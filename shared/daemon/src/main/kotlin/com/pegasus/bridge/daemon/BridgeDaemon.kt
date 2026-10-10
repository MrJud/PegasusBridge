package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.hasher.ArchiveAwareHasher
import com.pegasus.bridge.hasher.DeviceConnection
import com.pegasus.bridge.hasher.NativeRomHasher
import com.pegasus.bridge.hasher.RETROACHIEVEMENTS_URL
import com.pegasus.bridge.hasher.RaApiHashLookup
import com.pegasus.bridge.hasher.RaGameList
import com.pegasus.bridge.hasher.RaHashLookup
import com.pegasus.bridge.hasher.RomHasher
import com.pegasus.bridge.hasher.RomScanPipeline
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

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
    private val advertisePort: Int = 0,
    hashWorkers: Int = RomScanPipeline.DEFAULT_HASH_WORKERS,
    /**
     * Where the ROM hasher comes from: the native library, or null when it is
     * not to be found.
     *
     * A parameter so that a test can hand [start] a hasher of its own. The
     * daemon's tests find no library, and with no hasher no pipeline is built,
     * so nothing a scan is given here could be seen from one: with
     * [hashWorkers] left out where the pipeline is built, every test there was
     * still passed.
     */
    private val loadHasher: () -> RomHasher? = { nativeHasher() },
    /**
     * Whether this machine is certain it has no connection, which a scan's
     * lookup asks when a request of its own has failed ([HostNetwork]).
     *
     * A parameter, with [raBaseUrl], for the reason [loadHasher] is one: so
     * that a test can see what a scan is given where the pipeline is built.
     * The two together let it run a scan whose requests fail at once, against
     * a port with nothing behind it, on a machine it can call offline.
     *
     * That test gives its own, so what it holds is that the lookup is handed
     * the check. That the check is [HostNetwork]'s when none is given is not
     * under any test: one would have to take this machine's network away.
     */
    private val deviceOffline: () -> Boolean = { HostNetwork.offline() },
    private val raBaseUrl: String = RETROACHIEVEMENTS_URL
) {

    /**
     * How many files a scan is told to read and hash at once: the pipeline's
     * own count unless `--hash-workers=N` gives another. On a machine with
     * fewer cores a scan runs one for each ([RomScanPipeline.hashProducers]).
     *
     * Which count is quickest depends on what the library is kept on, and that
     * differs from one machine to the next and cannot be told from here.
     *
     * Held between 1 and [MAX_HASH_WORKERS] whatever was asked. The pipeline
     * refuses to be built with no worker, and it is built when a scan is
     * requested: a 0 passed through would start a daemon that answers
     * `/health` and fails every scan.
     */
    val hashWorkers: Int = hashWorkers.coerceIn(1, MAX_HASH_WORKERS)

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
                buildScanPipeline(
                    paths,
                    ArchiveAwareHasher(it, File(dataRoot, "tmp")),
                    RaApiHashLookup(ra?.user.orEmpty(), ra?.apiKey.orEmpty(), File(paths.cache, RaGameList.DIR),
                                    raBaseUrl, device = DeviceConnection(deviceOffline)),
                    hashWorkers
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
        if (!managed) runCatching { deleteEndpointFileIfStillOurs() }
    }

    /**
     * Removes the endpoint file, but only while it still describes *this* process.
     *
     * The unconditional delete this replaces lost a race that a session restart
     * runs every time: systemd starts the new instance, which writes the file,
     * and then the outgoing instance's shutdown hook deletes it. The result is a
     * daemon that is up and answering with no way for anything to find it —
     * observed on a live install, up thirteen hours on port 46029 with no
     * `daemon.json` at all, and a theme that reads exactly that file to learn the
     * port.
     *
     * Comparing the pid closes it exactly: an instance only ever withdraws its
     * own advertisement, and a file naming somebody else is somebody else's to
     * remove.
     */
    private fun deleteEndpointFileIfStillOurs() {
        val f = DaemonPaths.endpointFile(dataRoot)
        if (!f.isFile) return
        val advertised = runCatching { JSONObject(f.readText()).optLong("pid", -1L) }
            .getOrDefault(-1L)
        val mine = ProcessHandle.current().pid()
        // -1 covers a file too old to carry a pid and one that will not parse.
        // Removing it is right in both cases: nothing else claims it, and leaving
        // an unreadable pointer behind helps nobody.
        if (advertised == mine || advertised == -1L) f.delete()
        else BridgeLog.i(TAG, "leaving daemon.json alone: it advertises pid $advertised, not $mine")
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
        const val MAX_HASH_WORKERS = 16

        // Internal, with the function below, for [ScanAudit]: the scan it
        // reports on has to be the one a daemon runs, down to the library it
        // loads.
        internal fun nativeHasher(): RomHasher? =
            DaemonPaths.nativeLibraryCandidates()
                .firstNotNullOfOrNull { NativeRomHasher.tryLoad(it) }
                ?: NativeRomHasher.tryLoad()

        /**
         * The pipeline a scan of this daemon runs, around the hasher and the
         * lookup it is given.
         *
         * Here and not where [start] needs it, so that [ScanAudit] is handed
         * the same one. An audit that built a pipeline of its own would go on
         * measuring the scan of the day it was written: an argument added to
         * the daemon's would not reach it, and nothing would say that the two
         * had come apart.
         */
        internal fun buildScanPipeline(
            paths: BridgePaths,
            hasher: RomHasher,
            lookup: RaHashLookup,
            hashWorkers: Int
        ): RomScanPipeline = RomScanPipeline(paths, hasher, lookup, hashWorkers = hashWorkers)

        /**
         * The daemon the command line asks for, not yet started.
         *
         * Apart from [main], which never returns, so that a test can ask what
         * a flag came to. While the flags were read in main itself, one could
         * be read and then left out of the constructor's arguments with
         * nothing to notice.
         */
        internal fun fromArgs(args: Array<String>): BridgeDaemon {
            val dataRoot = args.firstOrNull { it.startsWith("--data-root=") }
                ?.removePrefix("--data-root=")?.let(::File)
                ?: DaemonPaths.defaultDataRoot()
            val port = args.firstOrNull { it.startsWith("--port=") }
                ?.removePrefix("--port=")?.toIntOrNull() ?: 0
            val advertise = args.firstOrNull { it.startsWith("--advertise-port=") }
                ?.removePrefix("--advertise-port=")?.toIntOrNull() ?: 0
            // Without the flag, or with an N that is not a number, the count the
            // pipeline has by default.
            val hashWorkers = args.firstOrNull { it.startsWith("--hash-workers=") }
                ?.removePrefix("--hash-workers=")?.toIntOrNull()
                ?: RomScanPipeline.DEFAULT_HASH_WORKERS
            return BridgeDaemon(dataRoot, port, advertise, hashWorkers)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            // An audit is one scan, run to its end and written down: no server,
            // no endpoint file, and a data root of its own that it deletes. So
            // it is turned to before a daemon is made of the arguments.
            if (args.any { it.startsWith(ScanAudit.FLAG) }) exitProcess(ScanAudit.run(args))

            val daemon = fromArgs(args)
            val bound = daemon.start()
            println("PegasusBridge daemon listening on http://127.0.0.1:$bound")
            if (daemon.managed) println("advertised to clients as port ${daemon.advertisePort}")
            println("data root: ${daemon.dataRoot}")
            println("endpoint file: ${DaemonPaths.endpointFile(daemon.dataRoot)}")
            // The count in use, not the one asked for. The two differ when the
            // flag was out of range or was not a number, and when it is more
            // than the machine has cores: the pipeline starts no more producers
            // than that, and this printed the count it was given.
            println("hash workers: ${RomScanPipeline.hashProducers(daemon.hashWorkers)}")

            // Nothing else to do on the main thread; the server runs its own.
            Thread.currentThread().join()
        }
    }
}
