package com.pegasus.bridge.contract

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.pegasus.bridge.daemon.BridgeRouter
import com.pegasus.bridge.daemon.JobRegistry
import com.pegasus.bridge.daemon.MicroHttpServer
import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.HashResult
import com.pegasus.bridge.hasher.RaHashLookup
import com.pegasus.bridge.hasher.RomHasher
import com.pegasus.bridge.hasher.RomScanPipeline
import com.pegasus.bridge.hasher.ScanJobRecord
import com.pegasus.bridge.hasher.ScanLedger
import com.pegasus.bridge.ra.RaMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a scan leaves for its two readers, as it is today on both shells: the job
 * record and the marker the ReStory theme polls, and the metadata files and the
 * index the theme lists and [RaMatcher] resolves a ROM against. Written before
 * the Android scan is moved onto the shared pipeline, so that the move can be
 * held to it. The theme is [ThemeScanReader], a port of its code.
 *
 * In four parts.
 *
 * Android is replayed. HasherService cannot run in this build, so the files in
 * `theme-contract/android-before` are what it wrote when it was driven on a JVM
 * against stub Android classes, as it stood at 8ba0366, with every distinct body
 * of `pending/{jobId}.json` saved as it appeared. The RetroAchievements user in
 * its credentials was called `harness`. They are kept byte for byte:
 *
 * - `pending-first`, `pending-done`, `done-marker`, `metadata-sample` and
 *   `index-sample` from ten SNES files that all match, through the real
 *   RAApiClient against a local server answering as RetroAchievements does;
 * - `pending-running-rescan` and `pending-done-rescan` from the scan of those
 *   ten files that came next, `job2`, which found every one of them cached;
 * - `pending-running-mixed` and `pending-done-mixed` from a library where no two
 *   counts are the same: five SNES files that match, three files under
 *   `switch/`, two ROMs the server has never heard of and one it holds only
 *   under a virtual id. `pending-done-mixed-rescan` from its second scan, `job2`;
 * - `pending-running` and `error-cancelled` from 300 files, cancelled a second
 *   and a half in;
 * - `pending-done-incompatible` from ten files RetroAchievements holds only
 *   under a virtual id;
 * - `pending-empty` from a root with no ROM in it;
 * - `error-outage` from 600 files and a client whose every lookup fails;
 * - `error-key` from a server that answers the key with a 401;
 * - `error-no-credentials` from a credentials.json with no `ra` block.
 *
 * The desktop is live: the real router, registry and pipeline behind the real
 * server, with a hasher and a lookup the test holds.
 *
 * The files are the pipeline's own, set against the two Android samples.
 *
 * The job record is ScanJobRecord's, which HasherService writes through. It is
 * set against every replayed record, and then put in front of the theme with
 * the real pipeline behind it: a scan run to its end, a library with nothing
 * in it, an outage and a refused key, each kept the way a caller of the
 * pipeline is to keep it.
 *
 * Everything is compared as parsed values and never as text. Android's org.json
 * escapes `/` and orders keys differently from the library these tests run on,
 * and the theme parses either.
 */
class ThemeContractTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths
    private lateinit var server: MicroHttpServer

    // What the daemon's next scan is built from; a desktop test sets both first.
    private lateinit var hasher: RomHasher
    private lateinit var lookup: RaHashLookup

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Every lookup a test has stopped, so none is left waiting when the test ends. */
    private val held = ArrayList<CompletableDeferred<Unit>>()

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("contract-data").toFile()
        romRoot  = Files.createTempDirectory("contract-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog

        val router = BridgeRouter(
            paths, Config(paths), JobRegistry(paths),
            scanPipeline = { RomScanPipeline(paths, hasher, lookup, throttleMs = { 0L }) }
        )
        server = MicroHttpServer(handler = router::handle)
        server.start()
    }

    @AfterTest fun tearDown() {
        held.forEach { it.complete(Unit) }
        server.stop()
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    /** What the popup shows, in the order the hub declares its properties. */
    private data class View(
        val status: String, val percent: Int, val processed: Int, val total: Int,
        val currentFile: String, val newEntries: Int, val cached: Int
    )

    private fun ThemeScanReader.view() =
        View(status, percent, processed, total, currentFile, newEntries, cached)

    private fun fixture(name: String): String =
        javaClass.getResource("/theme-contract/android-before/$name")!!.readText()

    private fun rom(platform: String, name: String, content: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    /**
     * Each key with the kind of value under it: what a reader that takes fields
     * by name depends on, and all of it.
     */
    private fun kinds(o: JSONObject): Map<String, String> = o.keySet().associateWith { k ->
        when (o.get(k)) {
            is JSONObject -> "object"
            is JSONArray  -> "array"
            is String     -> "string"
            is Number     -> "number"
            is Boolean    -> "boolean"
            else          -> "null"
        }
    }.toSortedMap()

    // ── Android, replayed ───────────────────────────────────────────────────

    private val androidRunningKeys = setOf(
        "schemaVersion", "jobId", "verb", "status", "progress", "message",
        "newEntries", "cachedHits", "skippedPlatforms", "unmatched", "incompatible",
        "startedAt", "updatedAt")

    private val androidErrorKeys = setOf(
        "schemaVersion", "jobId", "verb", "status", "error", "startedAt", "updatedAt")

    private val androidDoneSentence = "Done — 10 new, 0 cached, 0 skipped, 0 not in the database"

    private val androidRescanSentence = "Done — 0 new, 10 cached, 0 skipped, 0 not in the database"

    private val androidMixedSentence = "Done — 5 new, 0 cached, 3 skipped, 2 not in the database, " +
        "1 dumps RetroAchievements does not support"

    private val androidMixedRescanSentence = "Done — 0 new, 5 cached, 3 skipped, 2 not in the database, " +
        "1 dumps RetroAchievements does not support"

    /** Puts a record where the service left it, which is under the id it carries. */
    private fun leave(record: String) = fixture("$record.json").let { body ->
        paths.pending(JSONObject(body).getString("jobId")).writeText(body)
    }

    private fun leaveMarker(job: String = "job1") = paths.done(job).writeText(fixture("done-marker.txt"))

    private fun androidTheme(job: String = "job1") =
        ThemeScanReader(ThemeFiles(dataRoot)).apply { launched(job) }

    @Test fun `an android record of a scan under way or done has these keys and no others`() {
        val statusOf = mapOf(
            "pending-first" to "running", "pending-running" to "running",
            "pending-running-rescan" to "running", "pending-running-mixed" to "running",
            "pending-done" to "done", "pending-done-incompatible" to "done", "pending-empty" to "done",
            "pending-done-rescan" to "done", "pending-done-mixed" to "done",
            "pending-done-mixed-rescan" to "done")
        for ((name, status) in statusOf) {
            val j = JSONObject(fixture("$name.json"))
            assertEquals(androidRunningKeys, j.keySet(), name)
            assertEquals(status, j.getString("status"), name)
            assertEquals(1, j.getInt("schemaVersion"), name)
            assertEquals("scan", j.getString("verb"), name)
            // A library's second scan was asked for as job2.
            assertEquals(if (name.endsWith("-rescan")) "job2" else "job1", j.getString("jobId"), name)
        }
    }

    /** The five counts of a record, in the order its closing sentence gives them. */
    private data class Counters(
        val newEntries: Int, val cachedHits: Int, val skippedPlatforms: Int,
        val unmatched: Int, val incompatible: Int
    )

    private fun counters(record: JSONObject) = Counters(
        record.getInt("newEntries"), record.getInt("cachedHits"), record.getInt("skippedPlatforms"),
        record.getInt("unmatched"), record.getInt("incompatible"))

    // Which number sits under which key, and in which clause of the sentence. A
    // first scan of ROMs that all match cannot say: one count moves and the other
    // four are 0 under whatever name they are written. The second scan of the
    // same ten files moves cachedHits and nothing else, and in the mixed library
    // every count is a different number, so one written in another's place reads
    // differently wherever it lands.
    @Test fun `an android record has each count under its own key and in its own clause`() {
        val records = mapOf(
            "pending-first" to (Counters(0, 0, 0, 0, 0) to "Scanning ROM folders…"),
            "pending-running" to (Counters(10, 0, 0, 0, 0) to "[10/300] g97c0.sfc"),
            "pending-done" to (Counters(10, 0, 0, 0, 0) to androidDoneSentence),
            "pending-empty" to (Counters(0, 0, 0, 0, 0) to "No ROMs found"),
            "pending-done-incompatible" to (Counters(0, 0, 0, 0, 10) to
                "Done — 0 new, 0 cached, 0 skipped, 0 not in the database, " +
                "10 dumps RetroAchievements does not support"),
            "pending-running-rescan" to (Counters(0, 10, 0, 0, 0) to "[10/10] game0.sfc"),
            "pending-done-rescan" to (Counters(0, 10, 0, 0, 0) to androidRescanSentence),
            "pending-running-mixed" to (Counters(5, 0, 3, 2, 1) to "[11/11] unknown1.sfc"),
            "pending-done-mixed" to (Counters(5, 0, 3, 2, 1) to androidMixedSentence),
            "pending-done-mixed-rescan" to (Counters(0, 5, 3, 2, 1) to androidMixedRescanSentence))
        for ((name, expected) in records) {
            val j = JSONObject(fixture("$name.json"))
            assertEquals(expected.first, counters(j), name)
            assertEquals(expected.second, j.getString("message"), name)
        }
    }

    // No progress and no counters: whatever the scan had reached is not in it.
    @Test fun `an android record of a scan that failed has these keys and no others`() {
        for (name in listOf("error-outage", "error-key", "error-cancelled", "error-no-credentials")) {
            val j = JSONObject(fixture("$name.json"))
            assertEquals(androidErrorKeys, j.keySet(), name)
            assertEquals("error", j.getString("status"), name)
            assertEquals(1, j.getInt("schemaVersion"), name)
            assertEquals("scan", j.getString("verb"), name)
            assertEquals("job1", j.getString("jobId"), name)
        }
    }

    @Test fun `the theme follows an android scan from its first record to a cancel`() {
        val theme = androidTheme()

        // The request is on its way and the service has written nothing yet.
        theme.readHasherProgress()
        assertEquals(View("", 0, 0, 0, "Starting...", 0, 0), theme.view())

        leave("pending-first")
        theme.readHasherProgress()
        assertEquals(View("running", 0, 0, 0, "Scanning ROM folders…", 0, 0), theme.view())

        leave("pending-running")
        theme.readHasherProgress()
        assertEquals(View("running", 3, 10, 300, "g97c0.sfc", 10, 0), theme.view())
        assertEquals("job1", theme.activeScanJobId())

        // The service writes the error and then the marker. The error is read
        // first and ends the poll there, so the marker never makes it "done".
        // The bar goes back to nothing, because the record carries no progress.
        leave("error-cancelled"); leaveMarker()
        theme.readHasherProgress()
        assertEquals(View("error", 0, 10, 300, "Cancelled", 10, 0), theme.view())
        assertEquals("", theme.activeJobId)

        theme.readHasherProgress()
        assertEquals("error", theme.status)
    }

    // "done" in the record is not what ends a scan for the theme. Between the
    // record and the marker it goes on as it was, with the closing sentence
    // where the file name used to be.
    @Test fun `an android scan is done for the theme when the marker is there, not before`() {
        val theme = androidTheme()

        leave("pending-done")
        theme.readHasherProgress()
        assertEquals(View("", 100, 0, 0, androidDoneSentence, 10, 0), theme.view())
        assertEquals("job1", theme.activeJobId)

        leaveMarker()
        theme.readHasherProgress()
        assertEquals(View("done", 100, 0, 0, androidDoneSentence, 10, 0), theme.view())
        assertEquals("", theme.activeJobId)
    }

    @Test fun `the theme shows the sentence an android scan ends on`() {
        val sentences = mapOf(
            "pending-done" to androidDoneSentence,
            "pending-done-incompatible" to "Done — 0 new, 0 cached, 0 skipped, 0 not in the database, " +
                "10 dumps RetroAchievements does not support",
            "pending-empty" to "No ROMs found")
        for ((name, sentence) in sentences) {
            leave(name); leaveMarker()
            val theme = androidTheme().apply { readHasherProgress() }
            assertEquals("done", theme.status, name)
            assertEquals(100, theme.percent, name)
            assertEquals(sentence, theme.currentFile, name)
        }
    }

    // "N cached" in the popup is cachedHits, and on a second scan of files that
    // have not changed it is the only number that moves.
    @Test fun `the theme shows what an android rescan found cached`() {
        val theme = androidTheme("job2")

        leave("pending-running-rescan")
        theme.readHasherProgress()
        assertEquals(View("running", 100, 10, 10, "game0.sfc", 0, 10), theme.view())
        assertEquals("job2", theme.activeScanJobId())

        leave("pending-done-rescan"); leaveMarker("job2")
        theme.readHasherProgress()
        assertEquals(View("done", 100, 10, 10, androidRescanSentence, 0, 10), theme.view())
        assertEquals("", theme.activeJobId)
    }

    // Of the five counts the theme takes two. In the mixed library neither can be
    // mistaken for one of the other three: 5 new and 0 cached on the first scan,
    // 0 new and 5 cached on the second, beside 3 skipped, 2 unknown and 1 unsupported.
    @Test fun `the theme takes new and cached from an android record and no other count`() {
        val theme = androidTheme()

        leave("pending-running-mixed")
        theme.readHasherProgress()
        assertEquals(View("running", 100, 11, 11, "unknown1.sfc", 5, 0), theme.view())

        leave("pending-done-mixed"); leaveMarker()
        theme.readHasherProgress()
        assertEquals(View("done", 100, 11, 11, androidMixedSentence, 5, 0), theme.view())

        val again = androidTheme("job2")
        leave("pending-done-mixed-rescan"); leaveMarker("job2")
        again.readHasherProgress()
        assertEquals(View("done", 100, 0, 0, androidMixedRescanSentence, 0, 5), again.view())
    }

    // "After 1 of 600" and not after eight: the count is of the results collected
    // when the abort was decided, and by the first of them eight lookups had
    // already failed. It moves from one run to the next; the rest does not.
    @Test fun `the theme shows an android error as the sentence in the record`() {
        val sentences = mapOf(
            "error-outage" to "RetroAchievements stopped responding after 1 of 600 files (0 identified). " +
                "Nothing was recorded as missing. " +
                "Wait a few minutes and scan again — it will resume where it left off.",
            "error-key" to "RetroAchievements refused the API key for harness after 1 of 30 files " +
                "(0 identified). Nothing was recorded as missing. " +
                "Copy the Web API key from your RetroAchievements settings into credentials.json " +
                "and scan again.",
            "error-cancelled" to "Cancelled",
            "error-no-credentials" to "Missing RA credentials in credentials.json")
        for ((name, sentence) in sentences) {
            leave(name); leaveMarker()
            val theme = androidTheme().apply { readHasherProgress() }
            assertEquals(View("error", 0, 0, 0, sentence, 0, 0), theme.view(), name)
            assertEquals("", theme.activeJobId, name)
        }
    }

    // Qt cannot tell an empty file from a missing one, so a marker with nothing
    // in it is a marker the theme never sees.
    @Test fun `the android done marker has content, which is all the theme asks of it`() {
        assertEquals("done", fixture("done-marker.txt"))

        val files = ThemeFiles(dataRoot)
        assertFalse(files.jobDone("job1"))
        paths.done("job1").writeText("")
        assertFalse(files.jobDone("job1"), "an empty marker must read as no marker")
        leaveMarker()
        assertTrue(files.jobDone("job1"))
    }

    // Why the service writes its first record before it walks the directories,
    // and what a start request turned away as a duplicate looks like from the
    // theme: nothing is written under its id, and four polls later it is over.
    @Test fun `a job that never writes a record reads as finished at the fifth poll`() {
        val theme = androidTheme()
        repeat(4) {
            theme.readHasherProgress()
            assertEquals("", theme.status, "poll ${it + 1}")
        }
        theme.readHasherProgress()
        assertEquals(View("done", 100, 0, 0, "Starting...", 0, 0), theme.view())
        assertEquals("", theme.activeJobId)
    }

    @Test fun `after a reload the theme returns to an android scan only while its record says running`() {
        fun returnsTo(record: String?, marker: Boolean = false): String {
            paths.pending("job1").delete(); paths.done("job1").delete()
            if (record != null) leave(record)
            if (marker) leaveMarker()
            return androidTheme().activeScanJobId()
        }
        assertEquals("job1", returnsTo("pending-first"))
        assertEquals("job1", returnsTo("pending-running"))
        assertEquals("", returnsTo("pending-done"))
        assertEquals("", returnsTo("pending-done", marker = true))
        assertEquals("", returnsTo("error-cancelled", marker = true))
        assertEquals("", returnsTo(null))
    }

    @Test fun `the android samples of a metadata file and of the index have these keys`() {
        val meta = JSONObject(fixture("metadata-sample.json"))
        assertEquals(setOf("schemaVersion", "gameId", "title", "platform", "cacheKey", "ra", "rom", "fetchedAt"),
                     meta.keySet())
        assertEquals(setOf("points", "progress", "unlocked", "total", "imageIcon", "fetchedAt"),
                     meta.getJSONObject("ra").keySet())
        assertEquals(setOf("hash", "fileMd5", "fileCrc32", "fileSize", "lastModified"),
                     meta.getJSONObject("rom").keySet())

        val index = JSONObject(fixture("index-sample.json"))
        assertEquals(setOf("schemaVersion", "fetchedAt", "count", "games", "byKey"), index.keySet())
        val games = index.getJSONArray("games")
        val byKey = index.getJSONObject("byKey")
        assertEquals(10, index.getInt("count"))
        assertEquals(10, games.length())
        assertEquals(10, byKey.length())
        // The same five fields in the list the theme shows and under each key.
        val entry = setOf("gameId", "title", "platform", "total", "imageIcon")
        for (g in games) assertEquals(entry, (g as JSONObject).keySet())
        for (k in byKey.keySet()) assertEquals(entry, byKey.getJSONObject(k).keySet(), k)
    }

    // The sample is snes/game9.sfc, which the local server called game 1000.
    @Test fun `the matcher finds a rom in the android index by its file name`() {
        val meta = JSONObject(fixture("metadata-sample.json"))
        assertEquals(1000, meta.getInt("gameId"))
        assertEquals(FuzzyMatch.makeCacheKey("game9", "snes"), meta.getString("cacheKey"))
        // As RetroAchievements gives it, a path on its own site.
        assertEquals("/Images/000001.png", meta.getJSONObject("ra").getString("imageIcon"))

        val m = RaMatcher.fromIndex(JSONObject(fixture("index-sample.json")),
                                    "Whatever The Library Calls It", "snes", "/storage/roms/snes/game9.sfc")
        assertEquals(1000, m?.gameId)
        assertEquals("Game 1000", m?.title)
        assertEquals("hash_index", m?.method)
    }

    // ── Desktop, live ───────────────────────────────────────────────────────

    private val desktopKeys = setOf(
        "schemaVersion", "jobId", "verb", "status", "progress", "message", "startedAt", "updatedAt")

    /** Beside [desktopKeys] from the first result on, and from then to the end. */
    private val desktopCounters = setOf("newEntries", "cachedHits", "skippedPlatforms")

    private val desktopResultKeys = setOf(
        "total", "processed", "newEntries", "cachedHits", "skippedPlatforms", "failedLookups",
        "indexed", "aborted", "reason", "states", "ambiguousArchives")

    private val catalogue = mapOf(
        "hash-alpha" to GameMetadata(2001, "Alpha", "SNES/Super Famicom", "/Images/002001.png", 10),
        "hash-beta"  to GameMetadata(2002, "Beta", "SNES/Super Famicom", "/Images/002002.png", 20),
        "hash-smw"   to GameMetadata(1001, "Super Mario World", "SNES/Super Famicom", "/Images/061234.png", 96)
    )

    /**
     * Hashes a file to its own text, and fills the plain hashes as
     * ArchiveAwareHasher does in production: without them a metadata file looks
     * like one from before they existed and is never taken as cached.
     */
    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        override fun hash(path: String): HashResult {
            calls.incrementAndGet()
            val text = File(path).readText().trim()
            return HashResult(text, 3, fileMd5 = "md5-$text", fileCrc32 = "crc-$text")
        }
    }

    private class MapLookup(private val map: Map<String, GameMetadata>) : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata {
            calls.incrementAndGet()
            return map[hash] ?: GameMetadata(gameId = 0)
        }
    }

    /** A lookup the test can stop at a hash, to read the job while it waits there. */
    private inner class GatedLookup(private val answer: (String) -> GameMetadata?) : RaHashLookup {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun hold(hash: String): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates[hash] = it; held += it }

        override suspend fun lookup(hash: String): GameMetadata? {
            gates[hash]?.await()
            return answer(hash)
        }
    }

    private fun inCatalogue(hash: String) = catalogue[hash] ?: GameMetadata(gameId = 0)

    /** The theme's XMLHttpRequest: the body whatever the status, null when nobody answered. */
    private fun httpGet(pathAndQuery: String): String? = try {
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}$pathAndQuery").build())
            .execute().use { it.body!!.string() }
    } catch (_: IOException) { null }

    private val http = ThemeHttp(::httpGet)

    /** Asks for a scan of the ROM root the way the hub does, under an id of the shape it makes up. */
    private fun desktopTheme(jobId: String) =
        ThemeScanReader(http).apply { launched(http.requestScan(romRoot.absolutePath, jobId)) }

    // Over HTTP a poll returns what the one before it fetched, so it takes two
    // to see where the daemon is now.
    private fun ThemeScanReader.pollTwice() { readHasherProgress(); readHasherProgress() }

    private fun job(id: String) = JSONObject(httpGet("/jobs/$id")!!)

    private fun mirror(id: String): JSONObject? =
        runCatching { JSONObject(paths.pending(id).readText()) }.getOrNull()

    /** The theme reading the daemon's files instead, as it did before there was a port to ask. */
    private fun filesView(id: String) =
        ThemeScanReader(ThemeFiles(dataRoot)).apply { launched(id); readHasherProgress() }.view()

    // A test waits on the daemon, never on the clock.
    private fun await(what: String, reached: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            check(System.nanoTime() < deadline) { "waited 20 s for $what" }
            Thread.sleep(5)
        }
    }

    /**
     * The job's body once its progress has reached [message]. Waits on the
     * mirror, which is written last: the registry sets the fields of an update
     * one by one, and a body read between two of them has the new message with
     * the old counters.
     */
    private fun runningAt(id: String, message: String): JSONObject {
        await("job $id to publish \"$message\"") { mirror(id)?.optString("message") == message }
        return job(id)
    }

    /**
     * The same when only the count can be named: which of several results that
     * need no lookup is collected last is up to the workers.
     */
    private fun runningAtCount(id: String, count: String): JSONObject {
        await("job $id to publish \"$count\"") { mirror(id)?.optString("message")?.startsWith("$count ") == true }
        return job(id)
    }

    /** The job's body once it is over. The mirror goes last of all, so that is what is waited for. */
    private fun finished(id: String): JSONObject {
        await("job $id to finish") { !paths.pending(id).exists() }
        return job(id)
    }

    private fun assertMarkedAndCleared(id: String) {
        assertTrue(paths.done(id).length() > 0, "the marker must have content for the theme to see it")
        assertTrue(ThemeFiles(dataRoot).jobDone(id))
        assertFalse(paths.pending(id).exists(), "the mirror must be gone once the job is over")
    }

    @Test fun `the theme follows a desktop scan from the request to the result`() {
        rom("snes", "Alpha.sfc", "hash-alpha")
        rom("snes", "Beta.sfc", "hash-beta")
        hasher = ContentHasher()
        val gated = GatedLookup(::inCatalogue)
        val alpha = gated.hold("hash-alpha")
        val beta = gated.hold("hash-beta")
        lookup = gated
        val id = "scan_1791233730000_4242"

        // Created, and no result yet. No message and no counters, where Android
        // has "Scanning ROM folders…" and five zeros.
        val theme = desktopTheme(id)
        var body = job(id)
        assertEquals(desktopKeys, body.keySet())
        assertEquals("running", body.getString("status"))
        assertEquals(0.0, body.getDouble("progress"))
        assertEquals("", body.getString("message"))
        assertEquals(body.toMap() - "updatedAt", mirror(id)!!.toMap() - "updatedAt")

        theme.readHasherProgress()
        assertEquals(View("", 0, 0, 0, "Starting...", 0, 0), theme.view(), "the first poll has nothing to show")
        theme.readHasherProgress()
        assertEquals(View("running", 0, 0, 0, "", 0, 0), theme.view())
        assertEquals(theme.view(), filesView(id))

        alpha.complete(Unit)
        body = runningAt(id, "[1/2] Alpha.sfc")
        assertEquals(desktopKeys + desktopCounters, body.keySet())
        assertEquals("running", body.getString("status"))
        assertEquals(0.5, body.getDouble("progress"))
        assertEquals(1, body.getInt("newEntries"))
        assertEquals(0, body.getInt("cachedHits"))
        assertEquals(0, body.getInt("skippedPlatforms"))
        assertEquals(body.toMap() - "updatedAt", mirror(id)!!.toMap() - "updatedAt")

        theme.pollTwice()
        assertEquals(View("running", 50, 1, 2, "Alpha.sfc", 1, 0), theme.view())
        assertEquals(theme.view(), filesView(id))
        assertEquals(id, theme.activeScanJobId())

        beta.complete(Unit)
        body = finished(id)
        assertEquals(desktopKeys + desktopCounters + "result", body.keySet())
        assertEquals("done", body.getString("status"))
        assertEquals(1.0, body.getDouble("progress"))
        assertEquals("[2/2] Beta.sfc", body.getString("message"))
        assertEquals(2, body.getInt("newEntries"))
        val result = body.getJSONObject("result")
        assertEquals(desktopResultKeys, result.keySet())
        assertEquals(2, result.getInt("total"))
        assertEquals(2, result.getInt("processed"))
        assertEquals(2, result.getInt("newEntries"))
        assertEquals(2, result.getInt("indexed"))
        assertFalse(result.getBoolean("aborted"))
        assertEquals("", result.getString("reason"))
        assertEquals(mapOf<String, Any>("MATCHED" to 2), result.getJSONObject("states").toMap())

        // No closing sentence on the desktop: the last file name stays on screen.
        theme.pollTwice()
        assertEquals(View("done", 100, 2, 2, "Beta.sfc", 2, 0), theme.view())
        assertEquals("", theme.activeJobId)

        assertMarkedAndCleared(id)
        assertEquals("done", filesView(id).status)
    }

    // A first scan cannot say which number is which: newEntries moves and the
    // other two stay at 0 under whatever name they are published. The second
    // scan of a library can. Of nine files one is new, two are as the scan
    // before left them, five sit in a folder of a platform RetroAchievements
    // does not cover, and one is a miss the ledger remembers: every count a
    // different number, in the running record and in the result. The popup's
    // "cached" is cachedHits and nothing else.
    @Test fun `a desktop rescan reports what it found cached apart from what it skipped`() {
        rom("snes", "Alpha.sfc", "hash-alpha")
        rom("snes", "Beta.sfc", "hash-beta")
        rom("snes", "Homebrew.sfc", "hash-nobody-knows")
        hasher = ContentHasher()
        val gated = GatedLookup(::inCatalogue)
        lookup = gated
        val first = "scan_1791233730000_6"

        desktopTheme(first)
        var result = finished(first).getJSONObject("result")
        assertEquals(2, result.getInt("newEntries"))
        assertEquals(0, result.getInt("cachedHits"))
        assertEquals(0, result.getInt("skippedPlatforms"))
        assertEquals(mapOf<String, Any>("MATCHED" to 2, "NOT_FOUND" to 1), result.getJSONObject("states").toMap())

        rom("snes", "Super Mario World (USA).sfc", "hash-smw")
        repeat(5) { rom("switch", "Game $it.zip", "never read") }
        val smw = gated.hold("hash-smw")
        val id = "scan_1791233730000_7"

        // Eight of the nine need no lookup, so the scan stands there until the
        // new ROM's is answered.
        val theme = desktopTheme(id)
        var body = runningAtCount(id, "[8/9]")
        assertEquals(desktopKeys + desktopCounters, body.keySet())
        assertEquals("running", body.getString("status"))
        assertEquals(0, body.getInt("newEntries"))
        assertEquals(2, body.getInt("cachedHits"))
        assertEquals(5, body.getInt("skippedPlatforms"))
        assertEquals(body.toMap() - "updatedAt", mirror(id)!!.toMap() - "updatedAt")
        val eighth = body.getString("message").removePrefix("[8/9] ")

        theme.pollTwice()
        assertEquals(View("running", 89, 8, 9, eighth, 0, 2), theme.view())
        assertEquals(theme.view(), filesView(id))

        smw.complete(Unit)
        body = finished(id)
        assertEquals(desktopKeys + desktopCounters + "result", body.keySet())
        assertEquals("done", body.getString("status"))
        assertEquals("[9/9] Super Mario World (USA).sfc", body.getString("message"))
        assertEquals(1, body.getInt("newEntries"))
        assertEquals(2, body.getInt("cachedHits"))
        assertEquals(5, body.getInt("skippedPlatforms"))
        result = body.getJSONObject("result")
        assertEquals(desktopResultKeys, result.keySet())
        assertEquals(9, result.getInt("total"))
        assertEquals(9, result.getInt("processed"))
        assertEquals(1, result.getInt("newEntries"))
        assertEquals(2, result.getInt("cachedHits"))
        assertEquals(5, result.getInt("skippedPlatforms"))
        assertEquals(0, result.getInt("failedLookups"))
        assertEquals(3, result.getInt("indexed"))
        assertEquals(mapOf<String, Any>("MATCHED" to 3, "NOT_FOUND" to 1, "UNSUPPORTED" to 5),
                     result.getJSONObject("states").toMap())

        theme.pollTwice()
        assertEquals(View("done", 100, 9, 9, "Super Mario World (USA).sfc", 1, 2), theme.view())
        assertMarkedAndCleared(id)
    }

    @Test fun `a desktop scan that finds no rom ends done with nothing to show`() {
        hasher = ContentHasher()
        lookup = MapLookup(catalogue)
        val id = "scan_1791233730000_1"

        val theme = desktopTheme(id)
        val body = finished(id)
        assertEquals(desktopKeys + "result", body.keySet())
        assertEquals("done", body.getString("status"))
        assertEquals(1.0, body.getDouble("progress"))
        assertEquals("", body.getString("message"))
        assertEquals(0, body.getJSONObject("result").getInt("total"))

        // Android says "No ROMs found" here.
        theme.pollTwice()
        assertEquals(View("done", 100, 0, 0, "", 0, 0), theme.view())
        assertMarkedAndCleared(id)
    }

    @Test fun `a hasher that throws costs a desktop scan its files and not the job`() {
        rom("snes", "Alpha.sfc", "hash-alpha")
        rom("snes", "Beta.sfc", "hash-beta")
        hasher = object : RomHasher {
            override fun hash(path: String): HashResult? = throw IOException("unreadable")
        }
        lookup = MapLookup(catalogue)
        val id = "scan_1791233730000_2"

        val theme = desktopTheme(id)
        val body = finished(id)
        assertEquals("done", body.getString("status"))
        assertEquals(0, body.getInt("newEntries"))
        val result = body.getJSONObject("result")
        assertEquals(2, result.getInt("processed"))
        assertEquals(0, result.getInt("indexed"))
        assertEquals(mapOf<String, Any>("HASH_FAILED" to 2), result.getJSONObject("states").toMap())

        theme.pollTwice()
        assertEquals("done", theme.status)
        assertEquals(100, theme.percent)
        assertEquals(2, theme.processed)
        assertEquals(2, theme.total)
        assertEquals(0, theme.newEntries)
        assertMarkedAndCleared(id)
    }

    // What fails a desktop job is something thrown out of the scan, here by the
    // lookup. The record keeps the progress and the counters it had, with the
    // error beside them, where Android's error record has neither.
    @Test fun `a desktop scan that fails reads as its error once the theme has seen it running`() {
        rom("snes", "Alpha.sfc", "hash-alpha")
        rom("snes", "Beta.sfc", "hash-beta")
        hasher = ContentHasher()
        val gated = GatedLookup { hash ->
            if (hash == "hash-beta") throw IllegalStateException("the lookup broke") else inCatalogue(hash)
        }
        val beta = gated.hold("hash-beta")
        lookup = gated
        val id = "scan_1791233730000_3"

        val theme = desktopTheme(id)
        runningAt(id, "[1/2] Alpha.sfc")
        theme.pollTwice()
        assertEquals(View("running", 50, 1, 2, "Alpha.sfc", 1, 0), theme.view())

        beta.complete(Unit)
        val body = finished(id)
        assertEquals(desktopKeys + desktopCounters + "error", body.keySet())
        assertEquals("error", body.getString("status"))
        assertEquals("the lookup broke", body.getString("error"))
        assertEquals(0.5, body.getDouble("progress"))
        assertEquals("[1/2] Alpha.sfc", body.getString("message"))

        theme.pollTwice()
        assertEquals(View("error", 50, 1, 2, "the lookup broke", 1, 0), theme.view())
        assertEquals("", theme.activeJobId)
        assertMarkedAndCleared(id)
    }

    // The theme's own doing; the daemon's record is as good as the one above.
    // The theme's poll takes an error that finds nothing stored before it for
    // the 404 of a daemon that forgot the job. So a scan that fails before the
    // first poll, two seconds in, is announced as that, and the poll after it
    // puts what went wrong in its place.
    @Test fun `a desktop error the theme never saw running reads at first as a job the bridge does not know`() {
        rom("snes", "Alpha.sfc", "hash-alpha")
        hasher = ContentHasher()
        lookup = object : RaHashLookup {
            override suspend fun lookup(hash: String): GameMetadata? = throw IllegalStateException("the lookup broke")
        }
        val id = "scan_1791233730000_4"

        val theme = desktopTheme(id)
        val body = finished(id)
        assertEquals(desktopKeys + "error", body.keySet())
        assertEquals("the lookup broke", body.getString("error"))

        theme.pollTwice()
        assertEquals(View("error", 0, 0, 0, "job unknown to the bridge", 0, 0), theme.view())
        theme.readHasherProgress()
        assertEquals(View("error", 0, 0, 0, "the lookup broke", 0, 0), theme.view())
    }

    // On Android the same outage is the record in error-outage.json: status
    // "error" and a sentence saying what to do. Here the pipeline's abort is an
    // ordinary return, the router finishes the job, and the theme, which reads
    // status and never `result`, puts up "Scan Complete".
    @Test fun `known divergence - the desktop reports an aborted scan as done`() {
        repeat(20) { rom("snes", "Game $it.sfc", "hash-$it") }
        hasher = ContentHasher()
        lookup = object : RaHashLookup {
            val calls = AtomicInteger()
            override suspend fun lookup(hash: String): GameMetadata? { calls.incrementAndGet(); return null }
            override val consecutiveFailures: Int get() = calls.get()
        }
        val id = "scan_1791233730000_5"

        val theme = desktopTheme(id)
        val body = finished(id)
        assertEquals("done", body.getString("status"))
        assertFalse(body.has("error"))
        val result = body.getJSONObject("result")
        assertTrue(result.getBoolean("aborted"))
        assertTrue(result.getString("reason").startsWith("the lookup source stopped answering"),
                   result.getString("reason"))
        assertTrue(result.getInt("processed") < 20, "an abort must not have gone through the library")

        theme.pollTwice()
        assertEquals("done", theme.status)
        assertEquals(100, theme.percent)
        assertEquals(result.getInt("processed"), theme.processed)
        assertEquals(20, theme.total)
        assertMarkedAndCleared(id)
    }

    // ── The files a scan writes ─────────────────────────────────────────────

    private fun pipeline(h: RomHasher, l: RaHashLookup) =
        RomScanPipeline(paths, h, l, throttleMs = { 0L })

    private suspend fun scan(h: RomHasher, l: RaHashLookup) =
        pipeline(h, l).scan(listOf(romRoot.absolutePath))

    /** A metadata file as the scan writes it, for a test to leave on disk before one. */
    private fun metadataJson(gameId: Int, title: String, cacheKey: String, rom: JSONObject) = JSONObject()
        .put("schemaVersion", 1).put("gameId", gameId).put("title", title)
        .put("platform", "snes").put("cacheKey", cacheKey)
        .put("ra", JSONObject().put("points", 0).put("progress", 0.0).put("unlocked", 0)
            .put("total", 96).put("imageIcon", "/Images/061234.png").put("fetchedAt", 1700000000))
        .put("rom", rom)
        .put("fetchedAt", 1700000000)

    @Test fun `the pipeline writes a metadata file and an index of the shape the android samples have`(): Unit = runBlocking {
        val rom = rom("snes", "Super Mario World (USA).sfc", "hash-smw")
        rom("snes", "Alpha.sfc", "hash-alpha")

        assertEquals(2, scan(ContentHasher(), MapLookup(catalogue)).newEntries)

        val sample = JSONObject(fixture("metadata-sample.json"))
        val meta = JSONObject(paths.metadata("1001").readText())
        assertEquals(kinds(sample), kinds(meta))
        assertEquals(kinds(sample.getJSONObject("ra")), kinds(meta.getJSONObject("ra")))
        assertEquals(kinds(sample.getJSONObject("rom")), kinds(meta.getJSONObject("rom")))

        // The key is made of the file's name without its extension and of the
        // folder it sits in.
        assertEquals(FuzzyMatch.makeCacheKey("Super Mario World (USA)", "snes"), meta.getString("cacheKey"))
        assertEquals("supermarioworld|snes", meta.getString("cacheKey"))
        // The icon stays as RetroAchievements gives it, a path on its own site.
        assertEquals("/Images/061234.png", meta.getJSONObject("ra").getString("imageIcon"))

        val sampleIndex = JSONObject(fixture("index-sample.json"))
        val index = JSONObject(paths.discoveryIndex.readText())
        assertEquals(kinds(sampleIndex), kinds(index))
        assertEquals(2, index.getInt("count"))
        val entry = kinds(sampleIndex.getJSONArray("games").getJSONObject(0))
        val games = index.getJSONArray("games")
        val byKey = index.getJSONObject("byKey")
        assertEquals(2, games.length())
        for (g in games) assertEquals(entry, kinds(g as JSONObject))
        assertEquals(setOf("supermarioworld|snes", "alpha|snes"), byKey.keySet())
        for (k in byKey.keySet()) assertEquals(entry, kinds(byKey.getJSONObject(k)), k)
        assertEquals("/Images/061234.png", byKey.getJSONObject("supermarioworld|snes").getString("imageIcon"))

        val m = RaMatcher.fromIndex(index, "Whatever The Library Calls It", "snes", rom.absolutePath)
        assertEquals(1001, m?.gameId)
        assertEquals("Super Mario World", m?.title)
        assertEquals("hash_index", m?.method)
    }

    // The 732 files on the tablet were written before the plain hashes existed.
    // Each is hashed and asked about once more, and comes back with every key.
    @Test fun `a metadata file without fileMd5 is hashed again and rewritten whole`(): Unit = runBlocking {
        val rom = rom("snes", "Super Mario World (USA).sfc", "hash-smw")
        paths.metadata("1001").writeText(metadataJson(1001, "Super Mario World", "supermarioworld|snes",
            JSONObject().put("hash", "hash-smw")
                .put("fileSize", rom.length()).put("lastModified", rom.lastModified())).toString(2))
        val hashes = ContentHasher()
        val answers = MapLookup(catalogue)

        val first = scan(hashes, answers)
        assertEquals(1, hashes.calls.get())
        assertEquals(1, answers.calls.get())
        assertEquals(1, first.newEntries)
        assertEquals(0, first.cachedHits)

        val sample = JSONObject(fixture("metadata-sample.json"))
        val meta = JSONObject(paths.metadata("1001").readText())
        assertEquals(kinds(sample), kinds(meta))
        assertEquals(kinds(sample.getJSONObject("rom")), kinds(meta.getJSONObject("rom")))
        assertEquals("md5-hash-smw", meta.getJSONObject("rom").getString("fileMd5"))

        val second = scan(hashes, answers)
        assertEquals(1, hashes.calls.get(), "the rewritten file must be taken as cached")
        assertEquals(1, second.cachedHits)
        assertEquals(0, second.newEntries)
    }

    // An id with no title, as 27 of the tablet's files have. Not a match: the
    // ROM is looked up again as if the file were not there, the index leaves it
    // out, and the file itself is left alone.
    @Test fun `a metadata file with a blank title is ignored and stays out of the index`(): Unit = runBlocking {
        val rom = rom("snes", "Metroid (Europe) (Virtual Console).sfc", "hash-vc")
        val stale = metadataJson(1100001487, "", "metroid|snes",
            JSONObject().put("hash", "hash-vc").put("fileMd5", "md5-hash-vc").put("fileCrc32", "crc-hash-vc")
                .put("fileSize", rom.length()).put("lastModified", rom.lastModified())).toString(2)
        paths.metadata("1100001487").writeText(stale)
        val hashes = ContentHasher()

        val s = scan(hashes, MapLookup(catalogue))
        assertEquals(1, hashes.calls.get(), "a file with no title must not make its ROM cached")
        assertEquals(0, s.cachedHits)
        assertEquals(0, s.indexed)

        val index = JSONObject(paths.discoveryIndex.readText())
        assertEquals(0, index.getInt("count"))
        assertEquals(0, index.getJSONArray("games").length())
        assertFalse(index.getJSONObject("byKey").has("metroid|snes"))
        assertEquals(stale, paths.metadata("1100001487").readText())
    }

    // Both writers replace the whole file, the Android one and the pipeline's.
    // What a refresh of the game had merged in under ra.detail goes with it when
    // the ROM changes and is identified again, and stays for as long as it does not.
    @Test fun `identifying a rom again drops the ra detail block from its file`(): Unit = runBlocking {
        val rom = rom("snes", "Super Mario World (USA).sfc", "hash-smw")
        val hashes = ContentHasher()
        val answers = MapLookup(catalogue + ("hash-smw-rev1" to catalogue.getValue("hash-smw")))
        scan(hashes, answers)

        val file = paths.metadata("1001")
        val withDetail = JSONObject(file.readText())
        withDetail.getJSONObject("ra").put("detail", JSONObject().put("NumAwardedToUser", 12))
        file.writeText(withDetail.toString(2))

        assertEquals(1, scan(hashes, answers).cachedHits)
        assertTrue(JSONObject(file.readText()).getJSONObject("ra").has("detail"),
                   "an unchanged ROM must leave its file as it is")

        rom.writeText("hash-smw-rev1")
        assertEquals(1, scan(hashes, answers).newEntries)
        val ra = JSONObject(file.readText()).getJSONObject("ra")
        assertFalse(ra.has("detail"))
        assertEquals(kinds(JSONObject(fixture("metadata-sample.json")).getJSONObject("ra")), kinds(ra))
    }

    @Test fun `the ledger file has these keys`(): Unit = runBlocking {
        val matched = rom("snes", "Super Mario World (USA).sfc", "hash-smw")
        val missed = rom("snes", "Homebrew.sfc", "hash-nobody-knows")
        val virtual = rom("snes", "Metroid (Europe) (Virtual Console).sfc", "hash-vc")
        val unsupported = rom("switch", "Some Game.zip", "never read")
        scan(ContentHasher(), MapLookup(catalogue + ("hash-vc" to GameMetadata(gameId = 1100001487))))

        val ledger = JSONObject(paths.cache(ScanLedger.FILE_NAME).readText())
        assertEquals(setOf("schemaVersion", "algorithmVersion", "updatedAt", "count", "entries"), ledger.keySet())
        assertEquals(ScanLedger.SCHEMA_VERSION, ledger.getInt("schemaVersion"))
        assertEquals(ScanLedger.ALGORITHM_VERSION, ledger.getInt("algorithmVersion"))
        assertEquals(4, ledger.getInt("count"))

        // One entry per file, under its canonical path. gameId and detail are
        // there only when there is one to give.
        val entries = ledger.getJSONObject("entries")
        val always = setOf("state", "checkedAt", "fileSize", "lastModified", "algorithmVersion")
        fun entry(f: File) = entries.getJSONObject(f.canonicalPath)
        assertEquals(setOf(matched, missed, virtual, unsupported).map { it.canonicalPath }.toSet(), entries.keySet())

        assertEquals(always + "gameId", entry(matched).keySet())
        assertEquals("MATCHED", entry(matched).getString("state"))
        assertEquals(1001, entry(matched).getInt("gameId"))

        assertEquals(always, entry(missed).keySet())
        assertEquals("NOT_FOUND", entry(missed).getString("state"))

        assertEquals(always + "gameId" + "detail", entry(virtual).keySet())
        assertEquals("NOT_FOUND", entry(virtual).getString("state"))
        assertEquals(1100001487, entry(virtual).getInt("gameId"))

        assertEquals(always + "detail", entry(unsupported).keySet())
        assertEquals("UNSUPPORTED", entry(unsupported).getString("state"))

        for (k in entries.keySet()) {
            assertEquals(ScanLedger.ALGORITHM_VERSION, entries.getJSONObject(k).getInt("algorithmVersion"), k)
        }
    }

    // ── The job record, built ───────────────────────────────────────────────

    private val stamps = setOf("startedAt", "updatedAt")

    /**
     * The two counts the pipeline keeps apart and the service's own loop did
     * not. Every record with counts has them now; the replayed ones, written by
     * that loop, do not.
     */
    private val failureCounts = setOf("hashFailed", "failedLookups")

    private val sevenCounts = listOf("newEntries", "cachedHits", "skippedPlatforms", "unmatched",
                                     "incompatible", "hashFailed", "failedLookups")

    /**
     * A record's fields with every number as the one kind. Which of Integer,
     * BigDecimal and Double the parser hands back goes by how the number was
     * spelt, and a progress of 1.0 is spelt `1`.
     */
    private fun fields(record: JSONObject): Map<String, Any> =
        record.toMap().mapValues { (_, v) -> if (v is Number) v.toDouble() else v }

    private fun replayed(name: String) = fields(JSONObject(fixture("$name.json")))

    // Each record built from the values the scan behind its fixture had
    // reached. A record with counts has the replayed keys and the two the
    // service did not write then, at 0 in every one of these scans, and no
    // other; an error record has the replayed keys. Every value but the two
    // stamps is compared, and those are looked for under their own names.
    @Test fun `the builder gives back every replayed android record, and two counts more where it has counts`() {
        fun counts(new: Int, cached: Int, skipped: Int, unmatched: Int, incompatible: Int) =
            ScanJobRecord.Counts(new, cached, skipped, unmatched, incompatible, hashFailed = 0, failedLookups = 0)
        val outage = ScanJobRecord.abortAdvice(RomScanPipeline.AbortCause.SOURCE_DOWN, "harness", 1, 600, 0)
        val refusal = ScanJobRecord.abortAdvice(RomScanPipeline.AbortCause.KEY_REFUSED, "harness", 1, 30, 0)
        val began = 1791233730L
        val wrote = 1791233732L
        val built = mapOf(
            "pending-first" to ScanJobRecord.started("job1", began, wrote),
            "pending-running" to
                ScanJobRecord.running("job1", 10, 300, "g97c0.sfc", counts(10, 0, 0, 0, 0), began, wrote),
            "pending-running-rescan" to
                ScanJobRecord.running("job2", 10, 10, "game0.sfc", counts(0, 10, 0, 0, 0), began, wrote),
            "pending-running-mixed" to
                ScanJobRecord.running("job1", 11, 11, "unknown1.sfc", counts(5, 0, 3, 2, 1), began, wrote),
            "pending-done" to ScanJobRecord.done("job1", counts(10, 0, 0, 0, 0), began, wrote),
            "pending-done-incompatible" to ScanJobRecord.done("job1", counts(0, 0, 0, 0, 10), began, wrote),
            "pending-done-rescan" to ScanJobRecord.done("job2", counts(0, 10, 0, 0, 0), began, wrote),
            "pending-done-mixed" to ScanJobRecord.done("job1", counts(5, 0, 3, 2, 1), began, wrote),
            "pending-done-mixed-rescan" to ScanJobRecord.done("job2", counts(0, 5, 3, 2, 1), began, wrote),
            "pending-empty" to ScanJobRecord.noRoms("job1", counts(0, 0, 0, 0, 0), began, wrote),
            "error-outage" to ScanJobRecord.error("job1", outage, began, wrote),
            "error-key" to ScanJobRecord.error("job1", refusal, began, wrote),
            "error-cancelled" to ScanJobRecord.error("job1", "Cancelled", began, wrote),
            "error-no-credentials" to
                ScanJobRecord.error("job1", "Missing RA credentials in credentials.json", began, wrote))

        // Every record in the directory, so that one added there is not left out here.
        val kept = File(javaClass.getResource("/theme-contract/android-before")!!.toURI()).list()!!
            .filter { it.startsWith("pending-") || it.startsWith("error-") }
            .map { it.removeSuffix(".json") }.toSet()
        assertEquals(kept, built.keys)

        for ((name, record) in built) {
            // As a reader finds it, which is as text.
            val written = JSONObject(record.toString())
            val added = if (name.startsWith("error-")) emptySet() else failureCounts
            assertEquals(replayed(name).keys + added, written.keySet(), name)
            for (k in added) assertEquals(0, written.getInt(k), "$name: $k")
            assertEquals(replayed(name) - stamps, fields(written) - stamps - added, name)
            assertEquals(began, written.getLong("startedAt"), name)
            assertEquals(wrote, written.getLong("updatedAt"), name)

            // And the theme makes of it what it makes of the one replayed.
            val job = written.getString("jobId")
            BridgePaths.writeAtomic(paths.pending(job), record.toString())
            val ofBuilt = androidTheme(job).apply { readHasherProgress() }.view()
            leave(name)
            assertEquals(androidTheme(job).apply { readHasherProgress() }.view(), ofBuilt, name)
        }
    }

    /** What a scan left in `pending`, each record as a reader found it, and what the pipeline returned. */
    private class Recorded(val records: List<JSONObject>, val summary: RomScanPipeline.Summary)

    /**
     * Runs the real pipeline and keeps its job record as ScanJobRecord has a
     * caller keep it: the first record before the walk, one for each report
     * that is due, the last from the summary. [afterEach] runs when a record is
     * in place, which is when a poll of the theme's can find it.
     */
    private suspend fun recordedScan(
        job: String, h: RomHasher, l: RaHashLookup, afterEach: () -> Unit = {}
    ): Recorded {
        val startedAt = BridgePaths.epochSeconds()
        val records = ArrayList<JSONObject>()
        fun write(record: JSONObject) {
            BridgePaths.writeAtomic(paths.pending(job), record.toString())
            records += JSONObject(paths.pending(job).readText())
            afterEach()
        }
        write(ScanJobRecord.started(job, startedAt))
        var published = 0
        val summary = pipeline(h, l).scan(listOf(romRoot.absolutePath)) { p ->
            if (ScanJobRecord.due(p.processed, p.total, published)) {
                published = p.processed
                write(ScanJobRecord.running(job, p, startedAt))
            }
        }
        write(ScanJobRecord.finished(job, summary, "harness", startedAt))
        return Recorded(records, summary)
    }

    /** Never answers, and says how many times in a row: what the pipeline stops a scan for. */
    private class SilentSource : RaHashLookup {
        private val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata? { calls.incrementAndGet(); return null }
        override val consecutiveFailures: Int get() = calls.get()
    }

    // The library pending-done-mixed came from: five matches, three files of a
    // platform that is not covered, two misses and a dump held under a virtual
    // id. With the pipeline behind it the record ends on the same sentence and
    // the same five counts, and the theme reads the same from it.
    @Test fun `the theme follows a scan recorded through the builder to the sentence android ends on`(): Unit = runBlocking {
        repeat(5) { rom("snes", "game$it.sfc", "hash-game$it") }
        repeat(3) { rom("switch", "title$it.zip", "never read") }
        repeat(2) { rom("snes", "unknown$it.sfc", "hash-unknown$it") }
        rom("snes", "virtual0.sfc", "hash-virtual")
        val hashes = ContentHasher()
        val answers = MapLookup((0 until 5).associate {
            "hash-game$it" to GameMetadata(1000 + it, "Game ${1000 + it}", "SNES/Super Famicom", "/Images/000001.png", 10)
        } + ("hash-virtual" to GameMetadata(gameId = 1100001487)))

        val theme = androidTheme()
        val seen = ArrayList<View>()
        val first = recordedScan("job1", hashes, answers) { theme.readHasherProgress(); seen += theme.view() }

        // Eleven files: a record at the tenth result and one at the last.
        assertEquals(4, first.records.size)
        val (start, tenth, last, done) = first.records
        for (r in first.records) assertEquals(androidRunningKeys + failureCounts, r.keySet())
        assertTrue(tenth.getString("message").startsWith("[10/11] "), tenth.getString("message"))
        assertTrue(last.getString("message").startsWith("[11/11] "), last.getString("message"))

        assertEquals(View("running", 0, 0, 0, "Scanning ROM folders…", 0, 0), seen[0])
        assertEquals(View("running", 91, 10, 11, tenth.getString("message").removePrefix("[10/11] "),
                          tenth.getInt("newEntries"), tenth.getInt("cachedHits")), seen[1])
        assertEquals(View("running", 100, 11, 11, last.getString("message").removePrefix("[11/11] "), 5, 0), seen[2])
        // "done" in the record does not end the scan for the theme. The marker does.
        assertEquals(View("running", 100, 11, 11, androidMixedSentence, 5, 0), seen[3])
        leaveMarker()
        theme.readHasherProgress()
        assertEquals(View("done", 100, 11, 11, androidMixedSentence, 5, 0), theme.view())
        assertEquals("", theme.activeJobId)

        // What the service's own loop left for this library, beside the two
        // counts it did not keep, both at nothing here. Which file came last
        // is up to the workers.
        assertEquals(replayed("pending-first") - stamps, fields(start) - stamps - failureCounts)
        assertEquals(replayed("pending-running-mixed") - stamps - "message",
                     fields(last) - stamps - "message" - failureCounts)
        assertEquals(replayed("pending-done-mixed") - stamps, fields(done) - stamps - failureCounts)
        for (r in first.records) for (k in failureCounts) assertEquals(0, r.getInt(k), k)

        // The second scan reads no file. The matches are cached, and the three
        // answers that were no stand in the ledger and are counted as what they
        // were, where the service's own loop hashed and asked about the two
        // misses again to count them.
        val again = androidTheme("job2")
        val hashed = hashes.calls.get()
        val second = recordedScan("job2", hashes, answers) { again.readHasherProgress() }
        assertEquals(hashed, hashes.calls.get())
        assertEquals(replayed("pending-done-mixed-rescan") - stamps,
                     fields(second.records.last()) - stamps - failureCounts)
        leaveMarker("job2")
        again.readHasherProgress()
        assertEquals(View("done", 100, 11, 11, androidMixedRescanSentence, 0, 5), again.view())
    }

    @Test fun `a library with no rom in it is recorded with the sentence android has for it`(): Unit = runBlocking {
        val theme = androidTheme()
        val records = recordedScan("job1", ContentHasher(), MapLookup(catalogue)) { theme.readHasherProgress() }.records

        assertEquals(2, records.size)
        val last = records.last()
        assertEquals(androidRunningKeys + failureCounts, last.keySet())
        assertEquals(replayed("pending-empty") - stamps, fields(last) - stamps - failureCounts)
        for (k in failureCounts) assertEquals(0, last.getInt(k), k)

        assertEquals(View("running", 100, 0, 0, "No ROMs found", 0, 0), theme.view())
        leaveMarker()
        theme.readHasherProgress()
        assertEquals(View("done", 100, 0, 0, "No ROMs found", 0, 0), theme.view())
    }

    // The pipeline returns from an outage as from any scan, with `aborted` set
    // in its summary. The record must not say done: the theme goes by status
    // alone, and would put up "Scan Complete" over a scan that stopped at its
    // first results. 600 files as in error-outage.json, whose sentence this is
    // but for the count, which is of the results collected when the scan stopped.
    @Test fun `an outage is recorded as the error android writes, and the theme never reads it as done`(): Unit = runBlocking {
        repeat(600) { rom("snes", "g$it.sfc", "hash-$it") }
        val theme = androidTheme()
        val statuses = ArrayList<String>()

        val scan = withTimeout(5_000) {
            recordedScan("job1", ContentHasher(), SilentSource()) { theme.readHasherProgress(); statuses += theme.status }
        }

        assertTrue(scan.summary.aborted)
        assertTrue(scan.summary.processed < 600, "an abort must not have gone through the library")
        val last = scan.records.last()
        assertEquals(androidErrorKeys, last.keySet())
        assertEquals("error", last.getString("status"))
        val sentence = last.getString("error")
        assertTrue(sentence.startsWith("RetroAchievements stopped responding after "), sentence)
        assertEquals(JSONObject(fixture("error-outage.json")).getString("error")
                         .replace("after 1 of 600 files", "after ${scan.summary.processed} of 600 files"),
                     sentence)

        // The service marks the job done after the error, as after anything.
        leaveMarker()
        theme.readHasherProgress()
        statuses += theme.status
        assertFalse("done" in statuses, "read as $statuses")
        assertEquals("error", statuses.last())
        assertEquals(sentence, theme.currentFile)
        assertEquals(0, theme.percent)
        assertEquals("", theme.activeJobId)
    }

    @Test fun `a refused key is recorded as the error android writes, naming whose key it was`(): Unit = runBlocking {
        repeat(30) { rom("snes", "g$it.sfc", "hash-$it") }
        val refusing = object : RaHashLookup {
            @Volatile var refused = false
            override suspend fun lookup(hash: String): GameMetadata? { refused = true; return null }
            override val authRejected: Boolean get() = refused
        }
        val theme = androidTheme()
        val statuses = ArrayList<String>()

        val scan = withTimeout(5_000) {
            recordedScan("job1", ContentHasher(), refusing) { theme.readHasherProgress(); statuses += theme.status }
        }

        assertEquals(RomScanPipeline.AbortCause.KEY_REFUSED, scan.summary.abortCause)
        val last = scan.records.last()
        assertEquals(androidErrorKeys, last.keySet())
        val sentence = last.getString("error")
        assertTrue(sentence.startsWith("RetroAchievements refused the API key for harness after "), sentence)
        assertEquals(JSONObject(fixture("error-key.json")).getString("error")
                         .replace("after 1 of 30 files", "after ${scan.summary.processed} of 30 files"),
                     sentence)

        leaveMarker()
        theme.readHasherProgress()
        statuses += theme.status
        assertFalse("done" in statuses, "read as $statuses")
        assertEquals("error", statuses.last())
        assertEquals(sentence, theme.currentFile)
    }

    // Each number of the summary in its own place in the sentence, which the
    // two scans above cannot show: they stop at once, with nothing identified.
    // And an abort is an error whether or not the summary says which it was.
    @Test fun `a summary that says aborted becomes an error record with the advice for its cause`() {
        val summary = RomScanPipeline.Summary(
            total = 40, processed = 9, newEntries = 2, cachedHits = 3, skippedPlatforms = 0, indexed = 5,
            aborted = true, reason = "the lookup source stopped answering: 4 of 9 failed, 8 in a row",
            failedLookups = 4)
        val wait = "RetroAchievements stopped responding after 9 of 40 files (2 identified). " +
            "Nothing was recorded as missing. " +
            "Wait a few minutes and scan again — it will resume where it left off."
        val copy = "RetroAchievements refused the API key for someone after 9 of 40 files (2 identified). " +
            "Nothing was recorded as missing. " +
            "Copy the Web API key from your RetroAchievements settings into credentials.json " +
            "and scan again."
        val advice = mapOf(
            null to wait,
            RomScanPipeline.AbortCause.SOURCE_DOWN to wait,
            RomScanPipeline.AbortCause.KEY_REFUSED to copy)
        for ((cause, sentence) in advice) {
            val record = ScanJobRecord.finished("job1", summary.copy(abortCause = cause), "someone", 1791233730, 1791233732)
            assertEquals(androidErrorKeys, record.keySet(), "$cause")
            assertEquals("error", record.getString("status"), "$cause")
            // The advice, and not the line the pipeline wrote for the log.
            assertEquals(sentence, record.getString("error"), "$cause")
            assertEquals(1791233730, record.getLong("startedAt"), "$cause")
            assertEquals(1791233732, record.getLong("updatedAt"), "$cause")
        }
    }

    // With no scan behind them, so that every number can be a different one,
    // the two stamps included, and each is looked for in its own place.
    @Test fun `a report and a summary of the pipeline's are recorded number for number`() {
        val report = RomScanPipeline.Progress(
            processed = 30, total = 120, currentFile = "Some Game (USA).sfc",
            newEntries = 9, cachedHits = 8, skippedPlatforms = 6,
            unmatched = 4, incompatible = 2, hashFailed = 1, failedLookups = 0)
        assertEquals(
            fields(JSONObject("""{"schemaVersion":1,"jobId":"job7","verb":"scan","status":"running",
                "progress":0.25,"message":"[30/120] Some Game (USA).sfc",
                "newEntries":9,"cachedHits":8,"skippedPlatforms":6,"unmatched":4,"incompatible":2,
                "hashFailed":1,"failedLookups":0,"startedAt":1791233730,"updatedAt":1791233732}""")),
            fields(JSONObject(ScanJobRecord.running("job7", report, 1791233730, 1791233732).toString())))
        // Nothing out of nothing is no progress, and not a number JSON cannot hold.
        assertEquals(0.0, ScanJobRecord.running("job7", RomScanPipeline.Progress(0, 0, "", 0, 0, 0), 1, 2)
            .getDouble("progress"))

        val summary = RomScanPipeline.Summary(
            total = 120, processed = 120, newEntries = 40, cachedHits = 30, skippedPlatforms = 20, indexed = 70,
            failedLookups = 1, unmatched = 15, incompatible = 9, hashFailed = 5)
        assertEquals(
            fields(JSONObject("""{"schemaVersion":1,"jobId":"job7","verb":"scan","status":"done",
                "progress":1,"message":"Done — 40 new, 30 cached, 20 skipped, 15 not in the database, 9 dumps RetroAchievements does not support, 5 could not be hashed, 1 lookups got no answer",
                "newEntries":40,"cachedHits":30,"skippedPlatforms":20,"unmatched":15,"incompatible":9,
                "hashFailed":5,"failedLookups":1,"startedAt":1791233730,"updatedAt":1791233732}""")),
            fields(JSONObject(ScanJobRecord.finished("job7", summary, "someone", 1791233730, 1791233732).toString())))

        // What the pipeline returns when the walk found nothing.
        val nothing = RomScanPipeline.Summary(0, 0, 0, 0, 0, indexed = 70)
        assertEquals(
            fields(JSONObject("""{"schemaVersion":1,"jobId":"job7","verb":"scan","status":"done",
                "progress":1,"message":"No ROMs found",
                "newEntries":0,"cachedHits":0,"skippedPlatforms":0,"unmatched":0,"incompatible":0,
                "hashFailed":0,"failedLookups":0,"startedAt":1791233730,"updatedAt":1791233732}""")),
            fields(JSONObject(ScanJobRecord.finished("job7", nothing, "someone", 1791233730, 1791233732).toString())))
    }

    // Every count a different number, so that one written under another's name
    // shows wherever it lands: 12 files cached from the scan before, 9 new, 8
    // of a platform that is not covered, 7 misses, 6 held under a virtual id,
    // 5 the hasher gives nothing for and 4 whose lookup gets no answer.
    @Test fun `the seven counts of a record add up to the results it says were collected`(): Unit = runBlocking {
        val hashes = object : RomHasher {
            override fun hash(path: String): HashResult? = File(path).readText().let { text ->
                if (text.startsWith("unreadable")) null
                else HashResult(text, 3, fileMd5 = "md5-$text", fileCrc32 = "crc-$text")
            }
        }
        val answers = object : RaHashLookup {
            override suspend fun lookup(hash: String): GameMetadata? = when (hash.substringBeforeLast('-')) {
                "hash-match" -> (3000 + hash.substringAfterLast('-').toInt()).let { id ->
                    GameMetadata(id, "Game $id", "SNES/Super Famicom", "/Images/000001.png", 10)
                }
                "hash-virtual" -> GameMetadata(gameId = 1100001487)
                "hash-silent" -> null
                else -> GameMetadata(gameId = 0)
            }
        }
        repeat(12) { rom("snes", "kept$it.sfc", "hash-match-$it") }
        assertEquals(12, recordedScan("job1", hashes, answers).summary.newEntries)

        repeat(9) { rom("snes", "fresh$it.sfc", "hash-match-${100 + it}") }
        repeat(8) { rom("switch", "title$it.zip", "never read") }
        repeat(7) { rom("snes", "unknown$it.sfc", "hash-unknown-$it") }
        repeat(6) { rom("snes", "virtual$it.sfc", "hash-virtual-$it") }
        repeat(5) { rom("snes", "broken$it.sfc", "unreadable-$it") }
        repeat(4) { rom("snes", "unanswered$it.sfc", "hash-silent-$it") }
        val records = recordedScan("job2", hashes, answers).records

        // 51 files: a record every ten results, and one for the last.
        val running = records.filter { it.getString("message").startsWith("[") }
        val collected = running.map { it.getString("message").substringAfter('[').substringBefore('/').toInt() }
        assertEquals(listOf(10, 20, 30, 40, 50, 51), collected)
        for ((r, n) in running.zip(collected)) {
            assertEquals(androidRunningKeys + failureCounts, r.keySet())
            assertEquals("running", r.getString("status"))
            assertEquals(n, sevenCounts.sumOf { r.getInt(it) }, r.getString("message"))
        }

        val done = records.last()
        val counts = mapOf("newEntries" to 9, "cachedHits" to 12, "skippedPlatforms" to 8, "unmatched" to 7,
                           "incompatible" to 6, "hashFailed" to 5, "failedLookups" to 4)
        for (r in listOf(running.last(), done)) assertEquals(counts, sevenCounts.associateWith { r.getInt(it) })
        assertEquals("done", done.getString("status"))
        assertEquals(androidRunningKeys + failureCounts, done.keySet())
        assertEquals("Done — 9 new, 12 cached, 8 skipped, 7 not in the database, " +
                     "6 dumps RetroAchievements does not support, 5 could not be hashed, " +
                     "4 lookups got no answer", done.getString("message"))
    }

    // The two numbers are read from the start of the message and the name is
    // whatever follows them, so a name with brackets of its own stays whole.
    @Test fun `a file whose name begins with a bracket is shown under its whole name`(): Unit = runBlocking {
        rom("snes", "[BIOS] Super Game Boy (World).sfc", "hash-alpha")
        val theme = androidTheme()
        val seen = ArrayList<View>()

        val records = recordedScan("job1", ContentHasher(), MapLookup(catalogue)) {
            theme.readHasherProgress(); seen += theme.view()
        }.records

        assertEquals("[1/1] [BIOS] Super Game Boy (World).sfc", records[1].getString("message"))
        assertEquals(View("running", 100, 1, 1, "[BIOS] Super Game Boy (World).sfc", 1, 0), seen[1])
    }

    /** The results the pipeline reports, by its own rule: every fiftieth of the library, and the last. */
    private fun reportedOf(total: Int): List<Int> {
        val step = (total / 50).coerceAtLeast(1)
        return (1..total).filter { it % step == 0 || it == total }
    }

    /** The ones of [reported] that a caller asking [ScanJobRecord.due] writes a record for. */
    private fun writtenOf(total: Int, reported: List<Int> = reportedOf(total)): List<Int> {
        var last = 0
        return reported.filter { n -> ScanJobRecord.due(n, total, last).also { if (it) last = n } }
    }

    // What the next test rests on. Files of a platform that is not covered,
    // which the pipeline reports like any other and neither reads nor asks about.
    @Test fun `the pipeline reports every fiftieth of a library and its last result`(): Unit = runBlocking {
        for (total in listOf(7, 120, 480)) {
            val library = File(romRoot, "library-$total")
            File(library, "switch").mkdirs()
            repeat(total) { File(library, "switch/title$it.zip").writeText("never read") }
            val reported = ArrayList<Int>()

            pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(library.absolutePath)) { reported += it.processed }

            assertEquals(reportedOf(total), reported, "$total files")
        }
    }

    // The service's own loop wrote a record when the count of results was a
    // multiple of max(total / 50, 10), and for the last. Asked about every
    // result, the rule gives those and no others. The pipeline reports on
    // multiples of max(total / 50, 1): up to 99 files that is every result and
    // from 500 it is the service's step, so the records fall where they did.
    // In between the two steps differ, and a record is written at the first
    // report ten results or more after the one before: 10 to 18 apart.
    @Test fun `a record is written where android writes one, or within twice its step`() {
        for (total in 1..2000) {
            val step = (total / 50).coerceAtLeast(10)
            val android = (1..total).filter { it % step == 0 || it == total }
            assertEquals(android, writtenOf(total, (1..total).toList()), "$total files, asked about every result")

            val written = writtenOf(total)
            assertEquals(total, written.last(), "$total files: the last result")

            val gaps = (listOf(0) + written).zipWithNext { a, b -> b - a }
            assertTrue(gaps.max() <= 2 * step, "$total files: ${gaps.max()} results with no record")
            assertTrue(gaps.dropLast(1).all { it >= step }, "$total files: records closer than $step")

            if (total < 100 || total >= 500) assertEquals(android, written, "$total files")
        }

        // 480 files: reported every 9, written every 18, and the last. Keeping
        // the reports that are multiples of 10 would have kept one in ten of
        // them, a record every 90 results.
        assertEquals((18..468 step 18) + 480, writtenOf(480))
    }
}
