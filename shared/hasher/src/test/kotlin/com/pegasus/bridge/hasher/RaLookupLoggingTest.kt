package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The API key must not reach a log, and the way to know is to make a request
 * fail for real and read what was written.
 *
 * Asserting on [com.pegasus.bridge.core.SafeUrl] alone would prove the helper
 * works, not that the call site uses it — which was exactly the defect: the
 * helper did not exist and `getWithRetry` logged `$url` whole, API key included.
 */
class RaLookupLoggingTest {

    private val captured = StringBuilder()

    private val capturingLog = object : BridgeLog {
        private fun add(msg: String, t: Throwable?) {
            synchronized(captured) {
                captured.append(msg).append('\n')
                t?.let { captured.append(it.toString()).append('\n') }
            }
        }
        override fun d(tag: String, msg: String) = add(msg, null)
        override fun i(tag: String, msg: String) = add(msg, null)
        override fun w(tag: String, msg: String, t: Throwable?) = add(msg, t)
        override fun e(tag: String, msg: String, t: Throwable?) = add(msg, t)
    }

    @BeforeTest fun setUp() { BridgeLog.current = capturingLog }
    @AfterTest  fun tearDown() { BridgeLog.current = StderrLog }

    /** A port with nothing behind it: every attempt fails fast and for real. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun `an exhausted retry logs the endpoint but never the api key`() = runTest {
        val key = "SENTINEL-RA-KEY-0123456789"
        val lookup = RaApiHashLookup("someuser", key, "http://127.0.0.1:${deadPort()}")

        // Fails at every attempt — connection refused — so the retry-exhaustion
        // branch that carries the URL is the one that runs. `runTest` skips the
        // back-off delays, so this costs milliseconds rather than seven seconds.
        val result = lookup.lookup("8e3630186e35d477231bf8fd50e54cdd").asLegacy()

        val log = captured.toString()
        assertTrue(result == null, "an unreachable source must not answer")
        assertTrue(log.isNotEmpty(), "the failure should have been logged at all")
        assertFalse(log.contains(key), "the API key reached the log:\n$log")
        assertFalse(log.contains("y=$key"), "the API key reached the log:\n$log")
    }

    // A hash is not a secret, and losing it would make the line useless: the whole
    // point of the log is saying which ROM the source would not answer about.
    @Test fun `the hash under lookup is still logged`() = runTest {
        val hash = "8e3630186e35d477231bf8fd50e54cdd"
        RaApiHashLookup("someuser", "SENTINEL-RA-KEY", "http://127.0.0.1:${deadPort()}")
            .lookup(hash)
        assertTrue(captured.toString().contains(hash),
                   "the log must still say which hash failed:\n$captured")
    }
}
