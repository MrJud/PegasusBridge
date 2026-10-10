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

private const val NUL = "\u0000"

/**
 * What a test writes for a ROM: the text its hasher answers with, and a NUL
 * after it. A small file of nothing but text is a placeholder, which a scan
 * neither hashes nor asks about, and a ROM is never only text.
 */
private fun romText(content: String): String = content + NUL

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
            val text = f.readText().removeSuffix(NUL).trim()
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
        return File(dir, name).apply { writeText(romText(content)) }
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

    // Metadata written before the plain hashes existed carries no fileMd5, and
    // a scan used to read such a ROM whole again to fill the field in. Nothing
    // reads the field from a metadata file, so the file is the match it says
    // it is, here with no ledger beside it to say so first.
    @Test fun `metadata without a plain hash is a match all the same and its rom is not read`(): Unit = runBlocking {
        val f = rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        // Strip the fields, imitating a file from the previous schema.
        val meta = paths.metadata("1446")
        val j = JSONObject(meta.readText())
        j.getJSONObject("rom").apply { remove("fileMd5"); remove("fileCrc32") }
        meta.writeText(j.toString(2))
        assertTrue(File(paths.cache, ScanLedger.FILE_NAME).delete())

        val h2 = ContentHasher(); val l2 = MapLookup(catalogue)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(0, h2.calls.get(), "the rom was read for a field nobody asks for")
        assertEquals(0, l2.calls.get())
        assertEquals(1, s2.cachedHits)
        assertFalse(JSONObject(meta.readText()).getJSONObject("rom").has("fileMd5"), "the file was rewritten")
        assertEquals("MATCHED", ledgerEntry(f).getString("state"))
        assertEquals(1446, ledgerEntry(f).getInt("gameId"), "the match was adopted without its game")
    }

    /** Two hashes of each of two games, as two dumps of one game have. */
    private val twins = mapOf(
        "hash-ctra"           to GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40),
        "hash-ctra-revision"  to GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40),
        "hash-lantern-sheet"  to GameMetadata(3001, "Lantern Keep", "PlayStation", "/Images/3.png", 30),
        "hash-lantern-track-of-another-length" to GameMetadata(3001, "Lantern Keep", "PlayStation", "/Images/3.png", 30))

    // One game has one metadata file, and that file describes one ROM: the
    // last one written. The other file of the game, a second dump or the
    // track beside its sheet, was not described by it, so it was hashed and
    // asked about at every scan and counted as new each time. The ledger
    // holds a match for each file, and each is skipped on that.
    @Test fun `two roms of one game are both skipped on a rescan`(): Unit = runBlocking {
        // Two dumps under two titles, and so two keys.
        rom("nes", "Contra (USA).nes", "hash-ctra")
        rom("nes", "Gryzor (Europe).nes", "hash-ctra-revision")
        // A sheet and its track under one name, and so one key.
        rom("psx", "Lantern Keep (USA).cue", "hash-lantern-sheet")
        rom("psx", "Lantern Keep (USA).bin", "hash-lantern-track-of-another-length")
        assertEquals(3, romRoot.walkTopDown().filter { it.isFile }
            .map { com.pegasus.bridge.core.FuzzyMatch.makeCacheKey(it.nameWithoutExtension, it.parentFile.name) }
            .toSet().size, "the four files were to have three keys")

        val s1 = pipeline(ContentHasher(), MapLookup(twins)).scan(listOf(romRoot.absolutePath))
        assertEquals(4, s1.newEntries)
        assertEquals(2, s1.indexed)

        val h2 = ContentHasher(); val l2 = MapLookup(twins)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(0, h2.calls.get(), "a second file of a game was read again")
        assertEquals(0, l2.calls.get(), "a second file of a game was asked about again")
        assertEquals(counts(new = 0, cached = 4, skipped = 0, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s2.counts())
        assertEquals(mapOf(ScanLedger.State.MATCHED to 4), s2.states)
        assertEquals(2, s2.indexed)
    }

    // The theme finds a game by the key its metadata file carries, which is
    // the name of one of its ROMs. When that ROM is deleted, the one left
    // must not go on being skipped on the ledger's word: the file would name
    // a ROM that is not there for good. It is identified once more, which
    // writes the file under its own name.
    @Test fun `a match whose game's file names a rom that is gone is identified again`(): Unit = runBlocking {
        val dumps = listOf(rom("nes", "Contra (USA).nes", "hash-ctra"),
                           rom("nes", "Gryzor (Europe).nes", "hash-ctra-revision"))
        fun key(f: File) = com.pegasus.bridge.core.FuzzyMatch.makeCacheKey(f.nameWithoutExtension, "nes")
        pipeline(ContentHasher(), MapLookup(twins)).scan(listOf(romRoot.absolutePath))

        val meta = paths.metadata("1447")
        val named = dumps.single { key(it) == JSONObject(meta.readText()).getString("cacheKey") }
        val survivor = dumps.single { it != named }
        assertTrue(named.delete())

        val h2 = ContentHasher(); val l2 = MapLookup(twins)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, h2.calls.get(), "the rom left was skipped though its game's file names another")
        assertEquals(1, l2.calls.get())
        assertEquals(1, s2.newEntries)
        assertEquals(key(survivor), JSONObject(meta.readText()).getString("cacheKey"))
        val byKey = JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey")
        assertEquals(setOf(key(survivor)), byKey.keySet())

        val h3 = ContentHasher()
        val s3 = pipeline(h3, NeverAsked()).scan(listOf(romRoot.absolutePath))
        assertEquals(0, h3.calls.get())
        assertEquals(1, s3.cachedHits)
    }

    // A file under the name is not enough: the ROM the metadata file names
    // has to be the game's still. Here it is written over by another game.
    // Skipped because some file answers to that name, the ROM that is left
    // stayed out of the index's keys, and the one key was claimed there by
    // two games, the old one and the one the file now is.
    @Test fun `a match whose game's file names a rom that is another game now is identified again`(): Unit = runBlocking {
        val dumps = listOf(rom("nes", "Contra (USA).nes", "hash-ctra"),
                           rom("nes", "Gryzor (Europe).nes", "hash-ctra-revision"))
        fun key(f: File) = com.pegasus.bridge.core.FuzzyMatch.makeCacheKey(f.nameWithoutExtension, "nes")
        val known = twins + catalogue
        pipeline(ContentHasher(), MapLookup(known)).scan(listOf(romRoot.absolutePath))

        val meta = paths.metadata("1447")
        val named = dumps.single { key(it) == JSONObject(meta.readText()).getString("cacheKey") }
        val left = dumps.single { it != named }
        named.writeText(romText("hash-smb"))

        val h2 = ContentHasher(); val l2 = MapLookup(known)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(2, h2.calls.get(), "the rom left was skipped though its game's file names what is another game now")
        assertEquals(2, s2.newEntries)
        assertEquals(key(left), JSONObject(meta.readText()).getString("cacheKey"))
        val byKey = JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey")
        assertEquals(mapOf(key(named) to 1446, key(left) to 1447),
                     byKey.keySet().associateWith { byKey.getJSONObject(it).getInt("gameId") })

        val h3 = ContentHasher()
        val s3 = pipeline(h3, NeverAsked()).scan(listOf(romRoot.absolutePath))
        assertEquals(0, h3.calls.get())
        assertEquals(2, s3.cachedHits)
    }

    // The same when what took its place is no game at all: an empty file
    // under the ROM's name, as a library keeps for a game it does not hold.
    @Test fun `a match whose game's file names what is a placeholder now is identified again`(): Unit = runBlocking {
        val dumps = listOf(rom("nes", "Contra (USA).nes", "hash-ctra"),
                           rom("nes", "Gryzor (Europe).nes", "hash-ctra-revision"))
        fun key(f: File) = com.pegasus.bridge.core.FuzzyMatch.makeCacheKey(f.nameWithoutExtension, "nes")
        pipeline(ContentHasher(), MapLookup(twins)).scan(listOf(romRoot.absolutePath))

        val meta = paths.metadata("1447")
        val named = dumps.single { key(it) == JSONObject(meta.readText()).getString("cacheKey") }
        val left = dumps.single { it != named }
        named.writeText("")

        val h2 = ContentHasher(); val l2 = MapLookup(twins)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, h2.calls.get(), "the rom left was skipped though its game's file names a placeholder")
        assertEquals(counts(new = 1, cached = 0, skipped = 1, unmatched = 0, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s2.counts())
        assertEquals(key(left), JSONObject(meta.readText()).getString("cacheKey"))
        assertEquals(setOf(key(left)),
                     JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey").keySet())
    }

    // And when the ROM it names stands in the ledger as a match, but of
    // another game. That is what a scan of one folder leaves behind when the
    // two dumps are kept in two: the one written over is found to be another
    // game, and the one in the folder that was not scanned is still held
    // for the first, whose file still names the other.
    @Test fun `a match whose game's file names a rom the ledger holds for another game is identified again`(): Unit = runBlocking {
        val elsewhere = Files.createTempDirectory("hasher-roms-2").toFile()
        try {
            val dumps = listOf(
                rom("nes", "Contra (USA).nes", "hash-ctra"),
                File(File(elsewhere, "nes").apply { mkdirs() }, "Gryzor (Europe).nes")
                    .apply { writeText(romText("hash-ctra-revision")) })
            fun key(f: File) = com.pegasus.bridge.core.FuzzyMatch.makeCacheKey(f.nameWithoutExtension, "nes")
            val both = listOf(romRoot.absolutePath, elsewhere.absolutePath)
            val known = twins + catalogue
            pipeline(ContentHasher(), MapLookup(known)).scan(both)

            val meta = paths.metadata("1447")
            val named = dumps.single { key(it) == JSONObject(meta.readText()).getString("cacheKey") }
            val left = dumps.single { it != named }
            named.writeText(romText("hash-smb"))
            pipeline(ContentHasher(), MapLookup(known)).scan(listOf(named.parentFile.parentFile.absolutePath))
            assertEquals(1446, ledgerEntry(named).getInt("gameId"))
            assertEquals(1447, ledgerEntry(left).getInt("gameId"))
            assertEquals(key(named), JSONObject(meta.readText()).getString("cacheKey"))

            val h3 = ContentHasher(); val l3 = MapLookup(known)
            val s3 = pipeline(h3, l3).scan(both)

            assertEquals(1, h3.calls.get(), "the rom left was skipped though its game's file names a rom of another game")
            assertEquals(counts(new = 1, cached = 1, skipped = 0, unmatched = 0, incompatible = 0,
                                hashFailed = 0, failedLookups = 0), s3.counts())
            val byKey = JSONObject(paths.discoveryIndex.readText()).getJSONObject("byKey")
            assertEquals(mapOf(key(named) to 1446, key(left) to 1447),
                         byKey.keySet().associateWith { byKey.getJSONObject(it).getInt("gameId") })
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    // The ledgers on devices today: a build that found a match through its
    // metadata wrote the entry again without the game, and some entries are
    // still under a number no build gives any more. Neither can say which
    // metadata file is its own. Each match is taken from the file that
    // describes its ROM, and leaves the scan as the ledger now keeps one.
    @Test fun `a ledger written before it kept the game keeps every match without a read`(): Unit = runBlocking {
        val old = rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        val recent = rom("nes", "Contra (USA).nes", "hash-ctra")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        val today = ledgerEntry(recent).getInt("algorithmVersion")

        val ledgerFile = File(paths.cache, ScanLedger.FILE_NAME)
        val ledger = JSONObject(ledgerFile.readText())
        for (f in listOf(old, recent)) ledger.getJSONObject("entries").getJSONObject(f.canonicalPath).remove("gameId")
        ledger.getJSONObject("entries").getJSONObject(old.canonicalPath).put("algorithmVersion", 4)
        ledgerFile.writeText(ledger.toString())

        val h2 = ContentHasher()
        val s2 = pipeline(h2, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(0, h2.calls.get(), "a match was read again")
        assertEquals(2, s2.cachedHits)
        assertEquals(listOf(1446, 1447), listOf(old, recent).map { ledgerEntry(it).optInt("gameId") })
        assertEquals(listOf(today, today), listOf(old, recent).map { ledgerEntry(it).getInt("algorithmVersion") })
        assertEquals(listOf("MATCHED", "MATCHED"), listOf(old, recent).map { ledgerEntry(it).getString("state") })
    }

    // The ledger says a file matched a game; the metadata file is what the
    // theme shows of it. With the file gone the match is worth nothing to
    // anybody, and skipping the ROM would keep the game out of the list.
    @Test fun `a match whose metadata file is gone is identified again`(): Unit = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertTrue(paths.metadata("1446").delete())

        val h2 = ContentHasher(); val l2 = MapLookup(catalogue)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, h2.calls.get())
        assertEquals(1, l2.calls.get())
        assertEquals(1, s2.newEntries)
        assertEquals(0, s2.cachedHits)
        assertEquals(1, s2.indexed, "the game is back in the list")
        assertTrue(paths.metadata("1446").isFile)
    }

    // What this is all for: with nothing changed, and every verdict one that
    // is kept, a second scan opens no ROM and makes no request, whatever the
    // first one found each file to be.
    @Test fun `a rescan of a library that has not changed hands the hasher nothing and asks nothing`(): Unit = runBlocking {
        rom("nes", "Match.nes", "hash-match-1")
        rom("nes", "Miss.nes", "hash-miss")
        rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-virtual")
        stub("nes", "Nothing (World).nes", "")
        stub("nes", "Not Here Yet (World).nes")
        rom("switch", "Game.nes", "hash-switch")
        rom("wii", "Game.wbfs", "never read")
        zip("nes", "Two Games.zip", "first.nes" to "hash-first", "second.nes" to "hash-second")
        zip("nes", "Patch.zip", "Patch.ips" to "x".repeat(64), "readme.txt" to "x")
        zip("psx", "Disc.zip", "Disc.ccd" to "[CloneCD]", "Disc.img" to "x".repeat(4096))
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        val states = mapOf(
            ScanLedger.State.MATCHED to 1, ScanLedger.State.NOT_FOUND to 1,
            ScanLedger.State.KNOWN_UNSUPPORTED to 1, ScanLedger.State.PLACEHOLDER to 2,
            ScanLedger.State.UNSUPPORTED to 1, ScanLedger.State.UNSUPPORTED_FORMAT to 1,
            ScanLedger.State.AMBIGUOUS_ARCHIVE to 1, ScanLedger.State.NO_PLAYABLE_ENTRY to 1,
            ScanLedger.State.UNHASHABLE to 1)
        try {
            val l1 = MixedLookup()
            val s1 = RomScanPipeline(paths, ArchiveAwareHasher(ContentHasher(), tmp), l1, throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))
            assertEquals(counts(new = 1, cached = 0, skipped = 3, unmatched = 1, incompatible = 1,
                                hashFailed = 4, failedLookups = 0), s1.counts())
            assertEquals(states, s1.states)
            assertEquals(3, l1.asked.size, "asked: ${l1.asked}")

            val h2 = ContentHasher()
            // Counted where the archive hasher is asked as well, so that an
            // archive opened only to be listed shows too.
            val opened = AtomicInteger()
            val archives = ArchiveAwareHasher(h2, tmp)
            val counting = object : RomHasher by archives {
                override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome {
                    opened.incrementAndGet()
                    return archives.hashDetailed(path, collection)
                }
            }
            val s2 = RomScanPipeline(paths, counting, NeverAsked(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(0, opened.get(), "a file was handed to the hasher")
            assertEquals(0, h2.calls.get())
            assertEquals(counts(new = 0, cached = 1, skipped = 3, unmatched = 1, incompatible = 1,
                                hashFailed = 4, failedLookups = 0), s2.counts())
            assertEquals(states, s2.states)
        } finally {
            tmp.deleteRecursively()
        }
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

    // The index lists a game only when its file has an `ra` object. A file
    // without one, trusted as a match, kept its ROM away from the hasher and
    // out of the list for as long as the ROM stayed the same. A scan knows a
    // game exactly when the index would list it: by the ledger, and with the
    // ledger gone by the file alone, the ROM is identified again and its
    // file written whole.
    @Test fun `metadata the index would not list is looked up again`(): Unit = runBlocking {
        rom("nes", "Contra (USA).nes", "hash-ctra")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        val meta = paths.metadata("1447")

        for (ledgerKept in listOf(true, false)) {
            meta.writeText(JSONObject(meta.readText()).apply { remove("ra") }.toString(2))
            if (!ledgerKept) assertTrue(File(paths.cache, ScanLedger.FILE_NAME).delete())

            val h = ContentHasher(); val l = MapLookup(catalogue)
            val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

            assertEquals(1, h.calls.get(), "with the ledger kept: $ledgerKept")
            assertEquals(1, l.calls.get())
            assertEquals(1, s.newEntries)
            assertEquals(1, s.indexed, "the game is in the list again")
            assertTrue(JSONObject(meta.readText()).has("ra"))
        }
    }

    @Test fun `an edited file is rescanned`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        f.writeText(romText("hash-ctra"))
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

    // A library with one metafile at its top, which declares every
    // collection and sends none to a folder: the folders under it are named
    // for the consoles. Every one of them was the first collection declared.
    // The PlayStation sheet was then a Nintendo file that describes a disc,
    // refused unread; the Mega Drive cartridge was a `.md` in a collection
    // that lists none, and no file of the scan at all; and all of it was
    // written down under platform `nes`.
    @Test fun `one metafile above the console folders gives each folder its own collection`(): Unit = runBlocking {
        File(romRoot, "metadata.pegasus.txt").writeText("""
            collection: Nintendo Entertainment System
            shortname: nes
            extensions: nes

            collection: Sony PlayStation
            shortname: psx
            extensions: cue, bin

            collection: Sega Mega Drive
            shortname: megadrive
            extensions: md, bin
        """.trimIndent())
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        rom("psx/Lantern Keep (USA)", "Lantern Keep (USA).cue", "hash-lantern")
        rom("megadrive", "Cart (World).md", "hash-cart")
        rom("gba", "Pocket (World).gba", "hash-pocket")

        val h = CollectionHasher()
        val s = pipeline(h, MapLookup(catalogue + nested)).scan(listOf(romRoot.absolutePath))

        assertEquals(mapOf("Super Mario Bros. (World).nes" to ("nes" to "nes"),
                           "Lantern Keep (USA).cue" to ("psx" to "psx"),
                           "Cart (World).md" to ("megadrive" to "megadrive"),
                           "Pocket (World).gba" to ("gba" to "gba")),
                     h.handed.mapValues { it.value.shortName to it.value.dirName })
        assertEquals(mapOf(ScanLedger.State.MATCHED to 2, ScanLedger.State.NOT_FOUND to 2), s.states)
        assertEquals("lanternkeep|psx", JSONObject(paths.metadata("3001").readText()).getString("cacheKey"))
    }

    // Three files a scan did not pick up, each for want of an entry: a
    // Mega Drive cartridge called `.md` in the folder ES-DE keeps for the
    // Japanese console, which had no row and so was no Mega Drive folder; a
    // Famicom disk; and a Neo Geo cartridge in one file. The console each is
    // handed to rcheevos as is the table's: 1, the Disk System's 81, and 27
    // by its bytes where every other file of an arcade collection is
    // refused or hashed by its name.
    @Test fun `a cartridge under ES-DE's other name, a Famicom disk and a neo cartridge are hashed as the table says`(): Unit = runBlocking {
        class Told : RomHasher {
            val consoles = java.util.concurrent.ConcurrentHashMap<String, Int>()
            override fun hash(path: String): HashResult? = null
            override fun hashForConsole(path: String, consoleId: Int): HashOutcome {
                consoles[File(path).parentFile.name + "/" + File(path).name] = consoleId
                return HashOutcome.Ok(HashResult("hash-of-" + File(path).name, consoleId))
            }
        }
        rom("megadrivejp", "Cart (Japan).md", "x")
        rom("megadrivejp", "Cart (Japan).bin", "x")
        rom("megacdjp", "Disc (Japan).iso", "x")
        rom("sega32xna", "Cart (USA).bin", "x")
        rom("nes", "Disk (Japan).fds", "x")
        rom("arcade", "brawler.neo", "x")
        rom("arcade", "chip.bin", "x")
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        try {
            val h = Told()
            val s = RomScanPipeline(paths, ArchiveAwareHasher(h, tmp), MapLookup(emptyMap()), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(mapOf("megadrivejp/Cart (Japan).md" to 1, "megadrivejp/Cart (Japan).bin" to 1,
                               "megacdjp/Disc (Japan).iso" to 9, "sega32xna/Cart (USA).bin" to 10,
                               "nes/Disk (Japan).fds" to 81, "arcade/brawler.neo" to 27),
                         h.consoles.toMap())
            assertEquals(mapOf(ScanLedger.State.NOT_FOUND to 6, ScanLedger.State.UNSUPPORTED_FORMAT to 1), s.states)
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
    // which has not moved, and is read and asked about once more all the
    // same: it was the miss of a file in a collection nobody had heard of,
    // left to rcheevos to guess at, and the file is a PlayStation's now.
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
        assertEquals(2, h.calls.get(), "each is read again, once")
        assertEquals(2, l.calls.get(), "each is asked about again, once")
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

    // ── What nobody can hash ────────────────────────────────────────────────

    /** Answers no for the hashes it is told to expect, and fails the scan on any other. */
    private class AskedOnlyAbout(private vararg val expected: String) : RaHashLookup {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override suspend fun lookup(hash: String): LookupOutcome {
            if (hash !in expected) throw AssertionError("the source was asked about $hash")
            asked += hash
            return LookupOutcome.NotFound
        }
    }

    // Two kinds of file a scan can say no to from a name alone. One is every
    // file of a collection nobody can hash for: RetroAchievements has no such
    // console, or it has and rcheevos no algorithm for it. The other is a
    // file that cannot be hashed in a collection that can: a disc image
    // packed in a way nothing here reads, a chip of an arcade set, a readme.
    // Each was read to its end, the disc images too, given the hash of
    // something that is not the game, and asked about; and the answer, no,
    // was kept as a game the database lacks.
    //
    // The last file is the other side of it: a collection the list of
    // platforms never had and the table does, hashed and asked about.
    @Test fun `collections and formats nobody can hash cost no read and no request`(): Unit = runBlocking {
        collection("ps3", "PlayStation 3", "ps3")
        collection("switch", "Nintendo Switch", "switch")
        // A short name the table has never heard of leaves it to the folder.
        collection("bbcmicro", "BBC Micro", "beeb", extensions = "ssd")
        collection("chailove", "ChaiLove", "chailove", extensions = "chailove")
        collection("n3ds", "Nintendo 3DS", "3ds", extensions = "cci")
        // A `.md` is listed only where its collection is of the Mega Drive
        // or says it is a ROM. This one says so, wrongly: the scan finds the
        // file, and the table refuses it all the same.
        collection("psx", "PlayStation", "psx", extensions = "cue, md")
        val unsupported = mapOf(
            rom("ps3/Some Game/PS3_GAME/USRDIR", "EBOOT.BIN", "never read")
                to "rcheevos' algorithm for RC_CONSOLE_PLAYSTATION_3 (id 82) is held back: " +
                   "RetroAchievements has no hashes of PlayStation 3 games",
            rom("switch/Mods", "x.zip", "never read") to "RetroAchievements has no console for switch",
            rom("amiga", "x.adf", "never read") to "rcheevos has no hashing algorithm for RC_CONSOLE_AMIGA (id 35)",
            rom("cdimono1", "x.bin", "never read") to "rcheevos has no hashing algorithm for RC_CONSOLE_CDI (id 42)",
            rom("bbcmicro", "x.ssd", "never read") to "RetroAchievements has no console for bbcmicro",
            rom("chailove", "x.chailove", "never read") to "RetroAchievements has no console for chailove",
            rom("n3ds", "x.cci", "never read")
                to "rcheevos' algorithm for RC_CONSOLE_NINTENDO_3DS (id 62) is not built in: it needs decryption keys")
        val unsupportedFormat = mapOf(
            rom("wii", "x.wbfs", "never read") to ".wbfs is a format this build has no reader for",
            rom("ps2", "x.chd", "never read") to ".chd is a format this build has no reader for",
            rom("psp", "x.cso", "never read") to ".cso is a format rcheevos does not read",
            rom("arcade", "chip.bin", "never read") to "an arcade set is a .zip or a .7z, and this is a .bin",
            rom("psx", "README.md", "never read") to "a .md file in this collection is not a Mega Drive cartridge")
        val hashed = rom("adam", "x.bin", "hash-adam")
        // And where no collection says so a readme is not a file of the
        // scan at all: it is in no count below and nothing is kept for it.
        val readme = rom("ps2", "README.md", "never read")

        val expected = counts(new = 0, cached = 0, skipped = 7, unmatched = 1, incompatible = 0,
                              hashFailed = 5, failedLookups = 0)
        val states = mapOf(ScanLedger.State.UNSUPPORTED to 7, ScanLedger.State.UNSUPPORTED_FORMAT to 5,
                           ScanLedger.State.NOT_FOUND to 1)

        val h = CollectionHasher(); val l = AskedOnlyAbout("hash-adam")
        val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

        assertEquals(13, s.total)
        assertEquals(expected, s.counts())
        assertEquals(states, s.states)
        assertEquals(setOf("x.bin"), h.handed.keys, "the files the hasher was handed")
        assertEquals("adam", h.handed.getValue("x.bin").shortName)
        assertEquals(1, h.inner.calls.get())
        assertEquals(listOf("hash-adam"), l.asked.toList())
        for ((file, reason) in unsupported) {
            assertEquals("UNSUPPORTED" to reason,
                         ledgerEntry(file).let { it.getString("state") to it.getString("detail") }, file.path)
        }
        for ((file, reason) in unsupportedFormat) {
            assertEquals("UNSUPPORTED_FORMAT" to reason,
                         ledgerEntry(file).let { it.getString("state") to it.getString("detail") }, file.path)
        }
        assertEquals("NOT_FOUND", ledgerEntry(hashed).getString("state"))
        assertFalse(JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText()).getJSONObject("entries")
                        .has(readme.canonicalPath), "a readme was kept in the ledger")

        // And again on a rescan, each file in the count it was in, with
        // nothing read and nothing asked: the miss is found standing.
        val h2 = CollectionHasher()
        val s2 = pipeline(h2, AskedOnlyAbout()).scan(listOf(romRoot.absolutePath))

        assertEquals(expected, s2.counts())
        assertEquals(states, s2.states)
        assertEquals(0, h2.inner.calls.get(), "a file was read on the rescan")
    }

    // What a library scanned by a build before this one meets at its next
    // scan. That build hashed an Amiga disk as a Game Boy cartridge and a
    // packed disc image as its container, asked about both, and kept the two
    // answers as misses, good for a fortnight; and a metadata file can be
    // lying there that answers to the key of a file nobody can hash. The
    // table is asked before either is looked at, or the old answer would
    // stand in its place: the miss until it ran out, the metadata file for
    // as long as the ROM was not touched.
    @Test fun `what an earlier scan kept for a file nobody can hash gives way at once`(): Unit = runBlocking {
        val disk = rom("amiga", "Kept.adf", "never read")
        val packed = rom("ps2", "Packed.chd", "never read")
        val answered = rom("amiga", "Lantern Keep (USA).adf", "never read")
        ScanLedger(File(paths.cache, ScanLedger.FILE_NAME)).apply {
            for (f in listOf(disk, packed))
                record(f.canonicalPath, CollectionRef.inferred(f.parentFile.name), ScanLedger.State.NOT_FOUND,
                       f.length(), f.lastModified(), BridgePaths.epochSeconds())
            save { file, text -> BridgePaths.writeAtomic(file, text) }
        }
        paths.metadata("3001").writeText(JSONObject()
            .put("gameId", 3001).put("title", "Lantern Keep").put("platform", "amiga")
            .put("cacheKey", "lanternkeep|amiga")
            .put("ra", JSONObject().put("total", 30))
            .put("rom", JSONObject().put("hash", "hash-lantern").put("fileMd5", "md5-hash-lantern")
                .put("fileSize", answered.length()).put("lastModified", answered.lastModified()))
            .toString())

        val h = ContentHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 2, unmatched = 0, incompatible = 0,
                            hashFailed = 1, failedLookups = 0), s.counts())
        assertEquals(mapOf(ScanLedger.State.UNSUPPORTED to 2, ScanLedger.State.UNSUPPORTED_FORMAT to 1), s.states)
        assertEquals(0, h.calls.get(), "a file was read")
        assertEquals(listOf("UNSUPPORTED", "UNSUPPORTED_FORMAT", "UNSUPPORTED"),
                     listOf(disk, packed, answered).map { ledgerEntry(it).getString("state") })
    }

    // The short name is the collection's own word and is asked first. A
    // folder named for something nobody can hash, whose collection calls
    // itself by a name the table can hash for, is hashed; and a folder
    // `neogeo` that declares `ngpc` holds Neo Geo Pocket cartridges, which
    // are not to be turned away as the loose chips of an arcade set. The
    // folder decides only where the short name says nothing, as `bbcmicro`
    // does above. Asked in the other order, both collections here are lost
    // without a word.
    @Test fun `a short name the table knows is taken at its word whatever the folder is called`(): Unit = runBlocking {
        collection("switch", "Nintendo 8-bit", "nes")
        collection("neogeo", "Neo Geo Pocket Color", "ngpc")
        rom("switch", "Lantern Keep (USA).nes", "hash-lantern")
        val pocket = rom("neogeo", "Pocket.ngc", "hash-pocket")

        val h = CollectionHasher()
        val s = pipeline(h, MapLookup(nested)).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 1, cached = 0, skipped = 0, unmatched = 1, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(setOf("Lantern Keep (USA).nes", "Pocket.ngc"), h.handed.keys)
        assertEquals("NOT_FOUND", ledgerEntry(pocket).getString("state"))
        assertEquals("lanternkeep|nes", JSONObject(paths.metadata("3001").readText()).getString("cacheKey"))
    }

    // A folder named for one console of the family its collection declares
    // says which of them its files are first, and takes none of the others
    // away. An ES-DE library keeps `sega32x` and `segacd` beside `megadrive`,
    // each a collection that calls itself `megadrive` and lists `md` among
    // its extensions. A Mega Drive cartridge kept in either was turned away
    // as a Markdown file, by the row of a folder that knows one console. In
    // a collection with no Mega Drive in it a `.md` is a readme still.
    @Test fun `a folder that narrows the console keeps its collection's family`(): Unit = runBlocking {
        collection("sega32x", "Sega 16-bit and 32X", "megadrive", extensions = "32x, bin, md")
        collection("segacd", "Sega 16-bit and CD", "megadrive", extensions = "cue, bin, md")
        // It lists `md` as the other two do, or its readme would not be a
        // file of the scan to begin with.
        collection("gamegear", "Sega 8-bit", "mastersystem", extensions = "gg, sms, md")
        rom("sega32x", "Lantern Keep (USA).md", "hash-lantern")
        val second = rom("segacd", "Other Game (USA).md", "hash-other")
        val readme = rom("gamegear", "README.md", "never read")

        val h = CollectionHasher()
        val s = pipeline(h, AskedOnlyAbout("hash-lantern", "hash-other")).scan(listOf(romRoot.absolutePath))

        assertEquals(setOf("Lantern Keep (USA).md", "Other Game (USA).md"), h.handed.keys)
        assertEquals(counts(new = 0, cached = 0, skipped = 0, unmatched = 2, incompatible = 0,
                            hashFailed = 1, failedLookups = 0), s.counts())
        assertEquals("NOT_FOUND", ledgerEntry(second).getString("state"))
        assertEquals("UNSUPPORTED_FORMAT" to "a .md file in this collection is not a Mega Drive cartridge",
                     ledgerEntry(readme).let { it.getString("state") to it.getString("detail") })
    }

    // Such a verdict is reached again on every scan, before the ledger is
    // asked, so one is found standing only for a file the lists have let go
    // of since. Until it runs out the file is counted as it was, with the
    // files that gave no hash, and not as a platform skipped.
    @Test fun `a standing verdict that the format is not read counts with the files that gave no hash`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-smb")
        ScanLedger(File(paths.cache, ScanLedger.FILE_NAME)).apply {
            record(f.canonicalPath, CollectionRef.inferred("nes"), ScanLedger.State.UNSUPPORTED_FORMAT,
                   f.length(), f.lastModified(), BridgePaths.epochSeconds(),
                   detail = "a reason of a build before this one")
            save { file, text -> BridgePaths.writeAtomic(file, text) }
        }

        val h = ContentHasher(); val l = MapLookup(catalogue)
        val s = pipeline(h, l).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 0,
                            hashFailed = 1, failedLookups = 0), s.counts())
        assertEquals(mapOf(ScanLedger.State.UNSUPPORTED_FORMAT to 1), s.states)
        assertEquals(0, h.calls.get(), "the file was read")
        assertEquals(0, l.calls.get(), "the source was asked")
    }

    // An archive that gives nothing to hash was hashed as the file it is,
    // and to rcheevos a zip or a 7z it is told nothing about is an arcade
    // set: the hash is the MD5 of its name. So a download cut short, a patch
    // kept beside the game it is for, and a packed disc image zipped once
    // more were each asked about under a hash of a file name, and each
    // answer, no, was kept as a game the database lacks. None of them is
    // asked about now, and each is kept as what it is, so that the next scan
    // does not open it again.
    @Test fun `an archive with nothing playable is never asked about`(): Unit = runBlocking {
        val corrupt = rom("nes", "Lantern Keep (USA).zip", "the start of a download")
        val patch = zip("ps2", "Other Game (Europe) [patch].zip",
                        "Other Game (Europe).7z" to "x".repeat(4096), "how to apply.txt" to "x")
        val packed = zip("ps2", "Third Game (Japan).zip", "Third Game (Japan).chd" to "x".repeat(4096))
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        val expected = counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 0,
                              hashFailed = 3, failedLookups = 0)
        val states = mapOf(ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.NO_PLAYABLE_ENTRY to 1,
                           ScanLedger.State.UNSUPPORTED_FORMAT to 1)
        try {
            val h = ContentHasher()
            val s = RomScanPipeline(paths, ArchiveAwareHasher(h, tmp), NeverAsked(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(3, s.total)
            assertEquals(expected, s.counts())
            assertEquals(states, s.states)
            assertEquals(0, h.calls.get(), "an archive, or something out of one, was handed to rcheevos")
            fun kept(f: File) = ledgerEntry(f).let { it.getString("state") to it.getString("detail") }
            assertEquals("UNHASHABLE", kept(corrupt).first)
            assertTrue(kept(corrupt).second.startsWith("not a readable archive: "), kept(corrupt).second)
            assertEquals("NO_PLAYABLE_ENTRY" to "nothing in the archive is a game of this collection: " +
                                                "Other Game (Europe).7z", kept(patch))
            assertEquals("UNSUPPORTED_FORMAT" to "'Third Game (Japan).chd' in the archive: " +
                                                 ".chd is a format this build has no reader for", kept(packed))

            // And on a rescan each is found standing: counted where it was,
            // with no archive opened. A hasher that fails the scan when it is
            // handed anything stands in for the one that would open them.
            val untouched = object : RomHasher {
                override fun hash(path: String): HashResult? = throw AssertionError("$path was read again")
                override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome =
                    throw AssertionError("$path was opened again")
            }
            val s2 = pipeline(untouched, NeverAsked()).scan(listOf(romRoot.absolutePath))

            assertEquals(expected, s2.counts())
            assertEquals(states, s2.states)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // In a collection whose console is known, rcheevos is told the console
    // and what it says of a file is its last word: a cartridge cut short is
    // cut short at every scan, and is kept as a file that cannot be hashed
    // and not read again. A file that could not be opened says nothing of
    // itself and is tried again. So is every failure in a collection nobody
    // knows the console of, where rcheevos went by the extension, as before.
    @Test fun `a file its console refuses is kept, and one that could not be opened is read again`(): Unit = runBlocking {
        class Refusing : RomHasher {
            val read: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            override fun hash(path: String): HashResult? = null
            override fun hashForConsole(path: String, consoleId: Int): HashOutcome {
                read += File(path).parentFile.name + "/" + File(path).name + " as " + consoleId
                return HashOutcome.Failed(File(path).readText().removeSuffix(NUL))
            }
        }
        val refused = rom("nes", "Cut Short (World).nes", "File is not longer than a NES or FDS header (16 bytes)")
        val locked = rom("nes", "Locked (World).nes", "Could not open file")
        val unknown = rom("somewhere", "Cut Short (World).nes",
                          "File is not longer than a NES or FDS header (16 bytes)")
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        val expected = counts(new = 0, cached = 0, skipped = 0, unmatched = 0, incompatible = 0,
                              hashFailed = 3, failedLookups = 0)
        val states = mapOf(ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.HASH_FAILED to 2)
        try {
            val h = Refusing()
            val s = RomScanPipeline(paths, ArchiveAwareHasher(h, tmp), NeverAsked(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(expected, s.counts())
            assertEquals(states, s.states)
            assertEquals(listOf("nes/Cut Short (World).nes as 7", "nes/Locked (World).nes as 7",
                                "somewhere/Cut Short (World).nes as 0"), h.read.sorted())
            assertEquals(listOf("UNHASHABLE", "HASH_FAILED", "HASH_FAILED"),
                         listOf(refused, locked, unknown).map { ledgerEntry(it).getString("state") })
            assertEquals("the hasher could not read Cut Short (World).nes: " +
                         "File is not longer than a NES or FDS header (16 bytes)",
                         ledgerEntry(refused).getString("detail"))

            val h2 = Refusing()
            val s2 = RomScanPipeline(paths, ArchiveAwareHasher(h2, tmp), NeverAsked(), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(expected, s2.counts())
            assertEquals(states, s2.states)
            assertEquals(listOf("nes/Locked (World).nes as 7", "somewhere/Cut Short (World).nes as 0"),
                         h2.read.sorted(), "the files read again")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── Placeholders ────────────────────────────────────────────────────────

    /**
     * A file that stands for a game the library does not hold: [text] and
     * nothing after it, as the stubs found on a tablet are written, or no
     * byte at all for an empty [text].
     */
    private fun stub(platform: String, name: String,
                     text: String = "Placeholder for ${name.substringBeforeLast('.')} on $platform"): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(text) }
    }

    // A library that lists more games than it holds keeps a file for each of
    // the others, so that the frontend shows them: a sentence under the
    // game's name, or a file with nothing in it. Each sentence was hashed
    // as a cartridge and asked about, sixty requests for sixty answers of
    // no, and asked about again a fortnight later; each empty file was
    // read at every scan. A scan of nothing but such files now hands the
    // hasher nothing and asks nothing, and says at its end that it skipped
    // them, as it says of a platform nobody covers.
    @Test fun `a collection of stubs costs no hash and no request`(): Unit = runBlocking {
        // A sentence is a placeholder under any name a ROM has, an
        // archive's included: in a collection of arcade sets that is a
        // file hashed by its name alone, which nothing else would stop.
        val folders = listOf("gbc" to "gbc", "gamegear" to "gg", "megadrive" to "md", "sega32x" to "32x",
                             "genesis" to "bin", "nes" to "zip", "arcade" to "zip")
        val stubs = (0 until 60).map { n ->
            val (folder, extension) = folders[n % folders.size]
            stub(folder, "Invented Game $n.$extension")
        }
        // An empty file is one before its format is looked at: the third
        // is of a format nobody reads, and is a placeholder all the same.
        val empty = listOf(stub("nes", "Nothing Yet.nes", ""), stub("arcade", "nothingyet.zip", ""),
                           stub("psp", "Nothing Yet.cso", ""))
        val expected = counts(new = 0, cached = 0, skipped = 63, unmatched = 0, incompatible = 0,
                              hashFailed = 0, failedLookups = 0)
        val states = mapOf(ScanLedger.State.PLACEHOLDER to 63)
        fun sentence(s: RomScanPipeline.Summary) = ScanJobRecord.finished("job1", s, "someone", 1).getString("message")

        val h = CollectionHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(63, s.total)
        assertEquals(expected, s.counts())
        assertEquals(states, s.states)
        assertEquals(emptySet(), h.handed.keys, "the files the hasher was handed")
        assertEquals(0, h.inner.calls.get())
        assertEquals("Done — 0 new, 0 cached, 63 skipped, 0 not in the database", sentence(s))
        for (f in stubs) {
            assertEquals("PLACEHOLDER" to "text file, ${f.length()} bytes: not a ROM image",
                         ledgerEntry(f).let { it.getString("state") to it.getString("detail") }, f.path)
        }
        for (f in empty) {
            assertEquals("PLACEHOLDER" to "empty file",
                         ledgerEntry(f).let { it.getString("state") to it.getString("detail") }, f.path)
        }

        // A rescan finds every one standing. The first stub is made to show
        // that it is the verdict kept that answers for a file: other bytes,
        // as many, under the same date. A scan that went by the bytes again
        // would find no text in them and hand the file on.
        val unread = stubs.first()
        val date = unread.lastModified()
        unread.writeBytes(ByteArray(unread.length().toInt()))
        unread.setLastModified(date)

        val h2 = CollectionHasher()
        val s2 = pipeline(h2, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(expected, s2.counts())
        assertEquals(states, s2.states)
        assertEquals(emptySet(), h2.handed.keys, "the files the hasher was handed on the rescan")
        assertEquals("Done — 0 new, 0 cached, 63 skipped, 0 not in the database", sentence(s2))

        // And the game, when it comes to take a placeholder's place, is
        // another file by its size: read, asked about and found.
        val arrived = stubs[1].apply { writeText(romText("hash-smb")) }

        val h3 = CollectionHasher()
        val s3 = pipeline(h3, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(setOf(arrived.name), h3.handed.keys)
        assertEquals(expected + mapOf("newEntries" to 1, "skippedPlatforms" to 62), s3.counts())
        assertEquals("MATCHED", ledgerEntry(arrived).getString("state"))
    }

    // What a library scanned by a build before this one meets. Its stubs
    // were hashed and asked about, and each is in the ledger as a miss,
    // under the number that build kept every verdict by. This build keeps
    // its own under another, so neither miss is found standing, the one
    // of yesterday no more than the one of last month: each stub is
    // opened, once, and is a placeholder from then on, with nothing asked.
    // While both builds had one number, such a stub stayed a miss for what
    // was left of its fortnight.
    //
    // The third file holds the order of things on a rescan. A verdict that
    // stands is taken before a file is opened, which is what spares a scan
    // a read of every small file there is. So a miss under this build's own
    // number is left a miss, though no scan of this build writes one for
    // such a file.
    @Test fun `a stub an earlier build kept as a miss becomes a placeholder at the next scan`(): Unit = runBlocking {
        val recent = stub("gbc", "Asked Lately.gbc")
        val old = stub("gbc", "Asked Long Ago.gbc")
        val standing = stub("gbc", "Kept By This Build.gbc")
        val day = 24L * 60 * 60
        val ledgerFile = File(paths.cache, ScanLedger.FILE_NAME)
        ScanLedger(ledgerFile).apply {
            for ((f, age) in listOf(recent to 1, old to 30, standing to 1))
                record(f.canonicalPath, CollectionRef.inferred("gbc"), ScanLedger.State.NOT_FOUND,
                       f.length(), f.lastModified(), BridgePaths.epochSeconds() - age * day)
            save { file, text -> BridgePaths.writeAtomic(file, text) }
        }
        val written = JSONObject(ledgerFile.readText())
        for (f in listOf(recent, old))
            written.getJSONObject("entries").getJSONObject(f.canonicalPath)
                .put("algorithmVersion", HashRecipe.LAST_BY_HAND)
        ledgerFile.writeText(written.toString())

        val h = CollectionHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(counts(new = 0, cached = 0, skipped = 2, unmatched = 1, incompatible = 0,
                            hashFailed = 0, failedLookups = 0), s.counts())
        assertEquals(listOf("PLACEHOLDER", "PLACEHOLDER", "NOT_FOUND"),
                     listOf(recent, old, standing).map { ledgerEntry(it).getString("state") })
        assertEquals(emptySet(), h.handed.keys, "the files the hasher was handed")
    }

    // What is not text is no placeholder however small it is, and what is
    // text and long is none either: both go to the hasher as they did. So
    // does text under a name that is text by rights, a playlist here, which
    // is read as one. And a few bytes called `.cso` that are not text stay
    // a file of a format nobody reads. The collection nobody can hash for
    // comes ahead of everything: an empty file and a sentence under `switch`
    // are files of that platform, with its reason, as every other is.
    //
    // The longest text there can be is among them, 512 bytes, to hold the
    // scan to the rule's own limit: the scan opens only a file small enough
    // to be a stub, and one byte more is hashed.
    @Test fun `only an empty file or a short text is taken for a placeholder`(): Unit = runBlocking {
        val small = rom("nes", "Small (World).nes", "hash-smb")
        val longest = stub("nes", "As Long As It Gets (World).nes", "x".repeat(512))
        val long = stub("nes", "Long (World).nes", "hash-ctra".padEnd(513))
        val playlist = stub("psx", "Lantern Keep (USA).m3u", "Lantern Keep (USA).cue\n")
        val packed = rom("psp", "A Real Small One.cso", "CISO")
        val uncovered = listOf(stub("switch", "Nothing Yet.nes", ""), stub("switch", "Not Here Yet.nes"))
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        try {
            val h = ContentHasher()
            val s = RomScanPipeline(paths, ArchiveAwareHasher(h, tmp), MapLookup(catalogue), throttleMs = { 0L })
                .scan(listOf(romRoot.absolutePath))

            assertEquals(counts(new = 2, cached = 0, skipped = 3, unmatched = 0, incompatible = 0,
                                hashFailed = 2, failedLookups = 0), s.counts())
            assertEquals(listOf("MATCHED", "PLACEHOLDER", "MATCHED", "HASH_FAILED", "UNSUPPORTED_FORMAT"),
                         listOf(small, longest, long, playlist, packed).map { ledgerEntry(it).getString("state") })
            assertEquals("text file, 512 bytes: not a ROM image", ledgerEntry(longest).getString("detail"))
            assertEquals("the playlist names Lantern Keep (USA).cue, which is not there",
                         ledgerEntry(playlist).getString("detail"))
            assertEquals(".cso is a format rcheevos does not read", ledgerEntry(packed).getString("detail"))
            for (f in uncovered) {
                assertEquals("UNSUPPORTED" to "RetroAchievements has no console for switch",
                             ledgerEntry(f).let { it.getString("state") to it.getString("detail") }, f.name)
            }
            assertEquals(2, h.calls.get())
        } finally {
            tmp.deleteRecursively()
        }
    }

    // A sentence under the name of a format nobody reads. A library that
    // keeps a line of text for a game it does not hold calls it what the
    // game would be called, a packed disc image as readily as a cartridge,
    // and the scan asked the name before it asked the file: each such stub
    // was a `.cso` or a `.cdi` that could not be hashed, at every scan, and
    // a tablet with thirty of them ended each scan saying that thirty files
    // could not be hashed. It is a placeholder like any other, counted with
    // what was skipped.
    //
    // The scan opens such a file because it is small enough to be a stub,
    // and for no other reason: the same few bytes that are not text stay a
    // format nobody reads, and so does a file that says by its name that it
    // is no game, which a collection can list all the same. On a rescan the
    // stubs are found standing and are not opened: the bytes of one are
    // changed under the scan, at the same size and date, and it is a
    // placeholder still.
    @Test fun `a sentence under a format nobody reads is a placeholder and not a file that could not be hashed`(): Unit = runBlocking {
        collection("psp", "PlayStation Portable", "psp", extensions = "iso, cso, cfg")
        val stubs = listOf(stub("psp", "Not Here Yet.cso"), stub("dreamcast", "Not Here Yet.cdi"),
                           stub("wii", "Not Here Yet.wbfs"), stub("ps2", "Not Here Yet.chd"),
                           stub("arcade", "nothere.bin"), stub("psx", "Not Here Yet.pbp"))
        val images = listOf(rom("psp", "Small And Real.cso", "CISO"), rom("wii", "Small And Real.wbfs", "WBFS"))
        val notes = stub("psp", "launcher.cfg", "kept beside the games")
        val expected = counts(new = 0, cached = 0, skipped = 6, unmatched = 0, incompatible = 0,
                              hashFailed = 3, failedLookups = 0)
        val states = mapOf(ScanLedger.State.PLACEHOLDER to 6, ScanLedger.State.UNSUPPORTED_FORMAT to 3)

        val h = CollectionHasher()
        val s = pipeline(h, NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(expected, s.counts())
        assertEquals(states, s.states)
        assertEquals(emptySet(), h.handed.keys, "the files the hasher was handed")
        for (f in stubs) {
            assertEquals("PLACEHOLDER" to "text file, ${f.length()} bytes: not a ROM image",
                         ledgerEntry(f).let { it.getString("state") to it.getString("detail") }, f.path)
        }
        assertEquals(listOf(".cso is a format rcheevos does not read", ".wbfs is a format this build has no reader for",
                            "a .cfg file is not a game"),
                     (images + notes).map { ledgerEntry(it).getString("detail") })

        val unread = stubs[0]
        val date = unread.lastModified()
        unread.writeBytes(ByteArray(unread.length().toInt()))
        unread.setLastModified(date)

        val s2 = pipeline(CollectionHasher(), NeverAsked()).scan(listOf(romRoot.absolutePath))

        assertEquals(expected, s2.counts())
        assertEquals(states, s2.states)
        assertEquals("PLACEHOLDER", ledgerEntry(unread).getString("state"), "a stub that stood was opened again")
    }

    // A size of no bytes is also what the system answers for a file that
    // is not there. A file the walk found can be gone when its turn comes:
    // a card taken out, a folder moved while a long scan runs. Taken for
    // an empty file, each was written down as a placeholder and counted
    // with the files skipped, and a library that had gone away in the
    // middle of a scan ended it with nothing to say but that. It is asked
    // whether it is a file before it is called an empty one, and what is
    // not goes on to the hasher, which says that it is not there: a
    // failure, tried again at the next scan.
    //
    // One file is hashed at a time here, and the roots are walked in the
    // order given, so the second file is still waiting when the first is
    // hashed and takes it away.
    @Test fun `a file gone since the walk is not taken for an empty one`(): Unit = runBlocking {
        val here = File(romRoot, "held/nes").apply { mkdirs() }.resolve("Here (World).nes")
            .apply { writeText(romText("hash-smb")) }
        val gone = File(romRoot, "taken/nes").apply { mkdirs() }.resolve("Gone (World).nes")
            .apply { writeText(romText("hash-ctra")) }
        val takesAway = object : RomHasher {
            override fun hash(path: String): HashResult? {
                gone.delete()
                val text = File(path).readText().removeSuffix(NUL)
                return HashResult(text, 7, fileMd5 = "md5-$text", fileCrc32 = "crc-$text")
            }
        }
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        try {
            val s = RomScanPipeline(paths, ArchiveAwareHasher(takesAway, tmp), MapLookup(catalogue),
                                    throttleMs = { 0L }, hashWorkers = 1)
                .scan(listOf(here.parentFile.parent, gone.parentFile.parent))

            assertFalse(gone.exists())
            assertEquals(mapOf(ScanLedger.State.MATCHED to 1, ScanLedger.State.HASH_FAILED to 1), s.states)
            assertEquals(counts(new = 1, cached = 0, skipped = 0, unmatched = 0, incompatible = 0,
                                hashFailed = 1, failedLookups = 0), s.counts())
            assertEquals("HASH_FAILED" to "no such file",
                         ledgerEntry(gone).let { it.getString("state") to it.getString("detail") })
        } finally {
            tmp.deleteRecursively()
        }
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
     * two ROMs in it, a zip of a disc whose sheet nobody reads), five under a
     * platform RetroAchievements does not cover, and two lookups that brought
     * nothing usable back (one unanswered, one a real id with no title). And
     * a fourth file that gives no hash, added when it became an answer of its
     * own: a zip with no ROM in it. And three placeholders, two sentences and
     * an empty file, which are skipped as the five are.
     *
     * Scanned twice. The first scan asks about every hash. The second finds the
     * matches in their metadata and the verdicts in the ledger, and has to put
     * each file in the count the first one did without asking about it. Only
     * what is never kept as a verdict is done again: the unreadable file is read
     * and the two lookups are made.
     *
     * While the counts were four, the first scan here gave 4 new, 5 skipped and
     * 1 failed lookup: ten of what were then its 21 files.
     */
    @Test fun `every file is in exactly one of seven counts, on a first scan and on a rescan`(): Unit = runBlocking {
        repeat(4) { rom("nes", "Match $it.nes", "hash-match-$it") }
        repeat(6) { rom("nes", "Miss $it.nes", "hash-miss-$it") }
        rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-virtual")
        rom("nes", "Broken.nes", "UNHASHABLE")
        zip("nes", "Two Games.zip", "first.nes" to "hash-first", "second.nes" to "hash-second")
        zip("psx", "Disc.zip", "Disc.ccd" to "[CloneCD]", "Disc.img" to "x".repeat(4096))
        zip("nes", "Patch.zip", "Patch.ips" to "x".repeat(64), "readme.txt" to "x")
        repeat(5) { rom("switch", "Game $it.nes", "hash-switch-$it") }
        stub("nes", "Not Here Yet (World).nes")
        stub("nes", "Nor This (World).zip")
        stub("nes", "Nothing (World).nes", "")
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
            ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.NO_PLAYABLE_ENTRY to 1,
            ScanLedger.State.UNSUPPORTED to 5, ScanLedger.State.PLACEHOLDER to 3,
            ScanLedger.State.API_RETRY to 2)

        val l1 = MixedLookup()
        val (s1, seen1) = scan(ContentHasher(), l1)

        assertEquals(25, s1.total)
        assertEquals(25, s1.processed)
        assertEquals(counts(new = 4, cached = 0, skipped = 8, unmatched = 6, incompatible = 1,
                            hashFailed = 4, failedLookups = 2), s1.counts())
        assertEquals(states, s1.states)
        assertEquals(13, l1.asked.size, "asked: ${l1.asked}")

        val h2 = ContentHasher(); val l2 = MixedLookup()
        val (s2, seen2) = scan(h2, l2)

        assertEquals(25, s2.processed)
        assertEquals(counts(new = 0, cached = 4, skipped = 8, unmatched = 6, incompatible = 1,
                            hashFailed = 4, failedLookups = 2), s2.counts())
        assertEquals(states, s2.states)
        assertEquals(listOf("hash-silent", "hash-untitled"), l2.asked.sorted(),
                     "a file whose verdict was standing was asked about again")
        assertEquals(3, h2.calls.get(), "only the unreadable file and the two retries are read again")

        // With 25 files there is a report for every one of them.
        for ((s, seen) in listOf(s1 to seen1, s2 to seen2)) {
            assertEquals((1..25).toList(), seen.map { it.processed })
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
        // Kept as the scan keeps one: under the number of the file's
        // collection, here the folder's name with nothing declared, and of a
        // hasher that says nothing of itself. Under any other number the
        // verdict is no longer standing, and the file is read.
        ScanLedger(File(paths.cache, ScanLedger.FILE_NAME), HashRecipe("none")).apply {
            record(f.canonicalPath, CollectionRef.inferred("nes"), ScanLedger.State.UNSUPPORTED,
                   f.length(), f.lastModified(), BridgePaths.epochSeconds())
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
