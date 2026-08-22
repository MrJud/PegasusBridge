package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.hasher.ArchiveAwareHasher
import com.pegasus.bridge.hasher.NativeRomHasher
import com.pegasus.bridge.hasher.RaApiHashLookup
import com.pegasus.bridge.hasher.RomScanPipeline
import com.pegasus.bridge.hasher.RomScanner
import com.pegasus.bridge.pegasus.MetadataFile
import org.json.JSONObject
import java.io.File

/**
 * The desktop daemon: a resident process serving the Bridge HTTP API on
 * loopback.
 *
 * Replaces Android's Router-plus-four-Services arrangement. The work itself is
 * the same shared code; what changes is that nothing here is fire-and-forget per
 * request — the process stays up, which is what lets the contract be one HTTP
 * call instead of a job id and a polling loop.
 */
class BridgeDaemon(
    val dataRoot: File = DaemonPaths.defaultDataRoot(),
    private val port: Int = 0,
    /**
     * The port to publish instead of the bound one, when something in front of
     * the daemon owns the address clients use.
     *
     * That is the case under socket activation: systemd holds the public port
     * and starts this process behind it, so the bound port is an implementation
     * detail a theme must never see. Setting this also stops the endpoint file
     * being deleted at shutdown — a client that cannot read the port cannot make
     * the connection that would start the daemon again.
     */
    private val advertisePort: Int = 0
) {

    lateinit var paths: BridgePaths; private set
    private lateinit var server: MicroHttpServer
    private lateinit var jobs: JobRegistry

    /** The bound port. Meaningful only after [start]. */
    val boundPort: Int get() = if (::server.isInitialized) server.port else 0

    fun start(): Int {
        paths = DaemonPaths.bridgePaths(dataRoot)
        val config = Config(paths, DaemonPaths.appDefaultsFile())
        jobs = JobRegistry(paths)

        // Scanning is optional: without a native hasher the daemon still serves
        // scraping and RetroAchievements, and /health says why.
        val hasher = loadHasher()
        val scanPipeline: (() -> RomScanPipeline)? = hasher?.let {
            {
                val ra = config.load().ra
                RomScanPipeline(
                    paths,
                    ArchiveAwareHasher(it, File(dataRoot, "tmp")),
                    RaApiHashLookup(ra?.user.orEmpty(), ra?.apiKey.orEmpty()),
                    // A collection states which extensions it contains, and the
                    // scanner's built-in list is only a default. They disagree
                    // more often than is comfortable — every collection in the
                    // library this was developed against declares one the list
                    // has never heard of — and the collection is the authority.
                    extensionsFor = ::collectionExtensions
                )
            }
        }

        val router = BridgeRouter(paths, config, jobs, scanPipeline)
        server = MicroHttpServer(requestedPort = port, handler = router::handle)
        server.start()

        writeEndpointFile()
        notifyReady()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })

        BridgeLog.i(TAG, "ready on 127.0.0.1:${server.port}, data root $dataRoot" +
            (if (hasher == null) " (no ROM hasher: ${NativeRomHasher.lastError()})" else ""))
        return server.port
    }

    fun stop() {
        if (::server.isInitialized) server.stop()
        // Under socket activation the file is the only way back in, so it
        // outlives the process on purpose.
        if (!managed) runCatching { DaemonPaths.endpointFile(dataRoot).delete() }
    }

    private val managed: Boolean get() = advertisePort > 0

    /**
     * Tells systemd the port is open, when systemd asked to be told.
     *
     * Under socket activation a proxy sits in front and connects as soon as this
     * unit counts as started. With `Type=simple` that is the instant the process
     * is forked, so the very first request arrives before the JVM has bound
     * anything and is answered with a connection refused — the theme sees one
     * empty response per cold start. `Type=notify` moves "started" to here.
     *
     * Done by running systemd's own helper rather than talking to NOTIFY_SOCKET
     * directly: that socket is a Unix *datagram* socket, which the JDK's channel
     * API cannot open. Requires `NotifyAccess=all`, since the message then comes
     * from the helper rather than from this process.
     *
     * A no-op outside systemd, and harmless if the helper is missing: the unit
     * file only asks for notification where the installer found it.
     */
    private fun notifyReady() {
        if (System.getenv("NOTIFY_SOCKET").isNullOrEmpty()) return
        runCatching {
            ProcessBuilder("systemd-notify", "--ready").start().waitFor()
        }.onFailure {
            BridgeLog.w(TAG, "could not signal readiness to systemd: ${it.message}")
        }
    }

    /**
     * What counts as a ROM in [dir]: the built-in set, plus whatever the
     * collection's own metadata file declares.
     *
     * A union rather than a replacement. A collection that forgets to list `zip`
     * should not thereby lose its archives, and one that adds a spelling of its
     * own should not need the Bridge to be rebuilt to see it.
     */
    private fun collectionExtensions(dir: File): Set<String> {
        val declared = runCatching { MetadataFile.readCollection(dir)?.extensions }
            .getOrNull().orEmpty()
        return if (declared.isEmpty()) RomScanner.ROM_EXTENSIONS
               else RomScanner.ROM_EXTENSIONS + declared
    }

    private fun loadHasher() =
        DaemonPaths.nativeLibraryCandidates()
            .firstNotNullOfOrNull { NativeRomHasher.tryLoad(it) }
            ?: NativeRomHasher.tryLoad()

    /**
     * Publishes the port so a client can find the daemon.
     *
     * This is the only file left in the contract: a theme reads it once to learn
     * the port, then speaks HTTP. Writing it last means its presence implies the
     * server is already accepting connections.
     */
    private fun writeEndpointFile() {
        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("port", if (managed) advertisePort else server.port)
            .put("dataRoot", dataRoot.absolutePath)
            .put("startedAt", BridgePaths.epochSeconds())
        if (managed) {
            // No pid: this file outlives the process, and a stale one would be
            // worse than none. `managed` says the port is somebody else's.
            payload.put("managed", true)
        } else {
            payload.put("pid", ProcessHandle.current().pid())
        }
        BridgePaths.writeAtomic(DaemonPaths.endpointFile(dataRoot), payload.toString(2))
    }

    companion object {
        private const val TAG = "BridgeDaemon"

        @JvmStatic
        fun main(args: Array<String>) {
            val dataRoot = args.firstOrNull { it.startsWith("--data-root=") }
                ?.removePrefix("--data-root=")?.let(::File)
                ?: DaemonPaths.defaultDataRoot()
            val port = args.firstOrNull { it.startsWith("--port=") }
                ?.removePrefix("--port=")?.toIntOrNull() ?: 0
            val advertise = args.firstOrNull { it.startsWith("--advertise-port=") }
                ?.removePrefix("--advertise-port=")?.toIntOrNull() ?: 0

            val daemon = BridgeDaemon(dataRoot, port, advertise)
            val bound = daemon.start()
            println("PegasusBridge daemon listening on http://127.0.0.1:$bound")
            if (advertise > 0) println("advertised to clients as port $advertise")
            println("data root: ${daemon.dataRoot}")
            println("endpoint file: ${DaemonPaths.endpointFile(daemon.dataRoot)}")

            // Nothing else to do on the main thread; the server runs its own.
            Thread.currentThread().join()
        }
    }
}
