package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomScanPipelineTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    /**
     * Hashes a file to its own content, so tests control matching exactly.
     *
     * It also fills the plain hashes, because in production every hasher reaches
     * the pipeline wrapped in [ArchiveAwareHasher], which supplies them. A double
     * that left them empty would look like pre-plain-hash metadata and be
     * rescanned forever.
     */
    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            val f = File(path)
            if (!f.exists()) return null
            val text = f.readText().trim()
            if (text == "UNHASHABLE") return null
            return HashResult(text, 7, fileMd5 = "md5-$text", fileCrc32 = "crc-$text")
        }
    }

    private class MapLookup(private val map: Map<String, GameMetadata>) : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): LookupOutcome {
            calls.incrementAndGet()
            return (map[hash] ?: GameMetadata(gameId = 0)).asOutcome()
        }
    }

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("hasher-data").toFile()
        romRoot  = Files.createTempDirectory("hasher-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun rom(platform: String, name: String, content: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private val catalogue = mapOf(
        "hash-smb"  to GameMetadata(1446, "Super Mario Bros.", "NES", "/Images/1.png", 76),
        "hash-ctra" to GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40)
    )

    private fun pipeline(h: RomHasher, l: RaHashLookup) =
        RomScanPipeline(paths, h, l, throttleMs = { 0L })

    private fun zip(platform: String, name: String, vararg entries: Pair<String, String>): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).also { zip ->
            java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                for ((entry, content) in entries) {
                    z.putNextEntry(java.util.zip.ZipEntry(entry)); z.write(content.toByteArray()); z.closeEntry()
                }
            }
        }
    }

    /**
     * Every way a source can answer, chosen by the hash: a game, a virtual id, a
     * real id with no title, nothing at all, and for anything else a miss.
     */
    private class MixedLookup : RaHashLookup {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override suspend fun lookup(hash: String): LookupOutcome {
            asked += hash
            return when {
                hash.startsWith("hash-match-") -> {
                    val n = hash.substringAfterLast('-').toInt()
                    GameMetadata(2000 + n, "Game $n", "NES", "/Images/$n.png", 10)
                }
                hash == "hash-virtual"  -> GameMetadata(gameId = 1100001487)
                hash == "hash-untitled" -> GameMetadata(gameId = 1487)
                hash == "hash-silent"   -> null
                else                    -> GameMetadata(gameId = 0)
            }.asOutcome()
        }
    }

    // By name, so a count that fails says which one it was.
    private fun counts(new: Int, cached: Int, skipped: Int, unmatched: Int,
                       incompatible: Int, hashFailed: Int, failedLookups: Int) = mapOf(
        "newEntries" to new, "cachedHits" to cached, "skippedPlatforms" to skipped,
        "unmatched" to unmatched, "incompatible" to incompatible,
        "hashFailed" to hashFailed, "failedLookups" to failedLookups)

    private fun RomScanPipeline.Summary.counts() = counts(
        newEntries, cachedHits, skippedPlatforms, unmatched, incompatible, hashFailed, failedLookups)

    private fun RomScanPipeline.Progress.counts() = counts(
        newEntries, cachedHits, skippedPlatforms, unmatched, incompatible, hashFailed, failedLookups)

    @Test fun `matched roms produce metadata and a discovery index`(): Unit = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        rom("nes", "Contra (USA).nes", "hash-ctra")

        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(2, s.total)
        assertEquals(2, s.newEntries)
        assertEquals(2, s.indexed)

        val meta = JSONObject(paths.metadata("1446").readText())
        assertEquals("Super Mario Bros.", meta.getString("title"))
        assertEquals("nes", meta.getString("platform"))
        assertEquals(76, meta.getJSONObject("ra").getInt("total"))
        assertEquals("hash-smb", meta.getJSONObject("rom").getString("hash"))

        val index = JSONObject(paths.discoveryIndex.readText())
        assertEquals(2, index.getInt("count"))
        assertTrue(index.getJSONObject("byKey").has("supermariobros|nes"),
                   "reverse lookup key missing: ${index.getJSONObject("byKey").keys().asSequence().toList()}")
    }

    // RA's dorequest answers a dump it does not consider playable as is with a
    // virtual id: a Virtual Console Metroid returns 1100001487, game 1487
    // untested, and the Web API has no game under that number. Not a match, so
    // nothing is written and the count does not include it — but an answer, kept
    // like a miss. Recorded as API_RETRY, which is never cached, the file was
    // hashed and asked about again on every scan.
    @Test fun `a virtual id is kept like a miss, not written and not asked about again`(): Unit = runBlocking {
        val rom = rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-phantom")
        val phantom = object : RaHashLookup {
            val calls = AtomicInteger()
            override suspend fun lookup(hash: String): LookupOutcome {
                calls.incrementAndGet()
                return GameMetadata(gameId = 1100001487).asOutcome()
            }
        }

        val s = pipeline(ContentHasher(), phantom).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.total)
        assertEquals(0, s.newEntries, "a titleless id must not count as a new entry")
        assertEquals(0, s.indexed)
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size,
                     "no junk metadata file should be left on disk")
        assertEquals(mapOf(ScanLedger.State.NOT_FOUND to 1), s.states)
        assertEquals(0, s.failedLookups, "the source answered")
        assertEquals(1, s.incompatible)
        assertEquals(0, s.unmatched, "a dump RetroAchievements holds is not one it has never heard of")
        val entry = JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText())
            .getJSONObject("entries").getJSONObject(rom.canonicalPath)
        assertEquals(1100001487, entry.getInt("gameId"))
        assertEquals("RetroAchievements knows this dump only by virtual id 1100001487: game 1487, untested",
                     entry.getString("detail"))

        val h2 = ContentHasher()
        val s2 = pipeline(h2, phantom).scan(listOf(romRoot.absolutePath))
        assertEquals(0, h2.calls.get(), "the file was read again inside the verdict's TTL")
        assertEquals(1, phantom.calls.get(), "the source was asked again inside the verdict's TTL")
        assertEquals(mapOf(ScanLedger.State.NOT_FOUND to 1), s2.states)
        assertEquals(1, s2.incompatible, "the verdict found standing was counted as something else")
        assertEquals(0, s2.unmatched)
    }

    // A real id with a title of only spaces, which RaApiHashLookup answers as a
    // failure and no lookup can answer as a match: one will not be built from
    // it. Written, it would be distrusted by the next scan's cache, asked about
    // again and counted new on every run.
    @Test fun `a title of only spaces is not treated as a match either`(): Unit = runBlocking {
        rom("nes", "Metroid (Europe).nes", "hash-spaces")
        val spaces = object : RaHashLookup {
            override suspend fun lookup(hash: String) = GameMetadata(gameId = 1487, title = "   ").asOutcome()
        }

        val s = pipeline(ContentHasher(), spaces).scan(listOf(romRoot.absolutePath))

        assertEquals(0, s.newEntries)
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size)
        assertEquals(1, s.states[ScanLedger.State.API_RETRY], "it must be asked about again, not written off")
        assertEquals(1, s.failedLookups, "an id with no title is a lookup that brought nothing usable back")
    }

    @Test fun `unmatched roms are counted but write no metadata`(): Unit = runBlocking {
        rom("nes", "Homebrew Thing.nes", "hash-unknown")
        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s.total)
        assertEquals(0, s.newEntries)
        assertEquals(1, s.unmatched)
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size)
    }

    // A second scan of an unchanged library must not hash or hit the network again.
    @Test fun `unchanged files are skipped on a rescan`(): Unit = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")

        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        val h2 = ContentHasher(); val l2 = MapLookup(catalogue)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s2.cachedHits)
        assertEquals(0, s2.newEntries)
        assertEquals(0, h2.calls.get(), "unchanged file must not be re-hashed")
        assertEquals(0, l2.calls.get(), "unchanged file must not hit the API")
        assertEquals(1, s2.indexed, "the index must still list it")
    }

    // Metadata written before the plain hashes existed carries no fileMd5. The
    // incremental skip must not preserve that gap forever, or a library already
    // scanned once would never gain the field a scraper needs.
    @Test fun `metadata without a plain hash is rescanned once`(): Unit = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        // Strip the field, imitating a file from the previous schema.
        val meta = paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.first()
        val j = JSONObject(meta.readText())
        j.getJSONObject("rom").remove("fileMd5")
        meta.writeText(j.toString(2))

        val h2 = ContentHasher()
        val s2 = pipeline(h2, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(0, s2.cachedHits, "stale-schema metadata must not count as a cache hit")
        assertEquals(1, h2.calls.get(), "the file must be hashed again to backfill")
        val after = JSONObject(meta.readText()).getJSONObject("rom")
        assertEquals("md5-hash-smb", after.getString("fileMd5"))
        assertEquals("crc-hash-smb", after.getString("fileCrc32"))
    }

    // Metadata files written before the collector refused a blank title are still
    // on disk: 27 of 732 on the tablet. The index drops them for having no title,
    // and trusted as a cache they kept their ROM away from the hasher and the
    // network for as long as it stayed unchanged — out of the index for good.
    @Test fun `metadata with a blank title is looked up again`(): Unit = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        rom("nes", "Contra (USA).nes", "hash-ctra")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        // Blank both titles, imitating files written before the guard: one empty,
        // as RetroAchievements' `[]` used to leave it, and one only whitespace.
        for ((id, blank) in listOf("1446" to "", "1447" to "   ")) {
            val f = paths.metadata(id)
            f.writeText(JSONObject(f.readText()).put("title", blank).toString(2))
        }

        // RetroAchievements still cannot describe them: asked again, listed
        // nowhere, and the files left where they are.
        val untitled = object : RaHashLookup {
            val calls = AtomicInteger()
            override suspend fun lookup(hash: String): LookupOutcome {
                calls.incrementAndGet()
                return GameMetadata(gameId = catalogue.getValue(hash).gameId).asOutcome()
            }
        }
        val h2 = ContentHasher()
        val s2 = pipeline(h2, untitled).scan(listOf(romRoot.absolutePath))

        assertEquals(0, s2.cachedHits, "a file with no title must not count as a cache hit")
        assertEquals(2, h2.calls.get(), "both ROMs must be hashed again")
        assertEquals(2, untitled.calls.get(), "both hashes must be asked about again")
        assertEquals(0, s2.newEntries)
        assertEquals(0, s2.indexed, "a game with no title, empty or spaces, must not be listed")
        assertTrue(paths.metadata("1446").isFile && paths.metadata("1447").isFile,
                   "the files are left for a real match to overwrite")

        // Once it can, the answer replaces them and reaches the index.
        val l3 = MapLookup(catalogue)
        val s3 = pipeline(ContentHasher(), l3).scan(listOf(romRoot.absolutePath))

        assertEquals(2, l3.calls.get())
        assertEquals(2, s3.newEntries)
        assertEquals(2, s3.indexed)
        assertEquals("Super Mario Bros.", JSONObject(paths.metadata("1446").readText()).getString("title"))
        assertEquals("Contra", JSONObject(paths.metadata("1447").readText()).getString("title"))
    }

    @Test fun `an edited file is rescanned`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        f.writeText("hash-ctra")
        f.setLastModified(f.lastModified() + 10_000)

        val h2 = ContentHasher()
        val s2 = pipeline(h2, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(0, s2.cachedHits)
        assertEquals(1, h2.calls.get(), "changed file must be re-hashed")
    }

    @Test fun `platforms retroachievements does not cover are skipped before hashing`(): Unit = runBlocking {
        rom("switch", "Something.nes", "hash-smb")
        rom("psvita", "Other.nes", "hash-ctra")
        rom("nes",    "Real.nes", "hash-smb")

        val h = ContentHasher()
        val s = pipeline(h, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(3, s.total)
        assertEquals(2, s.skippedPlatforms)
        assertEquals(1, h.calls.get(), "only the supported platform should be hashed")
    }

    // Several files sharing a hash should cost one network call, not one each.
    @Test fun `identical hashes are looked up once`(): Unit = runBlocking {
        rom("nes", "Copy A.nes", "hash-smb")
        rom("nes", "Copy B.nes", "hash-smb")
        rom("nes", "Copy C.nes", "hash-smb")

        val l = MapLookup(catalogue)
        pipeline(ContentHasher(), l).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l.calls.get(), "duplicate hashes must be de-duplicated")
    }

    // Only an answer is kept for the copies that come after. A lookup that
    // failed is not one: kept, the request that went wrong once would be the
    // last made for that hash in the scan, and every copy would end as a
    // retry. One lookup at a time here, so that the second copy arrives when
    // the first is over and not while it is still being asked about.
    @Test fun `a hash whose lookup failed is asked about again for the next file that has it`(): Unit = runBlocking {
        rom("nes", "Copy A.nes", "hash-smb")
        rom("nes", "Copy B.nes", "hash-smb")
        val failsOnce = object : RaHashLookup {
            val calls = AtomicInteger()
            override suspend fun lookup(hash: String): LookupOutcome =
                if (calls.incrementAndGet() == 1) LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, "timeout")
                else LookupOutcome.Match(catalogue.getValue(hash))
        }

        val s = RomScanPipeline(paths, ContentHasher(), failsOnce, throttleMs = { 0L }, hashWorkers = 1, apiWorkers = 1)
            .scan(listOf(romRoot.absolutePath))

        assertEquals(2, failsOnce.calls.get(), "the copy that came after a failure was given the failure")
        assertEquals(counts(new = 1, cached = 0, skipped = 0, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 1), s.counts())
        assertEquals(mapOf(ScanLedger.State.API_RETRY to 1, ScanLedger.State.MATCHED to 1), s.states)
    }

    @Test fun `a file the hasher cannot read does not abort the scan`(): Unit = runBlocking {
        rom("nes", "Broken.nes", "UNHASHABLE")
        rom("nes", "Good.nes", "hash-smb")

        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(2, s.total)
        assertEquals(1, s.newEntries, "the readable ROM must still be processed")
        assertEquals(1, s.hashFailed)
    }

    /**
     * One of everything a scan can meet, in numbers no two of which are alike, so
     * that a file counted under the wrong name shows: four matches, six misses,
     * one virtual id, three files that give no hash (one unreadable, a zip with
     * two ROMs in it, a zip whose ROM is a disc descriptor), five under a
     * platform RetroAchievements does not cover, and two lookups that brought
     * nothing usable back (one unanswered, one a real id with no title).
     *
     * Scanned twice. The first scan asks about every hash. The second finds the
     * matches in their metadata and the verdicts in the ledger, and has to put
     * each file in the count the first one did without asking about it. Only
     * what is never kept as a verdict is done again: the unreadable file is read
     * and the two lookups are made.
     *
     * While the counts were four, the first scan here gave 4 new, 5 skipped and
     * 1 failed lookup: ten of its 21 files.
     */
    @Test fun `every file is in exactly one of seven counts, on a first scan and on a rescan`(): Unit = runBlocking {
        repeat(4) { rom("nes", "Match $it.nes", "hash-match-$it") }
        repeat(6) { rom("nes", "Miss $it.nes", "hash-miss-$it") }
        rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-virtual")
        rom("nes", "Broken.nes", "UNHASHABLE")
        zip("nes", "Two Games.zip", "first.nes" to "hash-first", "second.nes" to "hash-second")
        zip("psx", "Disc.zip", "Disc.cue" to "FILE \"Disc.bin\" BINARY", "Disc.bin" to "x".repeat(4096))
        repeat(5) { rom("switch", "Game $it.nes", "hash-switch-$it") }
        rom("nes", "Silent.nes", "hash-silent")
        rom("nes", "Untitled.nes", "hash-untitled")
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()

        suspend fun scan(h: RomHasher, l: RaHashLookup): Pair<RomScanPipeline.Summary, List<RomScanPipeline.Progress>> {
            val seen = mutableListOf<RomScanPipeline.Progress>()
            val s = RomScanPipeline(paths, ArchiveAwareHasher(h, tmp), l, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath)) { seen += it }
            return s to seen
        }
        val states = mapOf(
            ScanLedger.State.MATCHED to 4, ScanLedger.State.NOT_FOUND to 7,
            ScanLedger.State.HASH_FAILED to 1, ScanLedger.State.AMBIGUOUS_ARCHIVE to 1,
            ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.UNSUPPORTED to 5,
            ScanLedger.State.API_RETRY to 2)

        val l1 = MixedLookup()
        val (s1, seen1) = scan(ContentHasher(), l1)

        assertEquals(21, s1.total)
        assertEquals(21, s1.processed)
        assertEquals(counts(new = 4, cached = 0, skipped = 5, unmatched = 6, incompatible = 1,
                            hashFailed = 3, failedLookups = 2), s1.counts())
        assertEquals(states, s1.states)
        assertEquals(13, l1.asked.size, "asked: ${l1.asked}")

        val h2 = ContentHasher(); val l2 = MixedLookup()
        val (s2, seen2) = scan(h2, l2)

        assertEquals(21, s2.processed)
        assertEquals(counts(new = 0, cached = 4, skipped = 5, unmatched = 6, incompatible = 1,
                            hashFailed = 3, failedLookups = 2), s2.counts())
        assertEquals(states, s2.states)
        assertEquals(listOf("hash-silent", "hash-untitled"), l2.asked.sorted(),
                     "a file whose verdict was standing was asked about again")
        assertEquals(3, h2.calls.get(), "only the unreadable file and the two retries are read again")

        // With 21 files there is a report for every one of them.
        for ((s, seen) in listOf(s1 to seen1, s2 to seen2)) {
            assertEquals((1..21).toList(), seen.map { it.processed })
            for (p in seen) assertEquals(p.processed, p.counts().values.sum(), "at ${p.processed}: ${p.counts()}")
            assertEquals(s.counts(), seen.last().counts(), "the last report and the summary disagree")
        }
        tmp.deleteRecursively()
    }

    // The platforms RetroAchievements does not cover are turned away before the
    // ledger is asked, so a verdict of UNSUPPORTED is found standing only for a
    // platform that has left that list since it was written. Until the verdict
    // runs out the file is still skipped for that reason, and is counted as such.
    @Test fun `a standing verdict that the platform is not covered counts as skipped`(): Unit = runBlocking {
        val f = rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        ScanLedger(File(paths.cache, ScanLedger.FILE_NAME)).apply {
            record(f.canonicalPath, ScanLedger.State.UNSUPPORTED, f.length(), f.lastModified(),
                   BridgePaths.epochSeconds())
            save { file, text -> BridgePaths.writeAtomic(file, text) }
        }

        val h = ContentHasher(); val l = MapLookup(catalogue)
        val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 1, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(1, s.processed)
        assertEquals(0, h.calls.get(), "the file was read")
        assertEquals(0, l.calls.get(), "the source was asked")
    }

    @Test fun `progress is reported and reaches the total`(): Unit = runBlocking {
        repeat(5) { rom("nes", "Game$it.nes", "hash-smb") }
        val seen = mutableListOf<RomScanPipeline.Progress>()
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath)) { seen += it }

        assertEquals(5, s.total)
        assertTrue(seen.isNotEmpty(), "no progress reported")
        assertEquals(5, seen.last().processed)
        assertEquals(1.0, seen.last().fraction)
    }

    // What a shell has to tell between the walk and the first result: how many
    // files there are. Once, with the count, and before anything is reported
    // as processed. The reports are the caller's last argument as they were,
    // so a scan given only those is told no count and loses nothing.
    @Test fun `the count of files is told once, before the first result`(): Unit = runBlocking {
        repeat(5) { rom("nes", "Game$it.nes", "hash-smb") }
        val told = mutableListOf<String>()
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath), onCounted = { told += "counted $it" }) { told += "result ${it.processed}" }

        assertEquals(5, s.total)
        assertEquals(listOf("counted 5") + (1..5).map { "result $it" }, told)
    }

    // No count of nothing: a library with no ROM in it goes from the walk to
    // its end, and a shell that wrote "Checking 0 ROM files…" on the way would
    // be saying it had work to do.
    @Test fun `an empty library is not counted out`(): Unit = runBlocking {
        val told = mutableListOf<Int>()
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath), onCounted = { told += it })

        assertEquals(0, s.total)
        assertEquals(emptyList<Int>(), told)
    }

    // The count is a record a shell writes, and a write can fail. It ends the
    // scan as a failure anywhere else in it does: what was thrown reaches the
    // caller, and the index is rebuilt on the way out, as it is whenever the
    // roots have been walked.
    @Test fun `a caller that cannot take the count ends the scan as any failure does`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-smb")
        val h = ContentHasher()
        paths.discoveryIndex.delete()

        val thrown = assertFailsWith<java.io.IOException> {
            pipeline(h, MapLookup(catalogue))
                .scan(listOf(romRoot.absolutePath), onCounted = { throw java.io.IOException("read-only file system") })
        }

        assertEquals("read-only file system", thrown.message)
        assertEquals(0, h.calls.get(), "a file was read after the scan had failed")
        assertTrue(paths.discoveryIndex.isFile, "the index was not rebuilt")
    }

    @Test fun `a missing root is ignored rather than failing`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-smb")
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath, "/does/not/exist"))
        assertEquals(1, s.total)
    }

    @Test fun `an empty library still writes a valid index`(): Unit = runBlocking {
        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(0, s.total)
        assertTrue(paths.discoveryIndex.isFile)
        assertEquals(0, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    @Test fun `the throttle hook is honoured`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-smb")
        var asked = 0
        RomScanPipeline(paths, ContentHasher(), MapLookup(catalogue), throttleMs = { asked++; 0L })
            .scan(listOf(romRoot.absolutePath))
        assertTrue(asked > 0, "throttle hook was never consulted")
    }

    /**
     * The only test that takes an archive through the whole pipeline, and it had
     * never run. Written as `= runBlocking { … }`, its last expression was a
     * Boolean, so the method returned one — and JUnit skips a @Test that returns
     * a value without a word. Hence the explicit `: Unit` on every test here.
     *
     * It was also named after the rule [ArchiveSelector] replaced, and its zip
     * could not tell the two rules apart: the ROM was the largest entry anyway.
     * Now the readme outweighs the ROM, so only picking the entry the platform
     * runs produces the match.
     */
    @Test fun `a zip is hashed via the entry its platform runs, not the largest one`(): Unit = runBlocking {
        val dir = File(romRoot, "nes").apply { mkdirs() }
        val zip = File(dir, "Packed.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write("x".repeat(4096).toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("game.nes"));   z.write("hash-smb".toByteArray()); z.closeEntry()
        }
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        val s = RomScanPipeline(paths, ArchiveAwareHasher(ContentHasher(), tmp),
                                MapLookup(catalogue), throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.newEntries, "the ROM inside the zip should have matched")
        // The plain digests describe the entry that was hashed, not the container:
        // a database matching by file has never heard of the zip's own MD5.
        val rom = JSONObject(paths.metadata("1446").readText()).getJSONObject("rom")
        assertEquals("hash-smb", rom.getString("hash"))
        assertEquals(md5("hash-smb"), rom.getString("fileMd5"))
        assertFalse(tmp.listFiles()?.any { it.name.startsWith("bridge_") } ?: false,
                    "temp extraction files must be cleaned up")
        tmp.deleteRecursively()
    }

    // Zero workers used to be accepted, and then hung on the first library larger
    // than a queue's buffer, because nothing ever read that queue.
    @Test fun `a pipeline with no workers is refused`() {
        assertFailsWith<IllegalArgumentException> {
            RomScanPipeline(paths, ContentHasher(), MapLookup(catalogue), hashWorkers = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RomScanPipeline(paths, ContentHasher(), MapLookup(catalogue), apiWorkers = 0)
        }
    }

    // The count a shell prints or puts in its log. A scan starts one producer
    // for each hash worker it was given, and no more than the machine has
    // cores; the daemon and the Android service both said the count given,
    // which on a machine with fewer cores is not the count a scan ran with.
    @Test fun `the hash producers of a scan are the workers it was given, or the cores where those are fewer`() {
        val cores = Runtime.getRuntime().availableProcessors()
        assertEquals(1, RomScanPipeline.hashProducers(1))
        assertEquals(cores, RomScanPipeline.hashProducers(cores))
        assertEquals(cores, RomScanPipeline.hashProducers(cores + 1))
        assertEquals(cores, RomScanPipeline.hashProducers(1000))
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
