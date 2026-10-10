package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.RcConsoles
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
 * The number a verdict is kept under, and what makes it another number.
 *
 * It was a constant raised by hand, and a verdict outlived every change
 * nobody raised it for: a library of another release, a console given to a
 * collection, a longer list of what a platform runs. It is worked out now
 * from those things, and these tests hold what follows for a library that
 * has been scanned before: what is read again, what is asked about again,
 * and what is left alone.
 */
class HashRecipeTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("recipe-data").toFile()
        romRoot  = Files.createTempDirectory("recipe-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    /**
     * A hasher that says which engine it is, and keeps the name of every
     * file it is handed. A file's text is its hash, but for four texts that
     * stand for the answers that are not one.
     */
    private class EngineHasher(override val engine: String) : RomHasher {
        val handed = ConcurrentLinkedQueue<String>()
        override fun hash(path: String): HashResult? = null
        override fun hashDetailed(path: String, platform: String): HashOutcome {
            handed += File(path).name
            return when (val text = File(path).readText().removeSuffix(NUL)) {
                "REFUSED" -> HashOutcome.Failed("the console refused it", retryable = false)
                "SEVERAL" -> HashOutcome.AmbiguousArchive(listOf("one.nes", "two.nes"))
                "NOTHING" -> HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection")
                "UNREAD" -> HashOutcome.UnsupportedFormat("a format this build has no reader for")
                else -> HashOutcome.Ok(HashResult(text, 7, fileMd5 = "md5-$text", fileCrc32 = "crc-$text"))
            }
        }
    }

    /** A game for `hash-match`, a dump known and not supported for `hash-dump`, and no for the rest. */
    private class Answers : RaHashLookup {
        val asked = ConcurrentLinkedQueue<String>()
        override suspend fun lookup(hash: String): LookupOutcome {
            asked += hash
            return when (hash) {
                "hash-match" -> GameMetadata(3001, "Lantern Keep", "NES", "/Images/3.png", 30)
                "hash-dump" -> GameMetadata(gameId = 1_100_001_487)
                else -> GameMetadata(gameId = 0)
            }.asOutcome()
        }
    }

    private fun rom(folder: String, name: String, content: String): File {
        val dir = File(romRoot, folder).apply { mkdirs() }
        return File(dir, name).apply { writeText(romText(content)) }
    }

    /** A sentence under a ROM's name, as a library keeps one for a game it does not hold. */
    private fun stub(folder: String, name: String): File {
        val dir = File(romRoot, folder).apply { mkdirs() }
        return File(dir, name).apply { writeText("Placeholder for ${name.substringBeforeLast('.')} on $folder") }
    }

    /** A collection declared in [folder] as Pegasus reads one. */
    private fun collection(folder: String, name: String, shortName: String, extensions: String? = null) {
        File(romRoot, folder).apply { mkdirs() }.resolve("metadata.pegasus.txt").writeText(
            "collection: $name\nshortname: $shortName\n" + (extensions?.let { "extensions: $it\n" } ?: ""))
    }

    private suspend fun scan(hasher: RomHasher, lookup: RaHashLookup) =
        RomScanPipeline(paths, hasher, lookup, throttleMs = { 0L }).scan(listOf(romRoot.absolutePath))

    private val ledgerFile: File get() = File(paths.cache, ScanLedger.FILE_NAME)

    private fun entry(f: File): JSONObject =
        JSONObject(ledgerFile.readText()).getJSONObject("entries").getJSONObject(f.canonicalPath)

    /** A row's line as the recipe of a build writes it. */
    private fun rowOf(collection: CollectionRef): String =
        RcConsoles.describe(RcConsoles.resolve(collection.shortName, collection.dirName))

    // ── The engine ──────────────────────────────────────────────────────────

    // A library of another release, or with another patch on it, may hash a
    // file otherwise and refuse another. Every verdict reached with the old
    // one is reached again, whatever state it is: the miss and the dump are
    // read and asked about, the file that was refused and the archive nobody
    // could choose in are read, the placeholder is opened. Nothing is, while
    // the engine is the one it was.
    @Test fun `a different engine redoes every verdict that is not a match`(): Unit = runBlocking {
        val nes = CollectionRef.inferred("nes")
        val match = rom("nes", "Match.nes", "hash-match")
        val redone = listOf(rom("nes", "Miss.nes", "hash-miss"), rom("nes", "Dump.nes", "hash-dump"),
                            rom("nes", "Refused.nes", "REFUSED"), rom("nes", "Several.nes", "SEVERAL"))
        val placeholder = stub("nes", "Not Here Yet.nes")
        val states = mapOf(
            ScanLedger.State.MATCHED to 1, ScanLedger.State.NOT_FOUND to 1, ScanLedger.State.KNOWN_UNSUPPORTED to 1,
            ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.AMBIGUOUS_ARCHIVE to 1, ScanLedger.State.PLACEHOLDER to 1)

        val lines = ConcurrentLinkedQueue<String>()
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) {}
            override fun i(tag: String, msg: String) { lines += "$tag: $msg" }
            override fun w(tag: String, msg: String, t: Throwable?) {}
            override fun e(tag: String, msg: String, t: Throwable?) {}
        }
        val h1 = EngineHasher("one"); val l1 = Answers()
        assertEquals(states, scan(h1, l1).states)
        BridgeLog.current = NoopLog
        assertEquals((redone + match).map { it.name }.sorted(), h1.handed.sorted())
        assertEquals(listOf("hash-dump", "hash-match", "hash-miss"), l1.asked.sorted())
        val one = HashRecipe("one")
        for (f in redone + match + placeholder)
            assertEquals(one.versionFor(nes), entry(f).getInt("algorithmVersion"), f.name)
        // Said once at the start of a scan, so that a log shows what a
        // library was read again for.
        assertEquals(listOf("RomScanPipeline: verdicts are kept under the recipe ${one.global}"),
                     lines.filter { "recipe" in it })

        val h2 = EngineHasher("one"); val l2 = Answers()
        assertEquals(states, scan(h2, l2).states)
        assertEquals(emptyList(), h2.handed.toList(), "read again with nothing changed")
        assertEquals(emptyList(), l2.asked.toList(), "asked about again with nothing changed")

        val h3 = EngineHasher("two"); val l3 = Answers()
        val s3 = scan(h3, l3)
        assertEquals(states, s3.states)
        assertEquals(1, s3.cachedHits)
        assertEquals(redone.map { it.name }.sorted(), h3.handed.sorted(), "the files read again")
        assertEquals(listOf("hash-dump", "hash-miss"), l3.asked.sorted(), "the hashes asked about again")
        val two = HashRecipe("two")
        assertNotEquals(one.versionFor(nes), two.versionFor(nes))
        for (f in redone + match + placeholder)
            assertEquals(two.versionFor(nes), entry(f).getInt("algorithmVersion"), f.name)
    }

    // ── The console table ───────────────────────────────────────────────────

    // A row that is edited, a console's file given another console say,
    // changes what the files of its collections come to and of no others.
    // The second recipe here is this build's with the row of `nes` written
    // otherwise, which is what the build after such an edit would have.
    @Test fun `a changed table row redoes only that collection`() {
        val nes = CollectionRef.inferred("nes")
        val famicom = CollectionRef.inferred("famicom")
        val snes = CollectionRef.inferred("snes")
        val before = HashRecipe("none")
        val after = HashRecipe("none") { c -> if (rowOf(c) == rowOf(nes)) rowOf(c) + " ext[bin:7]" else rowOf(c) }

        // The row is in the number as this build's table has it, and it is
        // the row the two names give together: for a folder named for one
        // console of its collection's family that is the folder's, and an
        // edit to that row is the one that bears on the files in it.
        val inItsOwnFolder = CollectionRef("megadrive", "Mega Drive", "sega32x", null, emptySet())
        assertNotEquals(RcConsoles.describe(RcConsoles.row("megadrive")), rowOf(inItsOwnFolder))
        for (c in listOf(nes, inItsOwnFolder))
            assertEquals(HashRecipe("none") { rowOf(it) }.versionFor(c), before.versionFor(c), c.dirName)
        assertEquals(before.global, after.global)
        assertEquals(rowOf(nes), rowOf(famicom), "two names of one row")
        assertNotEquals(before.versionFor(nes), after.versionFor(nes))
        assertNotEquals(before.versionFor(famicom), after.versionFor(famicom))
        assertEquals(before.versionFor(snes), after.versionFor(snes))
        assertEquals(before.versionFor(null), after.versionFor(null))

        ScanLedger(ledgerFile, before).apply {
            record("/roms/nes/Miss.nes", nes, ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)
            record("/roms/snes/Miss.sfc", snes, ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }
        val same = ScanLedger(ledgerFile, before)
        assertNotNull(same.canSkip("/roms/nes/Miss.nes", nes, 10, 20, now = 1000))
        assertNotNull(same.canSkip("/roms/snes/Miss.sfc", snes, 10, 20, now = 1000))
        val edited = ScanLedger(ledgerFile, after)
        assertNull(edited.canSkip("/roms/nes/Miss.nes", nes, 10, 20, now = 1000), "the collection whose row changed")
        assertNotNull(edited.canSkip("/roms/snes/Miss.sfc", snes, 10, 20, now = 1000), "a collection of another row")
    }

    // ── The file's collection ───────────────────────────────────────────────

    // A verdict is on a file of the collection it was in. The folder may be
    // given to another collection, or its collection told of one more
    // extension, by an edit to a metafile that leaves the file as it was,
    // size and date. The file is then read again, and no more than once.
    @Test fun `a verdict does not survive its file moving to another collection`(): Unit = runBlocking {
        collection("cartridges", "Nintendo", "nes")
        rom("cartridges", "Miss.nes", "hash-miss")

        suspend fun handed(): List<String> = EngineHasher("one").also { scan(it, Answers()) }.handed.toList()
        assertEquals(listOf("Miss.nes"), handed())
        assertEquals(emptyList(), handed())

        collection("cartridges", "Nintendo", "nes", extensions = "nes, unf")
        assertEquals(listOf("Miss.nes"), handed(), "its collection declares another list of extensions")
        assertEquals(emptyList(), handed())

        collection("cartridges", "Super Nintendo", "snes", extensions = "nes, unf")
        assertEquals(listOf("Miss.nes"), handed(), "its folder is another collection's")
        assertEquals(emptyList(), handed())

        // Each of the three things a collection is in the number by, with
        // the ledger asked as the scan asks it. The folder's name is one a
        // scan cannot change without the path changing too.
        val kept = CollectionRef("megadrive", "Mega Drive", "sega32x", File("/roms/sega32x"), setOf("32x", "md"))
        val recipe = HashRecipe("one")
        ScanLedger(ledgerFile, recipe).apply {
            record("/roms/sega32x/Miss.32x", kept, ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }
        val ledger = ScanLedger(ledgerFile, recipe)
        fun stands(c: CollectionRef) = ledger.canSkip("/roms/sega32x/Miss.32x", c, 10, 20, now = 1000) != null
        assertTrue(stands(kept))
        assertTrue(!stands(kept.copy(shortName = "sega32x")), "another short name")
        assertTrue(!stands(kept.copy(dirName = "megadrive")), "another folder")
        assertTrue(!stands(kept.copy(declaredExtensions = setOf("32x"))), "another list of extensions")
        // The short name by itself, where the folder is the same and the row
        // is the same, as with the two names the table has for one console.
        // It is the name a hasher is told, which decides what in an archive
        // is the game.
        val asNes = CollectionRef("nes", "Nintendo", "cartridges", File("/roms/cartridges"), emptySet())
        val asFamicom = asNes.copy(shortName = "famicom")
        assertEquals(rowOf(asNes), rowOf(asFamicom))
        assertNotEquals(recipe.versionFor(asNes), recipe.versionFor(asFamicom), "another short name of the same row")
        // The same list written in another order is the same list. Asked
        // of a recipe that has not met the collection, as the next scan's
        // has not: one that has met it remembers the number, and to that
        // memory two sets are one whatever their order.
        val reordered = kept.copy(declaredExtensions = linkedSetOf("md", "32x"))
        assertEquals(listOf("32x", "md"), kept.declaredExtensions.toList())
        assertEquals(listOf("md", "32x"), reordered.declaredExtensions.toList())
        assertEquals(recipe.versionFor(kept), HashRecipe("one").versionFor(reordered))
        // What the collection is called in full and where its folder lies
        // decide nothing about a file, and are not in the number: a
        // library moved to another disk keeps what was found out of it.
        assertTrue(stands(kept.copy(name = "Sega Mega Drive", directory = File("/other/sega32x"))))
        assertEquals(recipe.versionFor(CollectionRef.inferred("nes")),
                     recipe.versionFor(CollectionRef("nes", "Nintendo", "nes", File("/roms/nes"), emptySet())),
                     "a metafile that says what the folder's name said")
    }

    // A verdict is kept at the place in the scan that reaches it, and there
    // are a dozen such places. Each has to keep it under the number the
    // next scan will ask by, which is that of the file's collection. Where
    // a folder is called what its collection is and declares nothing, a
    // collection made up from the short name alone has the same number, and
    // a place that kept its verdict under that one would not show. So this
    // collection is declared in a folder of another name, with an extension
    // of its own. Kept under any other number than its collection's, a
    // verdict is never found standing: the file is read again at every
    // scan, and asked about again where it gives a hash.
    @Test fun `every verdict is kept under its collection's number and stands at the next scan`(): Unit = runBlocking {
        collection("cartridges", "Nintendo", "nes", extensions = "nes, unf")
        val declared = CollectionRef("nes", "Nintendo", "cartridges", null, setOf("nes", "unf"))
        val files = listOf(
            rom("cartridges", "Match.nes", "hash-match"), rom("cartridges", "Miss.nes", "hash-miss"),
            rom("cartridges", "Dump.nes", "hash-dump"), rom("cartridges", "Refused.nes", "REFUSED"),
            rom("cartridges", "Several.nes", "SEVERAL"), rom("cartridges", "Nothing.nes", "NOTHING"),
            rom("cartridges", "Unread.nes", "UNREAD"), stub("cartridges", "Not Here Yet.nes"),
            File(romRoot, "cartridges/Empty.nes").apply { writeText("") },
            // Turned away by its name before it is read, in a collection that can be hashed.
            rom("cartridges", "Packed.chd", "never read"))
        val states = mapOf(
            ScanLedger.State.MATCHED to 1, ScanLedger.State.NOT_FOUND to 1, ScanLedger.State.KNOWN_UNSUPPORTED to 1,
            ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.AMBIGUOUS_ARCHIVE to 1,
            ScanLedger.State.NO_PLAYABLE_ENTRY to 1, ScanLedger.State.UNSUPPORTED_FORMAT to 2,
            ScanLedger.State.PLACEHOLDER to 2)

        val h1 = EngineHasher("one")
        assertEquals(states, scan(h1, Answers()).states)
        assertEquals(7, h1.handed.size)
        val recipe = HashRecipe("one")
        assertNotEquals(recipe.versionFor(CollectionRef.inferred("nes")), recipe.versionFor(declared))
        assertNotEquals(recipe.versionFor(CollectionRef.inferred("cartridges")), recipe.versionFor(declared))
        for (f in files) assertEquals(recipe.versionFor(declared), entry(f).getInt("algorithmVersion"), f.name)

        val h2 = EngineHasher("one"); val l2 = Answers()
        assertEquals(states, scan(h2, l2).states)
        assertEquals(emptyList(), h2.handed.toList(), "read again with nothing changed")
        assertEquals(emptyList(), l2.asked.toList(), "asked about again with nothing changed")
        for (f in files) assertEquals(recipe.versionFor(declared), entry(f).getInt("algorithmVersion"), f.name)
    }

    // ── The matches ─────────────────────────────────────────────────────────

    // A match is found through its metadata file before the ledger is
    // asked, by the file's name, size and date. So none of the changes
    // above has it read again: what a new number costs is the files that
    // did not match. Its entry in the ledger is written again under the
    // number of the day all the same.
    @Test fun `a matched file is still skipped with no hash after any of these`(): Unit = runBlocking {
        collection("nes", "Nintendo", "nes")
        val match = rom("nes", "Match.nes", "hash-match")
        val folder = File(romRoot, "nes").absoluteFile
        fun declared(vararg extensions: String) = CollectionRef("nes", "Nintendo", "nes", folder, extensions.toSet())

        val first = EngineHasher("one")
        assertEquals(1, scan(first, Answers()).newEntries)
        assertEquals(listOf("Match.nes"), first.handed.toList())
        assertEquals(HashRecipe("one").versionFor(declared()), entry(match).getInt("algorithmVersion"))

        // Another engine.
        val second = EngineHasher("two"); val asked = Answers()
        assertEquals(1, scan(second, asked).cachedHits)
        assertEquals(HashRecipe("two").versionFor(declared()), entry(match).getInt("algorithmVersion"))

        // Another description of its collection.
        collection("nes", "Nintendo", "nes", extensions = "nes, unf")
        assertEquals(1, scan(second, asked).cachedHits)
        assertEquals(HashRecipe("two").versionFor(declared("nes", "unf")), entry(match).getInt("algorithmVersion"))
        assertNotEquals(HashRecipe("two").versionFor(declared()), HashRecipe("two").versionFor(declared("nes", "unf")))

        assertEquals(emptyList(), second.handed.toList(), "the match was read again")
        assertEquals(emptyList(), asked.asked.toList(), "the match was asked about again")
        assertEquals("MATCHED", entry(match).getString("state"))
    }

    // ── The numbers of before ───────────────────────────────────────────────

    // 1 to 4 were chosen by hand, and 0 is what an entry with no number
    // reads as. A ledger holds verdicts under those from the builds that
    // wrote them, and a number worked out today that came to one of them
    // would have every such verdict believed.
    @Test fun `the derived number is never one a person chose`() {
        assertEquals(4, HashRecipe.LAST_BY_HAND)
        assertEquals(listOf(5, 6, 7, 8, 9), (0L..4L).map { HashRecipe.fold(it) })
        assertEquals(listOf(5, 6, 7, 8, 9), (0L..4L).map { HashRecipe.fold(0x80000000L + it) },
                     "the top bit is dropped first")
        assertEquals(5, HashRecipe.fold(5))
        assertEquals(0x7fffffff, HashRecipe.fold(0xffffffffL))
        assertEquals(0x12345678, HashRecipe.fold(0x92345678L))

        val collections = listOf(null) + listOf("nes", "snes", "megadrive", "psx", "arcade", "switch", "", "no such console")
            .map { CollectionRef.inferred(it) }
        val numbers = HashSet<Int>()
        for (engine in listOf("none", "12.5.0+pb6", "12.3.0+pb6", "")) {
            val recipe = HashRecipe(engine)
            for (c in collections) {
                val number = recipe.versionFor(c)
                assertTrue(number > HashRecipe.LAST_BY_HAND, "$engine, ${c?.shortName}: $number")
                assertEquals(number, HashRecipe(engine).versionFor(c), "the same recipe, worked out twice")
                numbers += number
            }
        }
        // Every engine and every collection its own number: none is left out of what is hashed.
        assertEquals(4 * collections.size, numbers.size)
    }

    // What the first scan of this build meets in a library scanned by the
    // one before it: a ledger whose every entry is under 4, the last number
    // set by hand. It is read, and none of its verdicts is believed. So a
    // miss is asked about once more; a dump kept in the old form, as a miss
    // under the number RetroAchievements sent, comes back under the game's
    // own id; a stub kept as a miss is a placeholder from here on, with
    // nothing asked; and the match is not read. The scan after that asks
    // nothing. An entry of a folder this scan was not given stays as it
    // was, for the scan that is.
    @Test fun `a ledger at version 4 loads and its misses are asked about once`(): Unit = runBlocking {
        val match = rom("nes", "Match.nes", "hash-match")
        val miss = rom("nes", "Miss.nes", "hash-miss")
        val dump = rom("nes", "Dump.nes", "hash-dump")
        val stub = stub("nes", "Not Here Yet.nes")
        val elsewhere = Files.createTempFile("recipe-elsewhere", ".nes").toFile()
        try {
            scan(EngineHasher("none"), Answers())
            val nes = HashRecipe("none").versionFor(CollectionRef.inferred("nes"))
            assertEquals(nes, entry(miss).getInt("algorithmVersion"))

            val old = JSONObject(ledgerFile.readText()).put("algorithmVersion", 4)
            val entries = old.getJSONObject("entries")
            entries.keys().forEach { entries.getJSONObject(it).put("algorithmVersion", 4) }
            entries.getJSONObject(dump.canonicalPath).put("state", "NOT_FOUND").put("gameId", 1_100_001_487)
                .put("detail", "RetroAchievements knows this dump only by virtual id 1100001487: game 1487, untested")
            entries.getJSONObject(stub.canonicalPath).put("state", "NOT_FOUND").remove("detail")
            entries.put(elsewhere.canonicalPath, JSONObject(entries.getJSONObject(miss.canonicalPath).toString()))
            ledgerFile.writeText(old.toString())

            val h = EngineHasher("none"); val l = Answers()
            val s = scan(h, l)
            assertEquals(listOf("Dump.nes", "Miss.nes"), h.handed.sorted(), "the files read")
            assertEquals(listOf("hash-dump", "hash-miss"), l.asked.sorted(), "the hashes asked about")
            assertEquals(mapOf("newEntries" to 0, "cachedHits" to 1, "skippedPlatforms" to 1, "unmatched" to 1,
                               "incompatible" to 1, "hashFailed" to 0, "failedLookups" to 0),
                         mapOf("newEntries" to s.newEntries, "cachedHits" to s.cachedHits,
                               "skippedPlatforms" to s.skippedPlatforms, "unmatched" to s.unmatched,
                               "incompatible" to s.incompatible, "hashFailed" to s.hashFailed,
                               "failedLookups" to s.failedLookups))
            assertEquals(listOf("MATCHED", "NOT_FOUND", "KNOWN_UNSUPPORTED", "PLACEHOLDER"),
                         listOf(match, miss, dump, stub).map { entry(it).getString("state") })
            assertEquals(1487, entry(dump).getInt("gameId"))
            for (f in listOf(match, miss, dump, stub)) assertEquals(nes, entry(f).getInt("algorithmVersion"), f.name)
            assertEquals(HashRecipe("none").versionFor(null), JSONObject(ledgerFile.readText()).getInt("algorithmVersion"))
            assertEquals(4, entry(elsewhere).getInt("algorithmVersion"), "an entry of a folder that was not scanned")

            val h2 = EngineHasher("none"); val l2 = Answers()
            scan(h2, l2)
            assertEquals(emptyList(), h2.handed.toList(), "read a second time")
            assertEquals(emptyList(), l2.asked.toList(), "asked about a second time")
        } finally {
            elsewhere.delete()
        }
    }

    // ── The tables ──────────────────────────────────────────────────────────

    // The line every number is made from, for a hasher that names no
    // engine. Each table is in it by a checksum of its content, so this
    // fails whenever one of them is edited, and that is its use: it shows
    // the edit will have every library read again, the files that did not
    // match, at the first scan of the build that carries it.
    @Test fun `the recipe changes when a table does`() {
        assertEquals(
            "rules=6;rc=none;ext=750286ba;con=f6451701;sel=5dbc3d88;disc=bc396177;deny=2e1b62a9;" +
            "containers=wbfs0,chd0;ph=1,512,24d0ed6a",
            HashRecipe("none").global,
            "A table a file is judged by has changed, and with it the number every verdict is kept under: " +
            "the next scan of every library reads again each file that did not match. If that is meant, " +
            "write the new line here. Do NOT raise 'rules' for it: the checksum has already changed the " +
            "number. 'rules' is for a change to the code that judges, which no table shows.")
    }

    // What the `disc` part is the checksum of, written out. Three limits
    // decide what a sheet or a playlist comes to, and the third was not in
    // it: how far into a playlist its first entry is looked for. A playlist
    // whose entry lies past that is refused, and kept as refused, so the
    // limit moved would have left such verdicts standing under a number
    // that said nothing had changed.
    @Test fun `the three limits a sheet and a playlist are read by are in the recipe`() {
        val text = "read[cue,gdi] unread[ccd,mds,toc]" +
                   " sheet<=${DescriptorSet.SHEET_LIMIT} name<=${DescriptorSet.NAME_LIMIT}" +
                   " playlist<=${PlaylistReader.READ_LIMIT}"
        val checksum = java.util.zip.CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value
        assertTrue("disc=" + checksum.toString(16).padStart(8, '0') in HashRecipe("none").global.split(';'),
                   "the disc part of ${HashRecipe("none").global} is not the checksum of: $text")
    }
}
