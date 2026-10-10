package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
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
    // nothing is written and the count does not include it. But an answer, and
    // kept as one: recorded as API_RETRY, which is never cached, the file was
    // hashed and asked about again on every scan. And kept as what it is, under
    // the game's own id: as a NOT_FOUND under the number as sent, the ledger
    // said the same of it as of a dump nobody has heard of.
    //
    // Through the lookup a scan really has, against a server that answers as
    // RetroAchievements does, for the two things only that lookup can show: a
    // virtual id is not asked about a second time, where the game's metadata
    // is, and it leaves the count of failures where it was. One failure is
    // made before the scan, so that a count put back to nothing would show.
    @Test fun `a virtual id is kept as KNOWN_UNSUPPORTED under the real game id`(): Unit = runBlocking {
        val rom = rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-phantom")
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            val body = if (requests.size == 1) "<html>not an answer</html>"
                       else """{"Success":true,"GameID":1100001487}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val lookup = RaApiHashLookup("someuser", "a-key", "http://127.0.0.1:${server.address.port}")
            assertTrue(lookup.lookup("hash-of-another-file") is LookupOutcome.Failed)
            assertEquals(1, lookup.consecutiveFailures)

            val s = pipeline(ContentHasher(), lookup).scan(listOf(romRoot.absolutePath))

            assertEquals(1, s.total)
            assertEquals(0, s.newEntries, "a dump that is not supported must not count as a new entry")
            assertEquals(0, s.indexed)
            assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size,
                         "no metadata file should be left on disk for it")
            assertEquals(mapOf(ScanLedger.State.KNOWN_UNSUPPORTED to 1), s.states)
            assertEquals(counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 1,
                                hashFailed = 0, failedLookups = 0), s.counts())
            val entry = JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText())
                .getJSONObject("entries").getJSONObject(rom.canonicalPath)
            assertEquals("KNOWN_UNSUPPORTED", entry.getString("state"))
            assertEquals(1487, entry.getInt("gameId"), "the game's own id, not the number as it was sent")
            assertEquals("untested", entry.getString("detail"))
            assertEquals(listOf("/dorequest.php", "/dorequest.php"), requests.toList(),
                         "a virtual id must not be asked about as a game is")
            assertEquals(1, lookup.consecutiveFailures, "an answer that is not a match moved the count of failures")

            val h2 = ContentHasher()
            val s2 = pipeline(h2, lookup).scan(listOf(romRoot.absolutePath))
            assertEquals(0, h2.calls.get(), "the file was read again inside the verdict's TTL")
            assertEquals(2, requests.size, "the source was asked again inside the verdict's TTL")
            assertEquals(mapOf(ScanLedger.State.KNOWN_UNSUPPORTED to 1), s2.states)
            assertEquals(counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 1,
                                hashFailed = 0, failedLookups = 0), s2.counts(),
                         "the verdict found standing was counted as something else")
        } finally {
            server.stop(0)
        }
    }

    // Each of the three reasons under its own words, which are all the ledger
    // says of why.
    @Test fun `the reason a dump is not supported is kept in the source's three words`(): Unit = runBlocking {
        val files = mapOf(1_000_000_009 to "incompatible", 1_100_000_009 to "untested", 1_200_000_009 to "patch required")
            .mapKeys { (virtual, _) -> rom("nes", "Dump $virtual.nes", "$virtual") }
        val answers = object : RaHashLookup {
            override suspend fun lookup(hash: String) = GameMetadata(gameId = hash.toInt()).asOutcome()
        }

        val s = pipeline(ContentHasher(), answers).scan(listOf(romRoot.absolutePath))

        assertEquals(3, s.incompatible)
        val entries = JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText()).getJSONObject("entries")
        for ((file, words) in files) {
            val entry = entries.getJSONObject(file.canonicalPath)
            assertEquals("KNOWN_UNSUPPORTED", entry.getString("state"), file.name)
            assertEquals(9, entry.getInt("gameId"), file.name)
            assertEquals(words, entry.getString("detail"), file.name)
        }
    }

    // What a ledger written before KNOWN_UNSUPPORTED was a state holds for such
    // a dump: a NOT_FOUND under the number as it was sent. It stands for its
    // fourteen days like any miss, and is not a miss: counted as one, a rescan
    // would move a file from one count to another with nothing changed.
    @Test fun `an entry in the old form still counts as incompatible`(): Unit = runBlocking {
        val f = rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-phantom")
        val ledgerFile = File(paths.cache, ScanLedger.FILE_NAME)
        ScanLedger(ledgerFile).apply {
            record(f.canonicalPath, ScanLedger.State.NOT_FOUND, f.length(), f.lastModified(),
                   BridgePaths.epochSeconds(), gameId = 1100001487,
                   detail = "RetroAchievements knows this dump only by virtual id 1100001487: game 1487, untested")
            save { file, text -> BridgePaths.writeAtomic(file, text) }
        }

        val h = ContentHasher(); val l = MapLookup(catalogue)
        val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 1,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(0, h.calls.get(), "the file was read")
        assertEquals(0, l.calls.get(), "the source was asked")
        // Counted, and left as it was written.
        assertEquals(mapOf(ScanLedger.State.NOT_FOUND to 1), s.states)
        val entry = JSONObject(ledgerFile.readText()).getJSONObject("entries").getJSONObject(f.canonicalPath)
        assertEquals("NOT_FOUND", entry.getString("state"))
        assertEquals(1100001487, entry.getInt("gameId"))
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

    // ── A file's collection ─────────────────────────────────────────────────
    //
    // The libraries below are laid out as Pegasus wants them: a metafile in
    // the collection's folder, which says what the collection is called, and
    // the games in it or in folders of their own under it.

    /** A collection declared in [folder] as Pegasus reads one. */
    private fun collection(folder: String, name: String, shortName: String, extensions: String? = null) {
        File(romRoot, folder).apply { mkdirs() }.resolve("metadata.pegasus.txt").writeText(
            "collection: $name\nshortname: $shortName\n" + (extensions?.let { "extensions: $it\n" } ?: ""))
    }

    /** Fails the scan, and so the test, when a file that needs no lookup is asked about. */
    private class NeverAsked : RaHashLookup {
        override suspend fun lookup(hash: String): LookupOutcome =
            throw AssertionError("the source was asked about $hash")
    }

    /**
     * [ContentHasher], and every collection a scan handed it, by the file's
     * name. It hands the collection on as a hasher that knows nothing of
     * collections is handed it, and keeps the one name that came to.
     */
    private class CollectionHasher : RomHasher {
        val inner = ContentHasher()
        val handed = java.util.concurrent.ConcurrentHashMap<String, CollectionRef>()
        val told = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun hash(path: String): HashResult? = inner.hash(path)
        override fun hash(path: String, platform: String): HashResult? {
            told[File(path).name] = platform
            return inner.hash(path)
        }
        override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome {
            handed[File(path).name] = collection
            return super.hashDetailed(path, collection)
        }
    }

    private val nested = mapOf(
        "hash-lantern" to GameMetadata(3001, "Lantern Keep", "PlayStation", "/Images/3.png", 30))

    private fun ledgerEntry(f: File): JSONObject =
        JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText()).getJSONObject("entries")
            .getJSONObject(f.canonicalPath)

    // A disc game is kept in a folder of its own, and the folder was taken for
    // its platform: the metadata said platform `lanternkeepusa`, under a key
    // that ends the same way, and no theme asks for a game by that.
    @Test fun `a file in a game folder takes its collection's platform`(): Unit = runBlocking {
        collection("psx", "PlayStation", "psx")
        rom("psx/Lantern Keep (USA)", "Lantern Keep (USA).bin", "hash-lantern")

        val h = CollectionHasher()
        val s = pipeline(h, MapLookup(nested)).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.newEntries)
        val meta = JSONObject(paths.metadata("3001").readText())
        assertEquals("psx", meta.getString("platform"))
        assertEquals("lanternkeep|psx", meta.getString("cacheKey"))
        assertEquals(setOf("lanternkeep|psx"),
                     JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey").keySet())
        // The hasher is told the collection, and where it is kept.
        val handed = h.handed.getValue("Lantern Keep (USA).bin")
        assertEquals(listOf("psx", "PlayStation", "psx", CollectionRef.Source.DECLARED),
                     listOf(handed.shortName, handed.name, handed.dirName, handed.source))
        assertEquals(File(romRoot, "psx"), handed.directory)
    }

    // The files under `switch/Switch Files` were platform `Switch Files`,
    // which is on no list of platforms to turn away: each was read to its end
    // and asked about.
    @Test fun `a nested folder of an unsupported collection is skipped`(): Unit = runBlocking {
        collection("switch", "Nintendo Switch", "switch")
        val f = rom("switch/Switch Files", "x.zip", "never read")

        val h = ContentHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 1, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(0, h.calls.get(), "the file was read")
        assertEquals("UNSUPPORTED", ledgerEntry(f).getString("state"))
    }

    // The theme hands over the folder of every game it knows as a root of its
    // own. The collection is found above the root all the same, so a scan
    // started there says of a file what a scan of the whole library says.
    @Test fun `the game folder alone as root gives the same answers`(): Unit = runBlocking {
        collection("psx", "PlayStation", "psx")
        collection("switch", "Nintendo Switch", "switch")
        rom("psx/Lantern Keep (USA)", "Lantern Keep (USA).bin", "hash-lantern")
        val turnedAway = rom("switch/Switch Files", "x.zip", "never read")

        val h = CollectionHasher()
        val s = pipeline(h, MapLookup(nested)).scan(listOf(
            File(romRoot, "psx/Lantern Keep (USA)").absolutePath,
            File(romRoot, "switch/Switch Files").absolutePath))

        assertEquals(counts(new = 1, cached = 0, skipped = 1, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals("psx", JSONObject(paths.metadata("3001").readText()).getString("platform"))
        assertEquals("lanternkeep|psx", JSONObject(paths.metadata("3001").readText()).getString("cacheKey"))
        assertEquals("UNSUPPORTED", ledgerEntry(turnedAway).getString("state"))
        assertEquals(setOf("Lantern Keep (USA).bin"), h.handed.keys)
        assertEquals(1, h.inner.calls.get())
    }

    // What the collection calls itself is the platform, whatever the folder
    // is called: it is the name the theme asks by, and the folder's is not.
    // The hasher is told both.
    @Test fun `a declared shortname names the platform`(): Unit = runBlocking {
        collection("gamegear", "Sega 8-bit", "mastersystem")
        rom("gamegear", "Lantern Keep (USA).sms", "hash-lantern")

        val h = CollectionHasher()
        pipeline(h, MapLookup(nested)).scan(listOf(romRoot.absolutePath))

        val meta = JSONObject(paths.metadata("3001").readText())
        assertEquals("lanternkeep|mastersystem", meta.getString("cacheKey"))
        assertEquals("mastersystem", meta.getString("platform"))
        val handed = h.handed.getValue("Lantern Keep (USA).sms")
        assertEquals("mastersystem" to "gamegear", handed.shortName to handed.dirName)
    }

    // A hasher that knows nothing of collections is told one name, and what
    // it does with it is choose the entry of an archive that is the game.
    // That name is the short name, but not for a folder named for one console
    // of the family the short name stands for: `gamegear` in a collection
    // that calls itself `mastersystem`, `sega32x` in one that calls itself
    // `megadrive`, as an ES-DE library has them. Told the short name there,
    // the hasher looked in a zipped Game Gear cartridge for a Master System
    // one, found none and hashed the zip, and a game that had matched while
    // the folder's name was all a scan went by was written down as a miss.
    @Test fun `an archive is opened for what its folder holds where the short name is of a wider family`(): Unit = runBlocking {
        val games = nested + ("hash-other" to GameMetadata(3002, "Other Game", "32X", "/Images/4.png", 10))
        zip("gamegear", "Lantern Keep (USA).zip", "Lantern Keep (USA).gg" to "hash-lantern")
        zip("sega32x", "Other Game (USA).zip", "Other Game (USA).32x" to "hash-other")
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        fun scanned() = runBlocking {
            RomScanPipeline(paths, ArchiveAwareHasher(ContentHasher(), tmp), MapLookup(games), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
        }
        try {
            // With no metafile each folder is the platform, as it always was.
            assertEquals(2, scanned().newEntries)

            collection("gamegear", "Sega 8-bit", "mastersystem")
            collection("sega32x", "Sega 16-bit", "megadrive")
            val s = scanned()

            assertEquals(mapOf(ScanLedger.State.MATCHED to 2), s.states)
            assertEquals(setOf("lanternkeep|mastersystem", "othergame|genesis"),
                         JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey").keySet())
        } finally {
            tmp.deleteRecursively()
        }
    }

    // And it is the short name wherever the folder says nothing more: a
    // folder called anything at all, and one named for a console that is not
    // of the short name's family, which the short name is taken to know
    // better than.
    @Test fun `a hasher that is told one name is told the short name unless the folder says more`(): Unit = runBlocking {
        collection("Sony Console", "PlayStation", "psx")
        collection("neogeo", "Neo Geo Pocket Color", "ngpc")
        collection("gamegear", "Sega 8-bit", "mastersystem")
        rom("Sony Console/Lantern Keep (USA)", "Lantern Keep (USA).bin", "hash-lantern")
        rom("neogeo", "Pocket.ngc", "hash-pocket")
        rom("gamegear", "Small.gg", "hash-small")
        rom("loose/Some Game", "Some Game.bin", "hash-loose")

        val h = CollectionHasher()
        pipeline(h, MapLookup(nested)).scan(listOf(romRoot.absolutePath))

        assertEquals(mapOf("Lantern Keep (USA).bin" to "psx", "Pocket.ngc" to "ngpc", "Small.gg" to "gamegear",
                           "Some Game.bin" to "Some Game"), h.told.toMap())
    }

    // What a library scanned by a build before this one meets at its next
    // scan. The key of a game in a folder of its own ended in that folder's
    // name, and now ends in the collection's: the metadata file no longer
    // answers for the ROM, so the ROM is read and asked about once more, its
    // file is written over under the same id, and the index has the one key.
    // After that it is found as any other. A miss is kept by the ROM's path,
    // which has not moved, and costs nothing.
    @Test fun `a game matched under its folder's name is read once more and then found by its collection's`(): Unit = runBlocking {
        rom("psx/Lantern Keep (USA)", "Lantern Keep (USA).bin", "hash-lantern")
        rom("psx/Unknown Game", "Unknown Game.bin", "hash-unknown")
        // No metafile: the scan takes each folder for a platform, as every
        // scan did.
        pipeline(ContentHasher(), MapLookup(nested)).scan(listOf(romRoot.absolutePath))
        fun byKey() = JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey").keySet()
        assertEquals(setOf("lanternkeep|lanternkeepusa"), byKey())

        collection("psx", "PlayStation", "psx")
        val h = ContentHasher(); val l = MapLookup(nested)
        val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 1, cached = 0, skipped = 0, unmatched = 1, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(1, h.calls.get(), "only the match is read again")
        assertEquals(1, l.calls.get(), "only the match is asked about again")
        assertEquals(setOf("lanternkeep|psx"), byKey())
        assertEquals(1, s.indexed)
        assertEquals(listOf("3001.json"),
                     paths.metadata.list()!!.filter { it.endsWith(".json") && !it.startsWith("_") })

        val h2 = ContentHasher(); val l2 = MapLookup(nested)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 1, skipped = 0, unmatched = 1, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s2.counts())
        assertEquals(0, h2.calls.get()); assertEquals(0, l2.calls.get())
    }

    // The list of platforms to turn away is asked about the folder as well as
    // about the short name. `vita` is not on it and `psvita` is: with the
    // short name alone, giving the collection its name would have had every
    // file in it read and asked about.
    @Test fun `psvita declaring shortname vita is still skipped`(): Unit = runBlocking {
        collection("psvita", "PlayStation Vita", "vita")
        val f = rom("psvita", "Some Game.zip", "never read")

        val h = ContentHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.skippedPlatforms)
        assertEquals(0, h.calls.get(), "the file was read")
        assertEquals("UNSUPPORTED", ledgerEntry(f).getString("state"))
    }

    // An extension a collection declares counted in the collection's own
    // folder and not in a game's folder under it, where each shell read the
    // metafile of the one folder a file was in. And it is the collection's:
    // the same extension in a collection that does not declare it is no ROM.
    @Test fun `an extension only the collection declares is found in a sub-folder`(): Unit = runBlocking {
        collection("psx", "PlayStation", "psx", extensions = "cue, JUD")
        collection("snes", "Super Nintendo", "snes")
        rom("psx/Lantern Keep (USA)", "Lantern Keep (USA).jud", "hash-lantern")
        rom("psx", "Loose.jud", "hash-loose")
        // What a collection declares is added to the built-in list and does
        // not stand in for it: one that lists two extensions keeps its `.bin`.
        rom("psx/Another Game", "Unlisted.bin", "hash-unlisted")
        rom("snes/Some Game", "Some Game.jud", "hash-undeclared")
        rom("snes", "Plain.sfc", "hash-plain")

        val h = CollectionHasher()
        val s = pipeline(h, MapLookup(nested)).scan(listOf(romRoot.absolutePath))

        assertEquals(setOf("Lantern Keep (USA).jud", "Loose.jud", "Unlisted.bin", "Plain.sfc"), h.handed.keys)
        assertEquals(4, s.total)
        assertEquals("lanternkeep|psx", JSONObject(paths.metadata("3001").readText()).getString("cacheKey"))
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
            ScanLedger.State.MATCHED to 4, ScanLedger.State.NOT_FOUND to 6,
            ScanLedger.State.KNOWN_UNSUPPORTED to 1,
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
