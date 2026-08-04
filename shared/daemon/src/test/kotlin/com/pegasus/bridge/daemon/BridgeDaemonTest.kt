package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonPathsTest {

    @Test fun `data root follows XDG_DATA_HOME when it is set`() {
        val root = DaemonPaths.defaultDataRoot(
            env = mapOf("XDG_DATA_HOME" to "/custom/share"), home = "/home/u")
        assertEquals(File("/custom/share/pegasus-bridge"), root)
    }

    @Test fun `data root falls back to the standard share directory`() {
        val root = DaemonPaths.defaultDataRoot(env = emptyMap(), home = "/home/u")
        assertEquals(File("/home/u/.local/share/pegasus-bridge"), root)
    }

    // A relative XDG_DATA_HOME is invalid per the spec and must not be honoured.
    @Test fun `a blank or relative XDG_DATA_HOME is ignored`() {
        assertEquals(File("/home/u/.local/share/pegasus-bridge"),
            DaemonPaths.defaultDataRoot(mapOf("XDG_DATA_HOME" to ""), "/home/u"))
        assertEquals(File("/home/u/.local/share/pegasus-bridge"),
            DaemonPaths.defaultDataRoot(mapOf("XDG_DATA_HOME" to "relative/path"), "/home/u"))
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

    // Scanning is the only feature that needs the native library; everything else
    // must keep working without it.
    @Test fun `the api serves even when no native hasher is present`() {
        get("/health").use { r -> assertEquals(200, r.code) }
        get("/scrape?source=sgdb&op=search&term=x").use { r ->
            assertEquals(400, r.code, "should report missing credentials, not crash")
        }
    }
}
