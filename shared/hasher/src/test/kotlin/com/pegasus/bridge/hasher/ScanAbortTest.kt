package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
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

    /**
     * The shape a real run takes: a library scanned before, then a rescan that
     * meets a source gone quiet partway through. It must return in seconds rather
     * than the twenty the tests above allow, leave no lookup running, and keep
     * the earlier match in the index — an abort is not a rollback. One worker per
     * stage, the narrowest pipeline that can be built.
     */
    @Test fun `an abort over a large library returns within seconds and keeps what was indexed`(): Unit = runBlocking {
        rom("nes", "Known.nes", "hash-known")
        val knows = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1446, "Super Mario Bros.", "NES", "/i.png", 76)
        }
        RomScanPipeline(paths, ContentHasher(), knows, throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))
        repeat(400) { rom("nes", "Unknown$it.nes", "unknown-$it") }

        val failures = AtomicInteger()
        val active = AtomicInteger()
        val quiet = object : RaHashLookup {
            override val consecutiveFailures: Int get() = failures.get()
            override suspend fun lookup(hash: String): GameMetadata? {
                active.incrementAndGet()
                try {
                    failures.incrementAndGet()
                    return null
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), quiet, throttleMs = { 0L },
                            hashWorkers = 1, apiWorkers = 1)
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(s.aborted)
        assertTrue(s.processed < s.total)
        assertTrue(s.reason.startsWith("the lookup source stopped answering"), s.reason)
        assertEquals(0, active.get(), "a lookup was still running after scan() returned")
        assertEquals(1, s.indexed, "the match from the earlier scan must survive the abort")
        assertEquals(s.indexed, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    /**
     * A hash is a blocking read, and the slow ones take minutes. Under a plain
     * withContext an abort had to wait for every read in flight, because the
     * scope cannot return before its children and a thread inside a read never
     * learns it was cancelled. With the hasher below, scan() came back only when
     * the fifteen-second read did.
     *
     * The read throws what an interrupted file channel throws, so this also
     * covers the other half: an interrupted read is the abort happening, not a
     * broken file, and the summary must not count it as one.
     */
    @Test fun `an abort interrupts a hash that is still reading`(): Unit = runBlocking {
        repeat(100) { rom("nes", "Game$it.nes", "hash-$it") }
        val stuck = CompletableDeferred<Unit>()
        val hashed = AtomicInteger()
        val slow = object : RomHasher {
            override fun hash(path: String): HashResult? {
                // The first few are quick, so the lookups have something to fail
                // on; the rest block the way a cold read of a disc image does.
                if (hashed.incrementAndGet() > 10) {
                    stuck.complete(Unit)
                    try {
                        Thread.sleep(15_000)
                    } catch (e: InterruptedException) {
                        throw java.nio.channels.ClosedByInterruptException()
                    }
                }
                val t = File(path).readText().trim()
                return HashResult(t, 7, fileMd5 = "md5-$t", fileCrc32 = "crc-$t")
            }
        }
        // Starts failing only once a read is known to be stuck, so the abort
        // always lands while one is in flight.
        val failures = AtomicInteger()
        val dead = object : RaHashLookup {
            override val consecutiveFailures: Int get() = failures.get()
            override suspend fun lookup(hash: String): GameMetadata? {
                stuck.await()
                failures.incrementAndGet()
                return null
            }
        }

        val started = System.nanoTime()
        val s = withTimeout(20_000) {
            RomScanPipeline(paths, slow, dead, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(s.aborted)
        assertTrue(tookMs < 5_000, "scan() took $tookMs ms: the abort waited for a read to finish")
        assertNull(s.states[ScanLedger.State.HASH_FAILED],
                   "an interrupted read was recorded as a broken file: ${s.states}")
    }

    /**
     * Cancelled from outside — the daemon shutting down, the Android service
     * being stopped — while one worker owns a lookup that will never answer and
     * another is waiting on it for a copy of the same ROM. Both must stop, and
     * the cancel must complete instead of waiting on the lookup.
     */
    @Test fun `cancelling the caller stops a lookup owner and the copies waiting on it`(): Unit = runBlocking {
        repeat(20) { rom("nes", "Copy$it.nes", "hash-same") }
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val hangs = object : RaHashLookup {
            override suspend fun lookup(hash: String): GameMetadata? {
                calls.incrementAndGet()
                active.incrementAndGet()
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        withTimeout(5_000) {
            val scan = launch {
                RomScanPipeline(paths, ContentHasher(), hangs, throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath))
            }
            started.await()
            // Time for the second worker to take a copy and start waiting on the owner.
            delay(200)
            scan.cancelAndJoin()
        }

        assertEquals(0, active.get(), "a lookup outlived the cancel")
        assertEquals(1, calls.get(), "the copies should have waited on the one lookup, not made their own")
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size)
    }
}
