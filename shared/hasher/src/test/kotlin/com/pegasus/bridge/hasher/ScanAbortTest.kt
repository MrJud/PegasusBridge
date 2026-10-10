package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NUL = "\u0000"

/**
 * What a test writes for a ROM: the text its hasher answers with, and a NUL
 * after it. A small file of nothing but text is a placeholder, which a scan
 * neither hashes nor asks about, and a ROM is never only text.
 */
private fun romText(content: String): String = content + NUL

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
        heldPorts.forEach { it.close() }
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
            val t = File(path).readText().removeSuffix(NUL).trim()
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
        override suspend fun lookup(hash: String): LookupOutcome {
            calls.incrementAndGet(); return null.asOutcome()
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
        override suspend fun lookup(hash: String): LookupOutcome {
            if (seen.incrementAndGet() <= n) {
                failures = 0
                val id = 1000 + (hash.substringAfter("hash-").toIntOrNull() ?: 0)
                return GameMetadata(id, "Game $id", "NES", "/i.png", 10).asOutcome()
            }
            failures++
            return null.asOutcome()
        }
        override val consecutiveFailures: Int get() = failures
    }

    private fun rom(platform: String, name: String, content: String) {
        File(romRoot, platform).apply { mkdirs() }
            .let { File(it, name).writeText(romText(content)) }
    }

    /** The seven counts together: on a scan cut short too, they are the files the collector saw. */
    private fun RomScanPipeline.Summary.counted(): Int =
        newEntries + cachedHits + skippedPlatforms + unmatched + incompatible + hashFailed + failedLookups

    private fun ledgerOnDisk(): JSONObject {
        val file = File(paths.cache, ScanLedger.FILE_NAME)
        assertTrue(file.isFile, "the scan left no ledger behind")
        return JSONObject(file.readText()).getJSONObject("entries")
    }

    /** How many entries the ledger on disk holds in each state. */
    private fun statesOnDisk(): Map<String, Int> = ledgerOnDisk().let { entries ->
        entries.keys().asSequence().groupingBy { entries.getJSONObject(it).getString("state") }.eachCount()
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
                GameMetadata(1, "One", "NES", "/i.png", 5).asOutcome()
        }

        val s = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), ok, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertFalse(s.aborted)
        assertEquals("", s.reason)
        assertNull(s.abortCause)
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
                GameMetadata(1446, "Super Mario Bros.", "NES", "/i.png", 76).asOutcome()
        }
        RomScanPipeline(paths, ContentHasher(), knows, throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))
        repeat(400) { rom("nes", "Unknown$it.nes", "unknown-$it") }

        val failures = AtomicInteger()
        val active = AtomicInteger()
        val quiet = object : RaHashLookup {
            override val consecutiveFailures: Int get() = failures.get()
            override suspend fun lookup(hash: String): LookupOutcome {
                active.incrementAndGet()
                try {
                    failures.incrementAndGet()
                    return null.asOutcome()
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
        assertEquals(RomScanPipeline.AbortCause.SOURCE_DOWN, s.abortCause)
        assertEquals(s.processed, s.counted(), "the counts of an aborted scan do not add up to what it saw")
        assertEquals(0, active.get(), "a lookup was still running after scan() returned")
        assertEquals(1, s.indexed, "the match from the earlier scan must survive the abort")
        assertEquals(s.indexed, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    /** Where the real lookup of these tests keeps its lists: where a daemon's does. */
    private fun lists() = File(paths.cache, RaGameList.DIR)

    /**
     * A server that answers every request with [status] and [body], and
     * counts them in [requests]. Stopped by whoever started it.
     */
    private fun answering(status: Int, body: String, requests: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    /**
     * A revoked key as RetroAchievements serves it, end to end: the request
     * for a console's list carries the key and is answered 401. It is the
     * first request of the scan, so the first lookup stops it, and the reason
     * names the key instead of a source that stopped answering. While a hash
     * was asked about without the key and only a match needed it, a library
     * with one ROM in four unknown went through without an abort: each miss
     * cleared the count of failures.
     */
    @Test fun `a refused key stops the scan at the first refusal`(): Unit = runBlocking {
        repeat(24) { i -> rom("nes", "Game$i.nes", "%032x".format(i + 1)) }
        val requests = AtomicInteger()
        val server = answering(401, """{"message":"Unauthenticated.","errors":[{"status":"401","code":"unauthorized","title":"Unauthenticated."}]}""", requests)
        try {
            val lookup = RaApiHashLookup("someuser", "revoked-key", lists(), "http://127.0.0.1:${server.address.port}")
            // One worker per stage, so the lookups run in a line and "after the
            // first refusal" means one thing.
            val s = withTimeout(20_000) {
                RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L },
                                hashWorkers = 1, apiWorkers = 1)
                    .scan(listOf(romRoot.absolutePath))
            }

            assertTrue(s.aborted, "a scan with a refused key ran to the end")
            assertTrue(s.reason.startsWith("RetroAchievements refused the API key"), s.reason)
            assertEquals(RomScanPipeline.AbortCause.KEY_REFUSED, s.abortCause)
            assertEquals(1, requests.get(), "the scan went on asking after the key was refused")
            assertEquals(1, s.failedLookups)
            assertEquals(1, s.states[ScanLedger.State.API_RETRY])
            assertEquals(mapOf("API_RETRY" to 1), statesOnDisk(), "a file was written off on a refused key")
            assertEquals(s.processed, s.counted())
            assertTrue(s.processed < s.total)
            assertEquals(emptyList(), lists().list().orEmpty().toList())
        } finally {
            server.stop(0)
        }
    }

    /**
     * RetroAchievements answers the list of the console with `[]`. No console
     * a scan asks about has no games, so this is an answer gone wrong, and
     * two things must not come of it. The files are not misses: a miss is
     * kept for fourteen days, and here it would be every file of the console.
     * And the scan is not stopped: the source answered, this is one console's
     * trouble, and stopped here the scan would stop here every time, with the
     * collections after it never reached.
     */
    @Test fun `a console whose list is empty does not stop the scan and writes nothing off`(): Unit = runBlocking {
        repeat(10) { i -> rom("nes", "Game$i.nes", "%032x".format(i + 1)) }
        val requests = AtomicInteger()
        val server = answering(200, "[]", requests)
        try {
            val lookup = RaApiHashLookup("someuser", "a-key", lists(), "http://127.0.0.1:${server.address.port}")
            val s = withTimeout(20_000) {
                RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath))
            }

            assertFalse(s.aborted, "the scan was stopped: ${s.reason}")
            assertEquals(10, s.processed)
            assertEquals(mapOf("API_RETRY" to 10), statesOnDisk())
            assertEquals(10, s.failedLookups)
            assertEquals(0, s.unmatched)
            assertEquals(1, requests.get())
            assertEquals(0, lookup.consecutiveFailures)
            assertEquals(emptyList(), lists().list().orEmpty().toList())
        } finally {
            server.stop(0)
        }
    }

    /**
     * RetroAchievements is down: every request is answered 503. The list is
     * asked for four times, for the first file, and is then known to be
     * missing: the files after it fail without a request, each counted, and
     * the eighth stops the scan. Asked for at every file it would be
     * thirty-two requests and close to a minute of back-off. These are real
     * seconds, the seven of the one back-off.
     */
    @Test fun `a source that answers no list stops the scan after eight lookups and four requests`(): Unit = runBlocking {
        repeat(12) { i -> rom("nes", "Game$i.nes", "%032x".format(i + 1)) }
        val requests = AtomicInteger()
        val server = answering(503, "unavailable", requests)
        try {
            val lookup = RaApiHashLookup("someuser", "a-key", lists(), "http://127.0.0.1:${server.address.port}")
            val s = withTimeout(30_000) {
                RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath))
            }

            assertEquals(RomScanPipeline.AbortCause.SOURCE_DOWN, s.abortCause, s.reason)
            assertEquals(4, requests.get())
            assertTrue(lookup.consecutiveFailures >= RomScanPipeline.MAX_CONSECUTIVE_FAILURES)
            assertEquals(s.processed, s.failedLookups)
            assertTrue(s.processed in 1..12, "processed ${s.processed}")
            assertEquals(mapOf("API_RETRY" to s.processed), statesOnDisk(), "a file was written off on a source that was down")
        } finally {
            server.stop(0)
        }
    }

    /**
     * Answers [answersFirst] lookups and then finds the device without a
     * connection, as [RaApiHashLookup] does when a request fails and the
     * platform says so: null, and [offline] from then on. Its count of
     * failures in a row is left at 0, so that nothing but [offline] can be
     * what stops a scan.
     *
     * It runs ahead of the collector as the real one does, and says it is
     * offline while the answers it gave before are still in the queue. The
     * pipeline goes by that only at a result whose lookup failed, so those
     * answers are taken in first and a test can count them.
     */
    private class Unplugged(private val answersFirst: Int = 0) : RaHashLookup {
        private val seen = AtomicInteger()
        @Volatile private var down = false
        override suspend fun lookup(hash: String): LookupOutcome {
            if (seen.incrementAndGet() <= answersFirst) {
                val id = 1000 + (hash.substringAfter("hash-").toIntOrNull() ?: 0)
                return GameMetadata(id, "Game $id", "NES", "/i.png", 10).asOutcome()
            }
            down = true
            return null.asOutcome()
        }
        override val offline: Boolean get() = down
    }

    private val heldPorts = ArrayList<java.net.Socket>()

    /**
     * A port with nothing behind it: every request to it is refused at once,
     * and for real. Bound and never listened on, and held until the test is
     * over. A port taken and given back is the next one the system hands out
     * now and then, to a server of the test's own or to anything else on the
     * machine, and the request would be answered.
     */
    private fun deadPort(): Int =
        java.net.Socket().also { it.bind(InetSocketAddress("127.0.0.1", 0)); heldPorts += it }.localPort

    /**
     * Thirteen files on a device with no connection. The scan went on until
     * eight lookups in a row had failed, each through its retries, and ended
     * as a source that had stopped answering. It stops at the first result,
     * which is the first lookup, and says what it was. One worker per stage,
     * so that the first result is that lookup's and no other is collected.
     *
     * Everything an abort keeps is kept: the file is a retry in the ledger and
     * not a miss, the ledger and the index are on disk, and the counts are of
     * the one file seen.
     */
    @Test fun `a lookup that finds the device offline stops the scan at that first failure`(): Unit = runBlocking {
        repeat(13) { rom("nes", "Game$it.nes", "hash-$it") }

        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), Unplugged(), throttleMs = { 0L },
                            hashWorkers = 1, apiWorkers = 1)
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(s.aborted, "a scan with no connection ran to the end")
        assertEquals(RomScanPipeline.AbortCause.OFFLINE, s.abortCause)
        assertTrue(s.reason.startsWith("no internet connection"), s.reason)
        assertEquals(13, s.total)
        assertEquals(1, s.processed)
        assertEquals(1, s.failedLookups)
        assertEquals(s.processed, s.counted())
        assertEquals(mapOf(ScanLedger.State.API_RETRY to 1), s.states)
        assertEquals(mapOf("API_RETRY" to 1), statesOnDisk(), "the file was written off, or the ledger not saved")
        assertEquals(0, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    // The connection goes in the middle of a scan. The next lookup is the one
    // that stops it, and not the eighth after it; the five matches made while
    // there was a connection are on disk and in the index. One worker per
    // stage, so that the results come in the order the lookups were made:
    // five answers and then the failure, however far the lookup is ahead of
    // the collector by then.
    @Test fun `a connection lost in the middle of a scan stops it there and keeps what was matched`(): Unit = runBlocking {
        repeat(20) { rom("nes", "Game$it.nes", "hash-$it") }

        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), Unplugged(answersFirst = 5), throttleMs = { 0L },
                            hashWorkers = 1, apiWorkers = 1)
                .scan(listOf(romRoot.absolutePath))
        }

        assertEquals(RomScanPipeline.AbortCause.OFFLINE, s.abortCause)
        assertEquals(6, s.processed)
        assertEquals(5, s.newEntries)
        assertEquals(1, s.failedLookups)
        assertEquals(s.processed, s.counted())
        assertEquals(mapOf("MATCHED" to 5, "API_RETRY" to 1), statesOnDisk())
        assertEquals(5, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size)
        assertEquals(5, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    // Both at once: eight failures in a row and a device that says it has no
    // connection, which is what a lookup with no pacing can reach before the
    // first result is collected. The scan is told the one a person can act on.
    @Test fun `a scan with no connection is not told that the source stopped answering`(): Unit = runBlocking {
        repeat(13) { rom("nes", "Game$it.nes", "hash-$it") }
        val both = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome = null.asOutcome()
            override val consecutiveFailures: Int get() = RomScanPipeline.MAX_CONSECUTIVE_FAILURES
            override val offline: Boolean get() = true
        }

        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), both, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertEquals(RomScanPipeline.AbortCause.OFFLINE, s.abortCause)
    }

    // And the other pair. With two requests at a time one can be answered
    // with a 401 while the other fails for want of a connection, and the key
    // is still the one to hear about: a connection that comes back does not
    // mend it.
    @Test fun `a scan whose key was refused is told so, with no connection as well`(): Unit = runBlocking {
        repeat(13) { rom("nes", "Game$it.nes", "hash-$it") }
        val both = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome = null.asOutcome()
            override val authRejected: Boolean get() = true
            override val offline: Boolean get() = true
        }

        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), both, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertEquals(RomScanPipeline.AbortCause.KEY_REFUSED, s.abortCause)
    }

    /**
     * A library that is mostly of no interest to RetroAchievements, with one
     * new file in it. The lookup says it has no connection before its result
     * is in the queue, and the files that need no lookup are going past the
     * collector all the while. Stopped at one of those, the scan would end
     * with no failed lookup in its counts and the new file in no ledger at
     * all.
     *
     * Held open here so that it happens every time. The collector is kept at
     * its first result until the lookup has said it is offline, and the
     * lookup is kept from handing its failure back until that result has
     * gone through. So one result at least, of a file that was never asked
     * about, is collected while the lookup says there is no connection.
     */
    @Test fun `a scan with no connection stops at the lookup that failed and not at a file that needed none`(): Unit = runBlocking {
        repeat(40) { rom("switch", "Game$it.nes", "content-$it") }
        rom("nes", "New.nes", "hash-new")
        val saidOffline = CountDownLatch(1)
        val anotherCollected = CompletableDeferred<Unit>()
        val lookup = object : RaHashLookup {
            @Volatile private var down = false
            override suspend fun lookup(hash: String): LookupOutcome {
                down = true
                saidOffline.countDown()
                anotherCollected.await()
                return null.asOutcome()
            }
            override val offline: Boolean get() = down
        }

        // With forty-one files there is a report for each result.
        val s = withTimeout(10_000) {
            RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath)) {
                    assertTrue(saidOffline.await(5, TimeUnit.SECONDS), "the new file was never asked about")
                    anotherCollected.complete(Unit)
                }
        }

        assertEquals(RomScanPipeline.AbortCause.OFFLINE, s.abortCause)
        assertEquals(1, s.failedLookups, "stopped at a file that needed no lookup, ${s.processed} of 41 in")
        assertEquals(s.processed - 1, s.skippedPlatforms)
        assertEquals(s.processed, s.counted())
        assertEquals(1, statesOnDisk()["API_RETRY"], "the file whose lookup failed is not in the ledger: ${statesOnDisk()}")
    }

    /**
     * The tablet's scan as it was run: thirteen files nobody has asked about,
     * the real lookup, and no connection. A port with nothing behind it
     * stands for the network that is not there, and the device says it has
     * none. It took 31 seconds, through four attempts and seven seconds of
     * back-off for each of eight lookups. No back-off is waited through now,
     * and these are real seconds: the first result of a scan that waited
     * would be seven of them away.
     *
     * One request is made, for the list of the files' console, and the
     * device is asked about that one. The lookups that follow it before the
     * scan stops find the list known to be missing and ask nobody.
     */
    @Test fun `thirteen files with no connection are stopped at once and none is written off`(): Unit = runBlocking {
        repeat(13) { rom("nes", "Game$it.nes", "hash-$it") }
        val asked = AtomicInteger()
        val lookup = RaApiHashLookup("someuser", "a-key", lists(), "http://127.0.0.1:${deadPort()}") {
            asked.incrementAndGet(); true
        }

        val started = System.nanoTime()
        val s = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(RomScanPipeline.AbortCause.OFFLINE, s.abortCause)
        assertTrue(tookMs < 3_000, "the scan took $tookMs ms: a back-off was waited through")
        assertEquals(1, asked.get(), "the scan went on asking after the first failure")
        assertEquals(1, lookup.requests)
        assertTrue(s.processed in 1 until 13, "processed ${s.processed} of 13")
        assertEquals(s.processed, s.failedLookups)
        assertEquals(s.processed, s.counted())
        assertEquals(mapOf("API_RETRY" to s.processed), statesOnDisk())
    }

    /**
     * A library scanned before, scanned again on a plane. Nothing needs
     * asking, so no request is made, none fails, and the device is never
     * asked what it thinks of its connection: the scan ends done, every file
     * found cached. What a device says explains a request that failed, and
     * here there is none to explain.
     */
    @Test fun `a library that needs no lookup is scanned to the end with no connection`(): Unit = runBlocking {
        repeat(13) { rom("nes", "Game$it.nes", "hash-$it") }
        val first = RomScanPipeline(paths, ContentHasher(), Unplugged(answersFirst = 13), throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))
        assertEquals(13, first.newEntries)

        val asked = AtomicInteger()
        val lookup = RaApiHashLookup("someuser", "a-key", lists(), "http://127.0.0.1:${deadPort()}") {
            asked.incrementAndGet(); true
        }
        val s = withTimeout(5_000) {
            RomScanPipeline(paths, ContentHasher(), lookup, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertFalse(s.aborted, "a scan that had nothing to ask was stopped: ${s.reason}")
        assertNull(s.abortCause)
        assertEquals(13, s.processed)
        assertEquals(13, s.cachedHits)
        assertEquals(0, asked.get(), "the device was asked about its connection with no request failed")
        assertFalse(lookup.offline)
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
                val t = File(path).readText().removeSuffix(NUL).trim()
                return HashResult(t, 7, fileMd5 = "md5-$t", fileCrc32 = "crc-$t")
            }
        }
        // Starts failing only once a read is known to be stuck, so the abort
        // always lands while one is in flight.
        val failures = AtomicInteger()
        val dead = object : RaHashLookup {
            override val consecutiveFailures: Int get() = failures.get()
            override suspend fun lookup(hash: String): LookupOutcome {
                stuck.await()
                failures.incrementAndGet()
                return null.asOutcome()
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
     * A lookup that holds its thread and has no suspension point: a blocking
     * read, as one written without a thought for cancellation makes. The scope
     * cancels the workers when the collector gives up, but a cancel is noticed
     * only where a coroutine suspends, and such a worker never does. The next
     * hash is waiting in the queue's buffer, the lookup blocks, and the result
     * queue has room. Each went on through every hash still queued while the
     * scope waited for it: 42 lookups here, the 32 of the buffer among them,
     * where 10 end the scan.
     *
     * The first eight are short, and long enough for the producers to fill the
     * queue behind them. The ones after are long, so that the scan has been
     * stopped well before a worker comes back for another.
     */
    @Test fun `a lookup that cannot be interrupted is not asked again once the scan has stopped`(): Unit = runBlocking {
        repeat(200) { rom("nes", "Game$it.nes", "hash-$it") }
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val deaf = object : RaHashLookup {
            override val consecutiveFailures: Int get() = failures.get()
            override suspend fun lookup(hash: String): LookupOutcome {
                Thread.sleep(if (calls.incrementAndGet() > RomScanPipeline.MAX_CONSECUTIVE_FAILURES) 300 else 30)
                failures.incrementAndGet()
                return null.asOutcome()
            }
        }

        val s = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), deaf, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(s.aborted)
        assertEquals(RomScanPipeline.AbortCause.SOURCE_DOWN, s.abortCause)
        // The eight that stop it, and the one each worker was held in by then.
        val atMost = RomScanPipeline.MAX_CONSECUTIVE_FAILURES + RomScanPipeline.DEFAULT_API_WORKERS
        assertTrue(calls.get() <= atMost,
                   "${calls.get()} lookups: a scan that had stopped went on asking, past the $atMost that end it")
    }

    /**
     * Cancelled from outside — the daemon shutting down, the Android service
     * being stopped — while one worker owns a lookup that will never answer and
     * another is waiting on it for a copy of the same ROM. The cancel must
     * complete instead of waiting on the lookup, the lookup must be cancelled,
     * and the copies must have shared it rather than made their own.
     *
     * The waiting copy stops because it is a child of the same scope, so this
     * does not depend on the owner settling its promise: it passes without that
     * line, which RomScanPipeline explains.
     */
    @Test fun `cancelling the caller cancels the one lookup the copies of a ROM share`(): Unit = runBlocking {
        repeat(20) { rom("nes", "Copy$it.nes", "hash-same") }
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val hangs = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
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

    /**
     * A scan cancelled from outside has no summary to return, and it used to
     * leave nothing else either: the ledger and the index were written after the
     * scope, on the way to the return, and a cancellation does not go that way.
     * The matches survived, each in its own file, though the index did not list
     * them. The answers that were "no" exist nowhere but in the ledger, so every
     * one of those files was read and asked about again by the next scan.
     *
     * Here the source answers six misses and a match and then hangs on the
     * eighth file, which is when the scan is cancelled.
     */
    @Test fun `a cancelled scan keeps the verdicts it reached`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        rom("nes", "Known.nes", "hash-known")
        rom("nes", "Hangs.nes", "hash-hangs")

        val hanging = CompletableDeferred<Unit>()
        val collected = CompletableDeferred<Unit>()
        val first = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome = when (hash) {
                "hash-hangs" -> { hanging.complete(Unit); awaitCancellation() }
                "hash-known" -> GameMetadata(1446, "Super Mario Bros.", "NES", "/i.png", 76)
                else         -> GameMetadata(gameId = 0)
            }.asOutcome()
        }

        var ended: Throwable? = null
        withTimeout(5_000) {
            val scan = launch {
                // Two lookups at a time, so the one that hangs does not hold up the
                // seven that are answered; and with eight files there is a report
                // for each, so the seventh says the answers have all been taken in.
                try {
                    RomScanPipeline(paths, ContentHasher(), first, throttleMs = { 0L })
                        .scan(listOf(romRoot.absolutePath)) { if (it.processed == 7) collected.complete(Unit) }
                } catch (t: Throwable) {
                    ended = t
                    throw t
                }
            }
            collected.await()
            hanging.await()
            scan.cancelAndJoin()
        }

        // Keeping what it found is not finishing: the caller is still told it was
        // cancelled, which is what it writes its own record from.
        assertTrue(ended is CancellationException, "scan() came back from a cancel with $ended")
        assertEquals(mapOf("NOT_FOUND" to 6, "MATCHED" to 1), statesOnDisk())
        val index = JSONObject(paths.discoveryIndex.readText())
        assertEquals(1, index.getInt("count"), "the match made before the cancel is not in the index")
        assertEquals(1446, index.getJSONArray("games").getJSONObject(0).getInt("gameId"))

        // The next scan reads and asks about the one file that was left.
        val hasher = ContentHasher()
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val second = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                asked += hash
                return GameMetadata(gameId = 0).asOutcome()
            }
        }
        val s = RomScanPipeline(paths, hasher, second, throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))

        assertEquals(listOf("hash-hangs"), asked)
        assertEquals(1, hasher.calls.get(), "a file whose verdict was kept was read again")
        assertEquals(1, s.cachedHits)
        assertEquals(7, s.unmatched)
    }

    // A failure nobody planned for ends the scan the same way a cancel does, and
    // skipped the ledger the same way. What threw still has to reach the caller,
    // which is how the daemon comes to report the job as failed.
    @Test fun `a lookup that throws still leaves the ledger on disk`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        rom("nes", "Breaks.nes", "hash-breaks")

        // It throws only once the six answers have been taken in, so what the
        // ledger should hold does not depend on which lookup ran first.
        val collected = CompletableDeferred<Unit>()
        val breaks = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                if (hash != "hash-breaks") return GameMetadata(gameId = 0).asOutcome()
                collected.await()
                throw java.io.IOException("the source fell over")
            }
        }

        // Not IllegalStateException: a timeout is one, and would pass for it.
        val thrown = assertFailsWith<java.io.IOException> {
            withTimeout(5_000) {
                RomScanPipeline(paths, ContentHasher(), breaks, throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath)) { if (it.processed == 6) collected.complete(Unit) }
            }
        }

        assertEquals("the source fell over", thrown.message)
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())
        assertEquals(0, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    // The index is rebuilt on the way out too, and that write can fail where the
    // ledger's cannot: save() keeps a failure to itself. A full disk must not be
    // what the caller hears about when it was a lookup that ended the scan, nor
    // cost the ledger, which is written after it. Here a directory sits where
    // the index goes, so the write fails however it is tried.
    @Test fun `an index that cannot be written does not hide what ended the scan`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        rom("nes", "Breaks.nes", "hash-breaks")
        File(paths.discoveryIndex, "in the way").apply { parentFile.mkdirs(); writeText("x") }

        val collected = CompletableDeferred<Unit>()
        val breaks = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                if (hash != "hash-breaks") return GameMetadata(gameId = 0).asOutcome()
                collected.await()
                throw java.io.IOException("the source fell over")
            }
        }

        val thrown = assertFailsWith<java.io.IOException> {
            withTimeout(5_000) {
                RomScanPipeline(paths, ContentHasher(), breaks, throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath)) { if (it.processed == 6) collected.complete(Unit) }
            }
        }

        assertEquals("the source fell over", thrown.message)
        assertTrue(paths.discoveryIndex.isDirectory, "the index was written after all")
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())
    }

    // The same index, met by a scan that nothing ended: it reaches its last
    // result and goes on to its summary. That write is not caught, because a
    // scan whose index is not on disk must not come back as one that finished,
    // and the ledger was saved after it, which is to say not at all. The six
    // answers here were then asked for again by the next scan.
    @Test fun `a scan that ends on an index it cannot write still leaves the ledger on disk`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        val inTheWay = File(paths.discoveryIndex, "in the way").apply { parentFile.mkdirs(); writeText("x") }

        // Not IllegalStateException here either: what is asked for is the write
        // that failed, and a timeout would pass for the other.
        assertFailsWith<java.io.IOException> {
            withTimeout(5_000) {
                RomScanPipeline(paths, ContentHasher(), SaysNo(), throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath))
            }
        }

        assertTrue(paths.discoveryIndex.isDirectory, "the index was written after all")
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())

        // With nothing in the way any more, the next scan reads no file and
        // asks about none, and writes the index the first could not.
        assertTrue(inTheWay.delete() && paths.discoveryIndex.delete())
        val hasher = ContentHasher()
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val second = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                asked += hash
                return GameMetadata(gameId = 0).asOutcome()
            }
        }
        val s = RomScanPipeline(paths, hasher, second, throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))

        assertEquals(emptyList<String>(), asked, "a miss the first scan settled was asked about again")
        assertEquals(0, hasher.calls.get(), "a file whose verdict was kept was read again")
        assertEquals(6, s.unmatched)
        assertTrue(paths.discoveryIndex.isFile)
    }

    /**
     * The ledger a cancel leaves is written while reads are being interrupted,
     * and an interrupted read throws what a broken file throws. The pipeline
     * tells the two apart before it records anything; this is the same question
     * as in the abort above, asked of the file on disk, which a cancel now
     * writes and which is all a cancel leaves to ask.
     */
    @Test fun `a cancel that interrupts a read does not write it down as a broken file`(): Unit = runBlocking {
        rom("nes", "Slow.nes", "hash-slow")
        val reading = CompletableDeferred<Unit>()
        val slow = object : RomHasher {
            override fun hash(path: String): HashResult? {
                reading.complete(Unit)
                try {
                    Thread.sleep(15_000)
                } catch (e: InterruptedException) {
                    throw java.nio.channels.ClosedByInterruptException()
                }
                return null
            }
        }

        withTimeout(5_000) {
            val scan = launch {
                RomScanPipeline(paths, slow, DeadLookup(), throttleMs = { 0L })
                    .scan(listOf(romRoot.absolutePath))
            }
            reading.await()
            scan.cancelAndJoin()
        }

        assertEquals(emptyMap<String, Int>(), statesOnDisk(), "the read the cancel interrupted is in the ledger")
    }

    /** Says no to everything: an answer each time, which is what fills a ledger. */
    private class SaysNo : RaHashLookup {
        override suspend fun lookup(hash: String) = GameMetadata(gameId = 0).asOutcome()
    }

    private val storage get() = File(romRoot, "storage")
    private val card get() = File(romRoot, "card")
    private val cardTakenOut get() = File(romRoot, "card taken out")

    /**
     * Three misses on one volume and five on another, scanned once so that all
     * eight stand in the ledger. Then the second volume goes, the way a card
     * that is taken out does, and one file is added to the first for the next
     * scan to stop on.
     */
    private suspend fun aLibraryWhoseCardWentAfterItsScan() {
        repeat(3) { rom("storage/nes", "Miss$it.nes", "miss-nes-$it") }
        repeat(5) { rom("card/snes", "Miss$it.sfc", "miss-snes-$it") }
        RomScanPipeline(paths, ContentHasher(), SaysNo(), throttleMs = { 0L })
            .scan(listOf(storage.absolutePath, card.absolutePath))
        assertEquals(mapOf("NOT_FOUND" to 8), statesOnDisk())

        assertTrue(card.renameTo(cardTakenOut))
        rom("storage/nes", "New.nes", "hash-new")
    }

    /** How many entries of the ledger on disk are for files under [dir]. */
    private fun onDiskUnder(dir: File): Int = ledgerOnDisk().keys().asSequence()
        .count { it.startsWith(dir.canonicalPath + File.separator) }

    /**
     * The ledger is pruned of what a scan's walk did not find: every entry under
     * a root it was given, and any whose file is gone, wherever it was. That was
     * done as the ledger was opened, which cost nothing while a scan had to
     * reach its end to save it. Saved on a cancel as well, the pruning went to
     * disk with it. So a scan started with a card out and cancelled, which is
     * what a person does on seeing the card is out, wrote off every verdict the
     * card's files had: of the eight here, three were left. A cancelled scan
     * only adds now, and the pruning waits for one that gets to its summary.
     */
    @Test fun `a cancelled scan does not drop the verdicts of a root that was not there`(): Unit = runBlocking {
        aLibraryWhoseCardWentAfterItsScan()
        val roots = listOf(storage.absolutePath, card.absolutePath)

        val hanging = CompletableDeferred<Unit>()
        val hangs = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                hanging.complete(Unit); awaitCancellation()
            }
        }
        withTimeout(5_000) {
            val scan = launch {
                RomScanPipeline(paths, ContentHasher(), hangs, throttleMs = { 0L }).scan(roots)
            }
            hanging.await()
            scan.cancelAndJoin()
        }

        assertEquals(5, onDiskUnder(card), "the cancel dropped the verdicts of the root that was not there")
        assertEquals(mapOf("NOT_FOUND" to 8), statesOnDisk())

        // The card is back. What the cancel kept is what the next scan does not
        // do again: it reads one file and asks about one, the new one.
        assertTrue(cardTakenOut.renameTo(card))
        val hasher = ContentHasher()
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val second = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                asked += hash
                return GameMetadata(gameId = 0).asOutcome()
            }
        }
        val s = RomScanPipeline(paths, hasher, second, throttleMs = { 0L }).scan(roots)

        assertEquals(listOf("hash-new"), asked)
        assertEquals(1, hasher.calls.get(), "a file whose verdict stood before the cancel was read again")
        assertEquals(9, s.unmatched)
    }

    // The other way out and the other rule. A scan of one collection, on the
    // volume that is there, ended by a lookup that throws: the card's files are
    // under no root it was given, and were dropped for not being there.
    @Test fun `a scan that fails does not drop the verdicts of files that were not there`(): Unit = runBlocking {
        aLibraryWhoseCardWentAfterItsScan()

        val breaks = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome =
                throw java.io.IOException("the source fell over")
        }
        assertFailsWith<java.io.IOException> {
            withTimeout(5_000) {
                RomScanPipeline(paths, ContentHasher(), breaks, throttleMs = { 0L })
                    .scan(listOf(storage.absolutePath))
            }
        }

        assertEquals(5, onDiskUnder(card), "the failed scan dropped the verdicts of files that were not there")
        assertEquals(mapOf("NOT_FOUND" to 8), statesOnDisk())
    }

    // A scan the pipeline stops itself is not one of those. It has walked every
    // root by then and goes on to its summary, so it drops what the walk did not
    // find as a scan that finished does, and as it did before the pruning moved.
    @Test fun `a scan the pipeline aborts still drops the verdict of a file that is gone`(): Unit = runBlocking {
        rom("nes", "Gone.nes", "miss-gone")
        rom("nes", "Stays.nes", "miss-stays")
        val gone = File(romRoot, "nes/Gone.nes"); val stays = File(romRoot, "nes/Stays.nes")
        RomScanPipeline(paths, ContentHasher(), SaysNo(), throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))
        assertTrue(ledgerOnDisk().has(gone.canonicalPath))

        assertTrue(gone.delete())
        repeat(40) { rom("nes", "New$it.nes", "hash-$it") }
        val s = withTimeout(20_000) {
            RomScanPipeline(paths, ContentHasher(), DeadLookup(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }

        assertTrue(s.aborted)
        assertFalse(ledgerOnDisk().has(gone.canonicalPath), "the aborted scan kept the entry of a file that is gone")
        assertEquals("NOT_FOUND", ledgerOnDisk().getJSONObject(stays.canonicalPath).getString("state"))
    }

    // ── A scan that is killed ───────────────────────────────────────────────

    /**
     * One file at a time through each stage, a report for every result, and
     * the ledger saved whenever [saveEveryMs] lets it be: 0 is after every
     * result that settled something. So at the report of the fourth result
     * the ledger holds four verdicts and no fifth.
     */
    private fun inStep(hasher: RomHasher, lookup: RaHashLookup, saveEveryMs: Long = 0) =
        RomScanPipeline(paths, hasher, lookup, throttleMs = { 0L }, hashWorkers = 1, apiWorkers = 1,
                        reportStep = { 1 }, ledgerSaveEveryMs = saveEveryMs)

    private val ledgerFile get() = File(paths.cache, ScanLedger.FILE_NAME)

    /**
     * A process that is killed runs no `catch` and no `finally`, so no test
     * can end a scan that way. What one can do is look at the disk as the
     * scan goes, which is all a kill leaves: the ledger is copied aside at the
     * fourth result of six, the scan runs to its end, and the copy is put
     * back in place of what the end wrote. The next scan starts from what a
     * kill after the fourth result would have left it.
     *
     * Before the ledger was saved along the way there was no file to copy.
     */
    @Test fun `what a scan had settled is on disk before it ends, and a scan started from that reads only the rest`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        val aside = File(dataRoot, "ledger-at-four.json")

        inStep(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath)) {
            if (it.processed == 4) ledgerFile.copyTo(aside)
        }

        val kept = JSONObject(aside.readText()).getJSONObject("entries")
        assertEquals(4, kept.length(), "the verdicts on disk at the fourth result")
        assertEquals(setOf("NOT_FOUND"), kept.keySet().map { kept.getJSONObject(it).getString("state") }.toSet())
        assertEquals(6, JSONObject(ledgerFile.readText()).getJSONObject("entries").length())

        aside.copyTo(ledgerFile, overwrite = true)
        val hasher = ContentHasher()
        val asked = AtomicInteger()
        val second = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                asked.incrementAndGet(); return GameMetadata(gameId = 0).asOutcome()
            }
        }
        val s = inStep(hasher, second).scan(listOf(romRoot.absolutePath))

        assertEquals(2, hasher.calls.get(), "a file whose verdict was on disk was read again")
        assertEquals(2, asked.get())
        assertEquals(6, s.unmatched)
    }

    // The same for matches. A match is in its metadata file before it is in
    // the ledger, so the two the copy does not hold are taken from their
    // files, and nothing at all is read.
    @Test fun `the matches a scan had made are on disk before it ends, and a scan started from that reads nothing`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Game$it.nes", "hash-$it") }
        val knows = object : RaHashLookup {
            val calls = AtomicInteger()
            override suspend fun lookup(hash: String): LookupOutcome {
                calls.incrementAndGet()
                val n = hash.substringAfterLast('-').toInt()
                return GameMetadata(5000 + n, "Game $n", "NES", "/i.png", 10).asOutcome()
            }
        }
        val aside = File(dataRoot, "ledger-at-four.json")

        inStep(ContentHasher(), knows).scan(listOf(romRoot.absolutePath)) {
            if (it.processed == 4) ledgerFile.copyTo(aside)
        }

        val kept = JSONObject(aside.readText()).getJSONObject("entries")
        assertEquals(4, kept.length())
        for (path in kept.keySet()) {
            val e = kept.getJSONObject(path)
            assertEquals("MATCHED", e.getString("state"))
            assertTrue(paths.metadata(e.getInt("gameId").toString()).isFile)
        }

        aside.copyTo(ledgerFile, overwrite = true)
        knows.calls.set(0)
        val hasher = ContentHasher()
        val s = inStep(hasher, knows).scan(listOf(romRoot.absolutePath))

        assertEquals(0, hasher.calls.get(), "a match was read again")
        assertEquals(0, knows.calls.get())
        assertEquals(6, s.cachedHits)
        assertEquals(mapOf("MATCHED" to 6), statesOnDisk())
    }

    // Each save writes the whole ledger, so a scan is told how often it may.
    @Test fun `a scan saves its ledger no more often than it is told`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        var thereAtFour: Boolean? = null

        inStep(ContentHasher(), SaysNo(), saveEveryMs = 3_600_000).scan(listOf(romRoot.absolutePath)) {
            if (it.processed == 4) thereAtFour = ledgerFile.exists()
        }

        assertEquals(false, thereAtFour, "the ledger was written before its time had come")
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())
    }

    // The longest wait there is means never in the middle. Turned into
    // nanoseconds it no longer fits its type and came out below zero, which
    // every result is later than: the ledger was written at each of them.
    @Test fun `a scan told to wait for ever saves its ledger only as it ends`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        var thereAtFour: Boolean? = null

        inStep(ContentHasher(), SaysNo(), saveEveryMs = Long.MAX_VALUE).scan(listOf(romRoot.absolutePath)) {
            if (it.processed == 4) thereAtFour = ledgerFile.exists()
        }

        assertEquals(false, thereAtFour, "the ledger was written in the middle of a scan told never to")
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())
    }

    // A rescan that finds every verdict standing records nothing, and has
    // nothing to write until its end.
    @Test fun `a rescan that settles nothing new does not write the ledger as it goes`(): Unit = runBlocking {
        repeat(6) { rom("nes", "Miss$it.nes", "miss-$it") }
        inStep(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        var thereAtSix: Boolean? = null

        // The ledger a scan works from has been read by the time the files
        // are counted out, so the file can go then: whatever is there before
        // the end, this scan wrote.
        val hasher = ContentHasher()
        var seen = 0
        inStep(hasher, SaysNo()).scan(listOf(romRoot.absolutePath), onCounted = { assertTrue(ledgerFile.delete()) }) {
            seen++
            if (it.processed == 6) thereAtSix = ledgerFile.exists()
        }

        assertEquals(0, hasher.calls.get())
        assertEquals(6, seen)
        assertEquals(false, thereAtSix, "a scan with nothing new to say wrote its ledger in the middle")
        assertEquals(mapOf("NOT_FOUND" to 6), statesOnDisk())
    }

    @Test fun `a pipeline told to save its ledger less than never is refused`() {
        assertFailsWith<IllegalArgumentException> {
            RomScanPipeline(paths, ContentHasher(), SaysNo(), ledgerSaveEveryMs = -1)
        }
    }
}
