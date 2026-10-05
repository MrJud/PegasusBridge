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
import com.pegasus.bridge.hasher.ScanLedger
import com.pegasus.bridge.ra.RaMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
 * In three parts.
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
}
