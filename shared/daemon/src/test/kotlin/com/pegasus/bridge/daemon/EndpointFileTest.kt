package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `daemon.json` is the one file left in the contract: a theme reads it to learn
 * the port, then speaks HTTP. Losing it means a daemon that is up and answering
 * with nothing able to find it.
 *
 * Which is exactly what a session restart used to produce. systemd starts the
 * new instance, it writes the file, and the outgoing instance's shutdown hook
 * deletes it — observed on a live install: up thirteen hours on port 46029, no
 * `daemon.json` at all, and `BridgeApi.js` reading precisely that path.
 */
class EndpointFileTest {

    private lateinit var dataRoot: File

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("endpoint-test").toFile()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun endpoint() = DaemonPaths.endpointFile(dataRoot)

    @Test fun `a daemon withdraws its own advertisement on the way out`() {
        val d = BridgeDaemon(dataRoot)
        d.start()
        assertTrue(endpoint().isFile, "the endpoint file was never written")
        assertEquals(ProcessHandle.current().pid(), JSONObject(endpoint().readText()).getLong("pid"))

        d.stop()
        assertFalse(endpoint().isFile, "a daemon should take its own pointer with it")
    }

    // The race, reproduced without needing two processes: the file names somebody
    // else, so this instance must leave it where it is.
    @Test fun `a daemon leaves an advertisement that names another process alone`() {
        val d = BridgeDaemon(dataRoot)
        d.start()

        // Stand in for the successor that started after us and wrote its own.
        val theirs = JSONObject(endpoint().readText()).put("pid", 999_999).put("port", 46029)
        endpoint().writeText(theirs.toString())

        d.stop()

        assertTrue(endpoint().isFile,
                   "the outgoing instance deleted the pointer its successor had written")
        assertEquals(46029, JSONObject(endpoint().readText()).getInt("port"))
    }

    // A file from before the pid was recorded, or one that will not parse, is
    // nobody's claim. Leaving an unreadable pointer behind helps no one.
    @Test fun `an unreadable or pidless advertisement is cleaned up`() {
        val d = BridgeDaemon(dataRoot)
        d.start()
        endpoint().writeText("{ not json")
        d.stop()
        assertFalse(endpoint().isFile)

        val d2 = BridgeDaemon(dataRoot)
        d2.start()
        endpoint().writeText("""{"schemaVersion":1,"port":1234}""")
        d2.stop()
        assertFalse(endpoint().isFile, "a file with no pid claims nothing")
    }

    // Under socket activation the pointer is the only way back in: deleting it
    // makes the connection that would start the daemon unreachable.
    @Test fun `a managed daemon never withdraws the pointer`() {
        val d = BridgeDaemon(dataRoot, advertisePort = 38700)
        d.start()
        assertTrue(JSONObject(endpoint().readText()).optBoolean("managed"))
        d.stop()
        assertTrue(endpoint().isFile, "a socket-activated daemon must leave the way back in")
    }
}
