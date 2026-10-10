package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.pegasus.bridge.hasher.HashResult
import com.pegasus.bridge.hasher.RomHasher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonPathsTest {

    // `os` is passed explicitly throughout: these ran on Linux only, so the Windows
    // branch had no coverage and the POSIX ones quietly changed meaning when the host
    // did. Naming the platform makes every case reachable from either machine.
    private val POSIX = "Linux"
    private val WIN   = "Windows 11"

    @Test fun `data root follows XDG_DATA_HOME when it is set`() {
        val root = DaemonPaths.defaultDataRoot(
            env = mapOf("XDG_DATA_HOME" to "/custom/share"), home = "/home/u", os = POSIX)
        assertEquals(File("/custom/share/pegasus-bridge"), root)
    }

    @Test fun `data root falls back to the standard share directory`() {
        val root = DaemonPaths.defaultDataRoot(env = emptyMap(), home = "/home/u", os = POSIX)
        assertEquals(File("/home/u/.local/share/pegasus-bridge"), root)
    }

    // A relative XDG_DATA_HOME is invalid per the spec and must not be honoured.
    @Test fun `a blank or relative XDG_DATA_HOME is ignored`() {
        assertEquals(File("/home/u/.local/share/pegasus-bridge"),
            DaemonPaths.defaultDataRoot(mapOf("XDG_DATA_HOME" to ""), "/home/u", POSIX))
        assertEquals(File("/home/u/.local/share/pegasus-bridge"),
            DaemonPaths.defaultDataRoot(mapOf("XDG_DATA_HOME" to "relative/path"), "/home/u", POSIX))
    }

    @Test fun `windows uses LOCALAPPDATA`() {
        val root = DaemonPaths.defaultDataRoot(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local"),
            home = "C:\\Users\\u", os = WIN)
        assertEquals(File("C:\\Users\\u\\AppData\\Local", "pegasus-bridge"), root)
    }

    // XDG is not a thing on Windows: honouring it there would scatter the data into
    // a directory nothing else on the machine knows about.
    @Test fun `windows ignores XDG_DATA_HOME`() {
        val root = DaemonPaths.defaultDataRoot(
            env = mapOf("XDG_DATA_HOME" to "/custom/share",
                        "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local"),
            home = "C:\\Users\\u", os = WIN)
        assertEquals(File("C:\\Users\\u\\AppData\\Local", "pegasus-bridge"), root)
    }

    // A user profile always has AppData\Local; deriving it beats refusing to start.
    @Test fun `windows falls back under the home directory when LOCALAPPDATA is missing`() {
        val root = DaemonPaths.defaultDataRoot(
            env = emptyMap(), home = "C:\\Users\\u", os = WIN)
        assertEquals(File("C:\\Users\\u\\AppData\\Local", "pegasus-bridge"), root)
    }

    @Test fun `library name matches the host platform`() {
        val name = DaemonPaths.libName()
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> assertEquals("rahasher.dll", name)
            os.contains("mac") -> assertEquals("librahasher.dylib", name)
            else               -> assertEquals("librahasher.so", name)
        }
    }
}

private const val NUL = "\u0000"

/**
 * What a test writes for a ROM: the text its hasher answers with, and a NUL
 * after it. A small file of nothing but text is a placeholder, which a scan
 * neither hashes nor asks about, and a ROM is never only text.
 */
private fun romText(content: String): String = content + NUL

class BridgeDaemonTest {

    private lateinit var dataRoot: File
    private lateinit var daemon: BridgeDaemon
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("daemon-test").toFile()
        BridgeLog.current = NoopLog
        daemon = BridgeDaemon(dataRoot)
        daemon.start()
    }

    @AfterTest fun tearDown() {
        daemon.stop()
        dataRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun get(p: String) = client.newCall(
        Request.Builder().url("http://127.0.0.1:${daemon.boundPort}$p").build()).execute()

    @Test fun `starting creates the whole directory layout`() {
        listOf("config", "metadata", "media", "search", "search-ra",
               "scrape", "download", "pending", "done", "profile", "completion")
            .forEach { assertTrue(File(dataRoot, it).isDirectory, "$it missing") }
    }

    // The endpoint file is the one thing a client reads before switching to HTTP,
    // so it has to be present and accurate the moment the server is up.
    @Test fun `the endpoint file advertises the bound port`() {
        val f = DaemonPaths.endpointFile(dataRoot)
        assertTrue(f.isFile, "daemon.json not written")
        val j = JSONObject(f.readText())
        assertEquals(daemon.boundPort, j.getInt("port"))
        assertEquals(dataRoot.absolutePath, j.getString("dataRoot"))
        assertTrue(j.getLong("pid") > 0)
    }

    @Test fun `the advertised port actually serves`() {
        val port = JSONObject(DaemonPaths.endpointFile(dataRoot).readText()).getInt("port")
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/health").build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("ok", JSONObject(r.body!!.string()).getString("status"))
            }
    }

    @Test fun `health reports the data root it is actually using`() {
        get("/health").use { r ->
            assertEquals(dataRoot.absolutePath, JSONObject(r.body!!.string()).getString("dataRoot"))
        }
    }

    @Test fun `stopping removes the endpoint file so nothing points at a dead port`() {
        assertTrue(DaemonPaths.endpointFile(dataRoot).isFile)
        daemon.stop()
        assertFalse(DaemonPaths.endpointFile(dataRoot).exists())
    }

    @Test fun `two daemons on different roots get different ports`() {
        val otherRoot = Files.createTempDirectory("daemon-test-2").toFile()
        val other = BridgeDaemon(otherRoot)
        try {
            other.start()
            assertTrue(other.boundPort != daemon.boundPort, "ports collided")
            assertEquals(other.boundPort,
                JSONObject(DaemonPaths.endpointFile(otherRoot).readText()).getInt("port"))
        } finally {
            other.stop(); otherRoot.deleteRecursively()
        }
    }

    // The count of the daemon main() would start on these arguments: fromArgs is
    // all there is of main() before start().
    private fun hashWorkersWith(vararg args: String): Int =
        BridgeDaemon.fromArgs(arrayOf(*args)).hashWorkers

    // Four is what a scan had before there was a flag. A daemon started the way
    // every installed unit starts it has to go on scanning as it did.
    @Test fun `the hash worker flag is taken when it is a number, and without one a scan keeps four`() {
        assertEquals(2, hashWorkersWith("--port=0", "--hash-workers=2"))
        assertEquals(4, hashWorkersWith("--port=0"))
        assertEquals(4, hashWorkersWith("--hash-workers=many"))
        assertEquals(4, hashWorkersWith("--hash-workers="))
        assertEquals(4, BridgeDaemon(dataRoot).hashWorkers)
    }

    // The pipeline refuses to be built with no worker, and the daemon builds it
    // only when a scan is asked for: a 0 that got through would cost every scan
    // and show nowhere before the first.
    @Test fun `a count of hash workers out of range is brought into it`() {
        assertEquals(1, hashWorkersWith("--hash-workers=0"))
        assertEquals(1, hashWorkersWith("--hash-workers=-3"))
        assertEquals(16, hashWorkersWith("--hash-workers=99"))
        assertEquals(16, hashWorkersWith("--hash-workers=16"))
        assertEquals(1, hashWorkersWith("--hash-workers=1"))
    }

    // Four flags are read one after the other and handed over by position.
    // Each has a number of its own here, so that one landing in another's
    // place shows. Nothing is started, so the two ports, which only a daemon
    // that is up shows, are not asked.
    @Test fun `a data root and a count of hash workers on one command line are both the daemon's`() {
        val d = BridgeDaemon.fromArgs(arrayOf(
            "--data-root=${dataRoot.absolutePath}", "--port=3", "--advertise-port=5", "--hash-workers=2"))
        assertEquals(dataRoot.absoluteFile, d.dataRoot.absoluteFile)
        assertEquals(2, d.hashWorkers)
    }

    /**
     * In place of the native library, which the daemon does not find under
     * test. It gives no hash, so nothing is looked up and no request leaves
     * the machine. Each file is held long enough for the hashes under way
     * side by side to overlap, and [peak] is the most there were at one
     * time: how many producers the scan ran.
     */
    private class HeldHasher : RomHasher {
        val calls = AtomicInteger()
        private val underWay = AtomicInteger()
        val peak = AtomicInteger()

        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            peak.accumulateAndGet(underWay.incrementAndGet(), ::maxOf)
            try { Thread.sleep(25) } finally { underWay.decrementAndGet() }
            return null
        }
    }

    /** The most files a daemon built with [workers] hashed at once, in one scan over HTTP. */
    private fun hashedAtOnce(workers: Int): Int {
        val root = Files.createTempDirectory("daemon-test-workers").toFile()
        val roms = File(root, "roms/snes").apply { mkdirs() }
        val files = 24
        repeat(files) { File(roms, "Game $it.sfc").writeText(romText("rom $it")) }

        val held = HeldHasher()
        // A key, which a scan is not started without. Nothing is asked with
        // it: no file here comes back from the hasher with a hash.
        Config(BridgePaths(File(root, "data"))).writeCredentials(raUser = "someone", raApiKey = "a-key")
        val scanning = BridgeDaemon(File(root, "data"), hashWorkers = workers, loadHasher = { held })
        try {
            scanning.start()
            fun at(p: String) = client.newCall(
                Request.Builder().url("http://127.0.0.1:${scanning.boundPort}$p").build()).execute()

            val started = at("/scan?roots=" + File(root, "roms").absolutePath)
                .use { JSONObject(it.body!!.string()) }
            assertEquals("started", started.optString("status"), "the daemon started no scan: $started")
            val jobId = started.getString("jobId")
            var job = JSONObject()
            for (attempt in 0 until 400) {
                Thread.sleep(50)
                job = at("/jobs/$jobId").use { JSONObject(it.body!!.string()) }
                if (job.getString("status") != "running") break
            }

            assertEquals("done", job.getString("status"), "the scan did not finish: $job")
            // Every file went through the hasher: a peak read off a scan that
            // hashed nothing would be no count at all.
            assertEquals(files, held.calls.get())
            assertEquals(files, job.getJSONObject("result").getJSONObject("states").getInt("HASH_FAILED"))
            return held.peak.get()
        } finally {
            scanning.stop(); root.deleteRecursively()
        }
    }

    // What the tests above read is a property of the daemon. What a scan is
    // given is the pipeline start() builds, and it builds one only around a
    // hasher: with none under test the count went nowhere a test could follow,
    // and a daemon that printed "hash workers: 2" and scanned with four passed.
    @Test fun `a scan hashes as many files at once as the daemon was told to`() {
        // The pipeline starts no more producers than there are cores.
        val cores = Runtime.getRuntime().availableProcessors()
        assertEquals(minOf(3, cores), hashedAtOnce(3))
        assertEquals(minOf(6, cores), hashedAtOnce(6))
        // 0 is the count the pipeline refuses: brought to 1, the scan runs. It
        // is also what tells the count from the default where the cores are
        // few, and three and six both come to as many as there are.
        assertEquals(1, hashedAtOnce(0))
    }

    /**
     * The scan a daemon runs on a machine with no connection, over HTTP and
     * with the lookup the daemon builds itself. No request leaves the machine:
     * RetroAchievements is a port with nothing behind it, which a request
     * fails on at once, and the machine is one the test says is offline. The
     * hasher gives each file its own text for a hash, so that there is
     * something to ask about.
     *
     * What it holds is the line in start() where the lookup is built. With the
     * check left out there, the lookup has nobody to ask, goes through its
     * retries, and the job is still running long after this has given up.
     */
    @Test fun `a scan on a machine that says it is offline ends as an error that says so`() {
        val root = Files.createTempDirectory("daemon-test-offline").toFile()
        val roms = File(root, "roms/snes").apply { mkdirs() }
        repeat(3) { File(roms, "Game $it.sfc").writeText(romText("hash-$it")) }
        val textHasher = object : RomHasher {
            override fun hash(path: String): HashResult = File(path).readText().removeSuffix(NUL)
                .let { HashResult(it, 3, fileMd5 = "md5-$it", fileCrc32 = "crc-$it") }
        }
        // Bound and never listened on, and held to the end: a request to it
        // is refused at once. A port taken and given back could be the one
        // the daemon is handed a line further down, and the lookup would be
        // answered by the daemon itself.
        val nobody = java.net.Socket().apply { bind(java.net.InetSocketAddress("127.0.0.1", 0)) }
        val asked = AtomicInteger()
        // A key to send: with none a scan stops before it asks anybody.
        Config(BridgePaths(File(root, "data"))).writeCredentials(raUser = "someone", raApiKey = "a-key")

        val scanning = BridgeDaemon(File(root, "data"), loadHasher = { textHasher },
                                    deviceOffline = { asked.incrementAndGet(); true },
                                    raBaseUrl = "http://127.0.0.1:${nobody.localPort}")
        try {
            scanning.start()
            fun at(p: String) = client.newCall(
                Request.Builder().url("http://127.0.0.1:${scanning.boundPort}$p").build()).execute()

            val started = at("/scan?roots=" + File(root, "roms").absolutePath)
                .use { JSONObject(it.body!!.string()) }
            assertEquals("started", started.optString("status"), "the daemon started no scan: $started")
            val jobId = started.getString("jobId")
            var job = JSONObject()
            // Five seconds, which is less than one lookup's retries take.
            for (attempt in 0 until 100) {
                Thread.sleep(50)
                job = at("/jobs/$jobId").use { JSONObject(it.body!!.string()) }
                if (job.getString("status") != "running") break
            }

            assertEquals("error", job.getString("status"), "the scan did not stop: $job")
            assertTrue(job.getString("error").startsWith("No internet connection: stopped after "),
                       job.getString("error"))
            assertTrue(asked.get() > 0, "the machine was never asked about its connection")
            val result = job.getJSONObject("result")
            assertTrue(result.getBoolean("aborted"))
            assertEquals(result.getInt("processed"), result.getJSONObject("states").getInt("API_RETRY"))
        } finally {
            scanning.stop(); root.deleteRecursively(); nobody.close()
        }
    }

    // Scanning is the only feature that needs the native library; everything else
    // must keep working without it.
    @Test fun `the api serves even when no native hasher is present`() {
        get("/health").use { r -> assertEquals(200, r.code) }
        get("/scrape?source=sgdb&op=search&term=x").use { r ->
            assertEquals(400, r.code, "should report missing credentials, not crash")
        }
    }
}
