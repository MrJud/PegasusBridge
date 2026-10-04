package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A scan whose lookups never answer must stop, and stopping must mean the call
 * returns.
 *
 * Breaking out of the collector loop only stopped the collector: the producers
 * went on hashing and went on sending into a queue nobody drained, so once its
 * 128-slot buffer filled they blocked forever and the surrounding scope waited
 * on them. Measured before the fix, on the 400-file library below: `scan()` had
 * not returned after 30 seconds. The abort meant to save a doomed run hung it.
 */
class ScanAbortTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("abort-data").toFile()
        romRoot  = Files.createTempDirectory("abort-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        /**
         * Set by the test once `scan()` has returned. A producer still running
         * after that is a leaked coroutine, and this is how one announces itself.
         */
        @Volatile var refuseFrom: Boolean = false
        @Volatile var calledAfterReturn: String? = null

        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            if (refuseFrom) calledAfterReturn = path
            val t = File(path).readText().trim()
            return HashResult(t, 7, fileMd5 = "md5-$t", fileCrc32 = "crc-$t")
        }
    }

    /**
     * Never answers. `null` is "no answer", which is what drives the failure
     * count — distinct from [GameMetadata] with gameId 0, which is the database
     * saying it does not know the hash.
     */
    private class DeadLookup : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata? {
            calls.incrementAndGet(); return null
        }
        override val consecutiveFailures: Int get() = calls.get()
    }

    /**
     * Answers for a while, then stops — the shape of a quota running out mid-scan.
     *
     * The game id comes from the hash rather than from a counter: two lookups
     * racing on a counter would mint the same id twice, and the second would
     * overwrite the first's metadata file, so the written count would not match
     * `newEntries` for reasons that have nothing to do with the abort.
     */
    private class FailsAfter(private val n: Int) : RaHashLookup {
        private val seen = AtomicInteger()
        @Volatile var failures = 0; private set
        override suspend fun lookup(hash: String): GameMetadata? {
            if (seen.incrementAndGet() <= n) {
                failures = 0
                val id = 1000 + (hash.substringAfter("hash-").toIntOrNull() ?: 0)
                return GameMetadata(id, "Game $id", "NES", "/i.png", 10)
            }
            failures++
            return null
        }
        override val consecutiveFailures: Int get() = failures
    }

    private fun rom(platform: String, name: String, content: String) {
        File(romRoot, platform).apply { mkdirs() }
            .let { File(it, name).writeText(content) }
    }

    // 400 files: far more than the 128-slot result queue, so the producers are
    // certain to be mid-send when the collector gives up.
    @Test fun `a dead lookup aborts the scan instead of hanging it`(): Unit = runBlocking {
        repeat(400) { rom("nes", "Game$it.nes", "hash-$it") }

        val summary = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), DeadLookup(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(summary.aborted, "the summary must say the scan was cut short")
        assertTrue(summary.reason.isNotEmpty(), "an aborted scan must say why")
        assertEquals(400, summary.total)
        assertTrue(summary.processed < summary.total,
                   "processed ${summary.processed} of ${summary.total}: " +
                   "an abort that saw everything is not an abort")
        assertTrue(summary.failedLookups > 0, "the failures that caused the abort must be counted")
        assertTrue(paths.discoveryIndex.isFile, "the index must still be written")
        JSONObject(paths.discoveryIndex.readText())   // valid JSON, or this throws
    }

    // The point of keeping the work: an abort is not a rollback. Whatever matched
    // before the source went quiet is on disk and in the index.
    @Test fun `matches found before the abort survive it`(): Unit = runBlocking {
        repeat(300) { rom("nes", "Game$it.nes", "hash-$it") }

        val summary = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), FailsAfter(20), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(summary.aborted)
        assertTrue(summary.newEntries > 0, "the games matched before the abort must be kept")
        val written = paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size
        assertEquals(summary.newEntries, written)
        assertEquals(written, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    // The failure mode this replaced was a producer blocked forever on a full
    // queue. Returning is necessary but not sufficient: the scan must also leave
    // nothing behind still chewing through the library. A producer that outlives
    // `scan()` announces itself by hashing one more file after the flag is set.
    @Test fun `an abort leaves no producer running`(): Unit = runBlocking {
        repeat(400) { rom("nes", "Game$it.nes", "hash-$it") }
        val hasher = ContentHasher()

        val s = withTimeout(20_000) {
            RomScanPipeline(paths, hasher, DeadLookup(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }
        assertTrue(s.aborted)

        hasher.refuseFrom = true
        val hashedByReturn = hasher.calls.get()
        // Long enough for a leaked producer to reach the next file; the queue it
        // would be draining still holds hundreds.
        Thread.sleep(1_500)

        assertEquals(null, hasher.calledAfterReturn,
                     "a producer was still hashing after scan() returned")
        assertEquals(hashedByReturn, hasher.calls.get(),
                     "the hash count kept rising after scan() returned")
    }

    // A scan that finishes normally must not claim to have been aborted.
    @Test fun `a healthy scan is not reported as aborted`(): Unit = runBlocking {
        repeat(30) { rom("nes", "Game$it.nes", "hash-$it") }
        val ok = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1, "One", "NES", "/i.png", 5)
        }

        val s = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), ok, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertFalse(s.aborted)
        assertEquals("", s.reason)
        assertEquals(s.total, s.processed, "a complete scan processes everything")
    }
}
