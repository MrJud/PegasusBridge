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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Why a scan did what it did, and what the next one will do.
 *
 * Before the ledger, the only record a scan left was a metadata file per match.
 * A ROM the database does not have, one that would not hash, an archive nobody
 * could resolve and a lookup the API refused all left the same trace — none —
 * and the absence was then read as "not scanned yet".
 *
 * The distinction that has to survive: NOT_FOUND is an answer and may be
 * remembered; API_RETRY is the source declining to answer and must never be.
 * Confusing the two is what cost a whole RetroAchievements run.
 */
class ScanLedgerTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("ledger-data").toFile()
        romRoot  = Files.createTempDirectory("ledger-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            val t = File(path).readText().removeSuffix(NUL).trim()
            if (t == "UNHASHABLE") return null
            return HashResult(t, 7, fileMd5 = "md5-$t", fileCrc32 = "crc-$t")
        }
    }

    /** Says no to everything. A verdict, not a refusal. */
    private class SaysNo : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): LookupOutcome {
            calls.incrementAndGet(); return GameMetadata(gameId = 0).asOutcome()
        }
    }

    /** Never answers. A refusal, not a verdict. */
    private class NeverAnswers : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): LookupOutcome {
            calls.incrementAndGet(); return null.asOutcome()
        }
    }

    private fun rom(platform: String, name: String, content: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(romText(content)) }
    }

    /** Two ROMs of the platform in one zip, neither named after it: nobody can pick. */
    private fun ambiguousZip(platform: String, name: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).also { zip ->
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("Sonic 1.md")); z.write(ByteArray(512)); z.closeEntry()
                z.putNextEntry(ZipEntry("Sonic 2.md")); z.write(ByteArray(1024)); z.closeEntry()
            }
        }
    }

    private fun pipeline(h: RomHasher, l: RaHashLookup) =
        RomScanPipeline(paths, h, l, throttleMs = { 0L })

    private fun ledgerJson(): JSONObject =
        JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText())

    private fun ledgerEntry(f: File): JSONObject? =
        ledgerJson().getJSONObject("entries").optJSONObject(f.canonicalPath)

    // ── A verdict is remembered ─────────────────────────────────────────────

    @Test fun `a rom the source does not have is recorded as not found`(): Unit = runBlocking {
        val f = rom("nes", "Homebrew.nes", "hash-unknown")
        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.NOT_FOUND])
        assertEquals("NOT_FOUND", ledgerEntry(f)!!.getString("state"))
    }

    @Test fun `a recorded miss is not asked about again on the next scan`(): Unit = runBlocking {
        rom("nes", "Homebrew.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val h2 = ContentHasher(); val l2 = SaysNo()
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(0, l2.calls.get(), "a miss inside its TTL cost a network call")
        assertEquals(0, h2.calls.get(), "a miss inside its TTL cost a hash")
        assertEquals(1, s2.states[ScanLedger.State.NOT_FOUND])
    }

    // The exact bug this project has already paid for once: a refusal cached as
    // a verdict writes the game off, and an incremental rescan never asks again.
    @Test fun `a source that did not answer is asked again next time`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-x")
        pipeline(ContentHasher(), NeverAnswers()).scan(listOf(romRoot.absolutePath))
        assertEquals("API_RETRY", ledgerEntry(f)!!.getString("state"))

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a refusal was cached as if it were an answer")
    }

    @Test fun `an unhashable file is recorded and retried rather than written off`(): Unit = runBlocking {
        val f = rom("nes", "Broken.nes", "UNHASHABLE")
        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.HASH_FAILED])
        assertEquals("HASH_FAILED", ledgerEntry(f)!!.getString("state"))

        // The file may be replaced by a good dump; a hash failure keeps no TTL.
        val h2 = ContentHasher()
        pipeline(h2, SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, h2.calls.get(), "a hash failure must be retried")
    }

    @Test fun `an unsupported platform is recorded without any io`(): Unit = runBlocking {
        val f = rom("switch", "Something.nes", "hash-x")
        val h = ContentHasher()
        val s = pipeline(h, SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.UNSUPPORTED])
        assertEquals(0, h.calls.get())
        assertEquals("UNSUPPORTED", ledgerEntry(f)!!.getString("state"))
    }

    @Test fun `a match is recorded with the game it matched`(): Unit = runBlocking {
        val f = rom("nes", "Contra (USA).nes", "hash-ctra")
        val found = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1447, "Contra", "NES", "/i.png", 40).asOutcome()
        }
        val s = pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.MATCHED])
        val e = ledgerEntry(f)!!
        assertEquals("MATCHED", e.getString("state"))
        assertEquals(1447, e.getInt("gameId"))
    }

    // ── An archive nobody can resolve ───────────────────────────────────────

    @Test fun `an ambiguous archive is a diagnostic and not a miss`(): Unit = runBlocking {
        ambiguousZip("megadrive", "Sonic Collection.zip")
        val l = SaysNo()
        val s = pipeline(ArchiveAwareHasher(ContentHasher(), File(dataRoot, "tmp")), l)
            .scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.AMBIGUOUS_ARCHIVE])
        assertNull(s.states[ScanLedger.State.NOT_FOUND],
                   "an archive nobody could resolve was recorded as a game the database lacks")
        assertEquals(0, l.calls.get(), "no lookup should be spent on a digest of nothing")
        assertEquals(1, s.ambiguousArchives.size)
        assertTrue(s.ambiguousArchives.first().second.contains("Sonic 1.md"),
                   "the candidates must be named, or nobody knows which file to open")
    }

    // ── A file the hasher knows it cannot hash ──────────────────────────────

    // A disc in an archive whose sheet is a .ccd, which rcheevos does not
    // read. As HASH_FAILED such a file was retried on every scan, and in a
    // solid 7z each retry decompressed the disc.
    @Test fun `a disc in an archive whose sheet nobody reads is kept as unhashable, not retried every scan`(): Unit = runBlocking {
        val dir = File(romRoot, "psx").apply { mkdirs() }
        val zip = File(dir, "Disc.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Disc.ccd")); z.write("[CloneCD]".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Disc.img")); z.write(ByteArray(4096)); z.closeEntry()
        }
        val opened = AtomicInteger()
        val archives = object : RomHasher {
            val real = ArchiveAwareHasher(ContentHasher(), File(dataRoot, "tmp"))
            override fun hash(path: String) = real.hash(path)
            override fun hashDetailed(path: String, platform: String): HashOutcome {
                opened.incrementAndGet()
                return real.hashDetailed(path, platform)
            }
        }

        val s = pipeline(archives, SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s.states[ScanLedger.State.UNHASHABLE])
        assertNull(s.states[ScanLedger.State.HASH_FAILED])
        val e = ledgerEntry(zip)!!
        assertEquals("UNHASHABLE", e.getString("state"))
        assertEquals("'Disc.ccd' in the archive is a .ccd sheet, which rcheevos does not read", e.getString("detail"))

        val l2 = SaysNo()
        val s2 = pipeline(archives, l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, opened.get(), "the archive was opened again inside the verdict's TTL")
        assertEquals(0, l2.calls.get())
        assertEquals(1, s2.states[ScanLedger.State.UNHASHABLE])

        // Past its TTL it is decided again.
        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val j = JSONObject(file.readText())
        val entries = j.getJSONObject("entries")
        val old = BridgePaths.epochSeconds() - ScanLedger.State.UNHASHABLE.retryAfterSeconds - 60
        entries.keys().forEach { k -> entries.getJSONObject(k).put("checkedAt", old) }
        file.writeText(j.toString())
        pipeline(archives, SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(2, opened.get())
    }

    // ── A dump the source knows and does not support ────────────────────────

    // Kept for thirty days, where a miss is kept for fourteen: at twenty days a
    // miss is asked about again and this is not.
    @Test fun `a dump known and not supported stands past a miss's fourteen days, and not past thirty`(): Unit = runBlocking {
        rom("nes", "Dump.nes", "hash-dump")
        rom("nes", "Homebrew.nes", "hash-unknown")
        class Answers : RaHashLookup {
            val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            override suspend fun lookup(hash: String): LookupOutcome {
                asked += hash
                return GameMetadata(gameId = if (hash == "hash-dump") 1_000_000_321 else 0).asOutcome()
            }
        }
        pipeline(ContentHasher(), Answers()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        fun age(days: Long) {
            val j = JSONObject(file.readText())
            val entries = j.getJSONObject("entries")
            val then = BridgePaths.epochSeconds() - days * 24 * 60 * 60
            entries.keys().forEach { k -> entries.getJSONObject(k).put("checkedAt", then) }
            file.writeText(j.toString())
        }

        age(20)
        val l2 = Answers()
        val s2 = pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(listOf("hash-unknown"), l2.asked.toList())
        assertEquals(1, s2.incompatible)
        assertEquals(1, s2.states[ScanLedger.State.KNOWN_UNSUPPORTED])

        age(31)
        val l3 = Answers()
        pipeline(ContentHasher(), l3).scan(listOf(romRoot.absolutePath))
        assertEquals(listOf("hash-dump", "hash-unknown"), l3.asked.sorted())
    }

    // ── Invalidation ────────────────────────────────────────────────────────

    @Test fun `a replaced file is asked about again despite a stored verdict`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        f.writeText(romText("hash-something-else-entirely"))
        f.setLastModified(f.lastModified() + 60_000)

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "the replaced file kept the old verdict")
    }

    // What makes a policy change take effect on a library already scanned. Without
    // it, every decision the old archive rule made would be preserved forever.
    // The number is worked out from what a verdict is reached with, so one off
    // the number the file has today is the number of some other recipe: it is
    // not held to be older or newer, only to be the same or not.
    @Test fun `a verdict from an older algorithm version is redone`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val j = JSONObject(file.readText())
        val entries = j.getJSONObject("entries")
        val today = HashRecipe("none").versionFor(CollectionRef.inferred("nes"))
        entries.keys().forEach { k ->
            assertEquals(today, entries.getJSONObject(k).getInt("algorithmVersion"))
            entries.getJSONObject(k).put("algorithmVersion", today - 1)
        }
        file.writeText(j.toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a verdict from the old rule was kept")
    }

    @Test fun `a miss older than its ttl is asked about again`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val j = JSONObject(file.readText())
        val entries = j.getJSONObject("entries")
        val old = BridgePaths.epochSeconds() - 30L * 24 * 60 * 60
        entries.keys().forEach { k -> entries.getJSONObject(k).put("checkedAt", old) }
        file.writeText(j.toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a thirty-day-old miss was still trusted")
    }

    @Test fun `entries for files that are gone are dropped`(): Unit = runBlocking {
        val a = rom("nes", "A.nes", "hash-a")
        rom("nes", "B.nes", "hash-b")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(2, ledgerJson().getInt("count"))

        a.delete()
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, ledgerJson().getInt("count"), "the deleted file's entry lived on")
    }

    // A theme can scan one collection at a time. Keeping only what the last scan
    // found threw away the other collections' misses, and the next full scan
    // hashed and asked about every one of them again.
    //
    // What the ledger keeps is not what the scan reports, though: the ambiguous
    // archive under megadrive stays a verdict, but a scan of nes alone that named
    // it would hand the theme, on every round, an archive to open from a
    // collection it never asked about, and could push its own past the fifty
    // that /jobs lists.
    @Test fun `a scan of one root keeps the verdicts of the others`(): Unit = runBlocking {
        rom("nes", "A.nes", "hash-a")
        val other = rom("snes", "B.sfc", "hash-b")
        val archive = ambiguousZip("megadrive", "Sonic Collection.zip")
        val tmp = File(dataRoot, "tmp")
        val s1 = pipeline(ArchiveAwareHasher(ContentHasher(), tmp), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(listOf(archive.canonicalPath), s1.ambiguousArchives.map { it.first })

        val s2 = pipeline(ArchiveAwareHasher(ContentHasher(), tmp), SaysNo())
            .scan(listOf(File(romRoot, "nes").absolutePath))
        assertEquals(1, s2.total)
        assertEquals("NOT_FOUND", ledgerEntry(other)!!.getString("state"), "the other root's miss was dropped")
        assertEquals("AMBIGUOUS_ARCHIVE", ledgerEntry(archive)!!.getString("state"))
        assertEquals(emptyList(), s2.ambiguousArchives, "a scan of nes reported an archive under megadrive")

        val h3 = ContentHasher(); val l3 = SaysNo()
        val s3 = pipeline(ArchiveAwareHasher(h3, tmp), l3).scan(listOf(romRoot.absolutePath))
        assertEquals(0, h3.calls.get(), "a miss inside its TTL was hashed again")
        assertEquals(0, l3.calls.get(), "a miss inside its TTL was asked about again")
        assertEquals(2, s3.states[ScanLedger.State.NOT_FOUND])
        // Skipped inside its TTL rather than decided again, and still this scan's
        // to report, since this scan counted it.
        assertEquals(1, s3.states[ScanLedger.State.AMBIGUOUS_ARCHIVE])
        assertEquals(listOf(archive.canonicalPath), s3.ambiguousArchives.map { it.first },
                     "an archive skipped on its standing verdict was counted but not named")
    }

    // What the ledger still drops: a file that is gone, even outside the roots
    // scanned, and one under them that the scan no longer counts as a ROM.
    @Test fun `a scan of one root still drops what is gone or no longer a rom`(): Unit = runBlocking {
        rom("nes", "A.nes", "hash-a")
        val notRom = rom("nes", "Notes.bin", "hash-n")
        val gone = rom("snes", "B.sfc", "hash-b")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(3, ledgerJson().getInt("count"))

        gone.delete()
        RomScanPipeline(paths, ContentHasher(), SaysNo(), throttleMs = { 0L }, extensionsFor = { setOf("nes") })
            .scan(listOf(File(romRoot, "nes").absolutePath))

        assertNull(ledgerEntry(gone), "a deleted file outside the scanned root kept its entry")
        assertNull(ledgerEntry(notRom), "a file the scan no longer counts kept its entry")
        assertEquals(1, ledgerJson().getInt("count"))
    }

    @Test fun `a corrupt ledger is started again rather than failing the scan`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        paths.cache.mkdirs()
        File(paths.cache, ScanLedger.FILE_NAME).writeText("{ not json at all")

        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s.total)
        assertEquals(1, s.states[ScanLedger.State.NOT_FOUND])
    }

    @Test fun `a ledger from an older schema is discarded`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        file.writeText(JSONObject(file.readText()).put("schemaVersion", 99).toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a ledger from an unknown schema was trusted")
    }

    // The ledger gains a state now and then, and a ledger outlives the build
    // that wrote it in both directions: a build put back over a later one
    // finds entries in a state it has no name for. Such an entry is one file
    // to ask about again. Taken for a ledger that cannot be read, it would
    // cost every other verdict in the file, and every miss of the library
    // would be read and asked about once more.
    @Test fun `an entry in a state this build has no name for costs that file and no other`(): Unit = runBlocking {
        val later = rom("nes", "Dump.nes", "hash-dump")
        rom("nes", "Homebrew.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val json = JSONObject(file.readText())
        json.getJSONObject("entries").getJSONObject(later.canonicalPath).put("state", "A_STATE_OF_A_LATER_BUILD")
        file.writeText(json.toString())

        val h2 = ContentHasher(); val l2 = SaysNo()
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, h2.calls.get(), "the file whose state could not be read, and that one alone")
        assertEquals(1, l2.calls.get(), "the miss beside it was asked about again")
        assertEquals(mapOf(ScanLedger.State.NOT_FOUND to 2), s2.states)
        assertEquals("NOT_FOUND", ledgerEntry(later)!!.getString("state"), "what this build made of the file")
    }

    // Kept for ninety days, as a platform nobody covers is, to the second,
    // and read back under its name by the ledger that comes after.
    @Test fun `a format nobody reads is a verdict kept for a season`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val path = "/roms/ps2/Disc.cso"
        val ninetyDays = 90L * 24 * 60 * 60
        assertEquals(ScanLedger.State.UNSUPPORTED.retryAfterSeconds, ScanLedger.State.UNSUPPORTED_FORMAT.retryAfterSeconds)
        val ps2 = CollectionRef.inferred("ps2")
        ScanLedger(file).apply {
            record(path, ps2, ScanLedger.State.UNSUPPORTED_FORMAT, 10, 20, now = 1000,
                   detail = ".cso is a format rcheevos does not read")
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        val kept = read.canSkip(path, ps2, 10, 20, now = 1000 + ninetyDays)
        assertEquals(ScanLedger.State.UNSUPPORTED_FORMAT, kept?.state)
        assertEquals(".cso is a format rcheevos does not read", kept?.detail)
        assertNull(read.canSkip(path, ps2, 10, 20, now = 1000 + ninetyDays + 1))
    }

    // But for one such verdict, which does not stand a day: that the build
    // had no reader for a format, kept by a build before the one that has
    // it. For the packed disc itself the number says as much. Said of an
    // archive's entry or of a playlist's first file it is kept for the
    // archive and for the playlist, under a number no reader is in, and
    // only its words tell it from a verdict that is still true. They are
    // the last words of the detail, under that state, of an extension that
    // has a reader today: anything else stands as it did.
    @Test fun `a verdict that a build had no reader does not stand once there is one`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val ps2 = CollectionRef.inferred("ps2")
        val unsupported = ScanLedger.State.UNSUPPORTED_FORMAT
        val noReader = "is a format this build has no reader for"
        val kept = listOf(
            Triple("/roms/ps2/Packed.zip", unsupported, "'Packed.chd' in the archive: .chd $noReader"),
            Triple("/roms/ps2/Listed.m3u", unsupported, "Disc 1.chd, the first file the playlist names: .chd $noReader"),
            Triple("/roms/ps2/Wii.m3u", unsupported, "Disc 1.wbfs, the first file the playlist names: .wbfs $noReader"),
            Triple("/roms/ps2/Bare.zip", unsupported, ".chd $noReader"),
            // An extension that has no reader, the words that are true of a
            // .wbfs inside an archive, the old words with others after
            // them, and the old words under another state.
            Triple("/roms/ps2/Other.zip", unsupported, "'Other.xyz' in the archive: .xyz $noReader"),
            Triple("/roms/ps2/Wii.zip", unsupported, "'Disc.wbfs' in the archive: a .wbfs inside an archive is not unpacked"),
            Triple("/roms/ps2/Quoted.zip", unsupported, "'.chd $noReader.txt' in the archive: a .txt file is not a game"),
            Triple("/roms/ps2/Named.zip", ScanLedger.State.NO_PLAYABLE_ENTRY,
                   "nothing in the archive is a game of this collection: .chd $noReader"))
        ScanLedger(file).apply {
            for ((path, state, detail) in kept) record(path, ps2, state, 10, 20, now = 1000, detail = detail)
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        assertEquals(listOf(false, false, false, false, true, true, true, true),
                     kept.map { (path, _, _) -> read.canSkip(path, ps2, 10, 20, now = 1001) != null },
                     "which of them stand: " + kept.map { it.first.substringAfterLast('/') })
        // Nothing is taken out of the ledger for it: the verdict is there
        // for whoever reads it, until a scan has written another.
        assertEquals(kept.map { it.third }, kept.map { read.entry(it.first)?.detail })
    }

    // The builds that read a .chd as a disc of nine consoles turned one in
    // a PC-FX collection away unread, and said so in one sentence. This
    // build reads it. A loose file is under the reader's new number; an
    // archive's entry and a playlist's first file are under numbers no
    // reader is in, and are known by that sentence at the end of the
    // detail. The same words of another console are still true, in the
    // number they are said with today, and stand.
    @Test fun `a PC-FX chd turned away inside an archive or a playlist is taken again once`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val pcfx = CollectionRef.inferred("pcfx")
        val unsupported = ScanLedger.State.UNSUPPORTED_FORMAT
        fun nine(console: Int) = "a .chd is read as a disc of nine consoles, and console $console is not one of them"
        assertEquals(nine(49), ScanLedger.LEFT_BY_NINE)
        val kept = listOf(
            Triple("/roms/pcfx/Packed.zip", unsupported, "'Packed.chd' in the archive: ${nine(49)}"),
            Triple("/roms/pcfx/Listed.m3u", unsupported, "Disc 1.chd, the first file the playlist names: ${nine(49)}"),
            Triple("/roms/pcfx/Loose.chd", unsupported, nine(49)),
            // Another console, the words this build writes, the old words
            // with others after them, and the old words under another state.
            Triple("/roms/pcfx/Cartridge.zip", unsupported, "'Cartridge.chd' in the archive: ${nine(7)}"),
            Triple("/roms/pcfx/Jaguar.zip", unsupported,
                   "'Jaguar.chd' in the archive: a .chd is read as a disc of ten consoles, and console 77 is not one of them"),
            Triple("/roms/pcfx/Quoted.zip", unsupported, "'${nine(49)}.txt' in the archive: a .txt file is not a game"),
            Triple("/roms/pcfx/Named.zip", ScanLedger.State.NO_PLAYABLE_ENTRY,
                   "nothing in the archive is a game of this collection: ${nine(49)}"))
        ScanLedger(file).apply {
            for ((path, state, detail) in kept) record(path, pcfx, state, 10, 20, now = 1000, detail = detail)
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        assertEquals(listOf(false, false, false, true, true, true, true),
                     kept.map { (path, _, _) -> read.canSkip(path, pcfx, 10, 20, now = 1001) != null },
                     "which of them stand: " + kept.map { it.first.substringAfterLast('/') })
        assertEquals(kept.map { it.third }, kept.map { read.entry(it.first)?.detail })
        // Once: what the scan then records of the file is this build's
        // words, and stands as any verdict does.
        read.record("/roms/pcfx/Packed.zip", pcfx, ScanLedger.State.NOT_FOUND, 10, 20, now = 1002, detail = "")
        assertEquals(ScanLedger.State.NOT_FOUND, read.canSkip("/roms/pcfx/Packed.zip", pcfx, 10, 20, now = 1003)?.state)
    }

    // The other thing those builds said of a PC-FX .chd: in a folder nothing
    // is known of rcheevos is left to guess, came to a hash under console
    // 49, and the hash was not kept. That failure is one that is kept, for
    // a month, and for an archive's entry and a playlist's first file under
    // a number no reader is in. This build keeps the hash, so the failure
    // goes, once, by its sentence and its state.
    @Test fun `a chd an earlier build guessed to be a PC-FX disc and did not keep is taken again once`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val attic = CollectionRef.inferred("attic")
        val unhashable = ScanLedger.State.UNHASHABLE
        fun nine(console: Int) = "rcheevos takes this .chd for console $console, which is not one of the nine a .chd is read as"
        assertEquals(nine(49), ScanLedger.GUESSED_BY_NINE)
        val kept = listOf(
            Triple("/roms/attic/Packed.zip", unhashable, "the hasher could not read 'Packed.chd': ${nine(49)}"),
            Triple("/roms/attic/Listed.m3u", unhashable, "the hasher could not read Disc 1.chd: ${nine(49)}"),
            // Another console, the words this build writes, the old words
            // with others after them, and the old words under another state.
            Triple("/roms/attic/Other.zip", unhashable, "the hasher could not read 'Other.chd': ${nine(77)}"),
            Triple("/roms/attic/Jaguar.zip", unhashable, "the hasher could not read 'Jaguar.chd': rcheevos takes " +
                   "this .chd for console 77, which is not one of the ten a .chd is read as"),
            Triple("/roms/attic/Longer.zip", unhashable, "the hasher could not read 'Longer.chd': ${nine(49)}; as console 9: no"),
            Triple("/roms/attic/Named.zip", ScanLedger.State.UNSUPPORTED_FORMAT, "'x' in the archive: ${nine(49)}"))
        ScanLedger(file).apply {
            for ((path, state, detail) in kept) record(path, attic, state, 10, 20, now = 1000, detail = detail)
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        assertEquals(listOf(false, false, true, true, true, true),
                     kept.map { (path, _, _) -> read.canSkip(path, attic, 10, 20, now = 1001) != null },
                     "which of them stand: " + kept.map { it.first.substringAfterLast('/') })
        read.record("/roms/attic/Packed.zip", attic, ScanLedger.State.NOT_FOUND, 10, 20, now = 1002, detail = "")
        assertEquals(ScanLedger.State.NOT_FOUND, read.canSkip("/roms/attic/Packed.zip", attic, 10, 20, now = 1003)?.state)
    }

    // Kept for thirty days, as a file the hasher cannot hash is, and not
    // tried again at every scan as a file that failed is.
    @Test fun `an archive with no game in it is a verdict kept for a month`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val path = "/roms/ps2/Patch.zip"
        val thirtyDays = 30L * 24 * 60 * 60
        assertTrue(ScanLedger.State.NO_PLAYABLE_ENTRY.cacheable)
        assertEquals(ScanLedger.State.UNHASHABLE.retryAfterSeconds, ScanLedger.State.NO_PLAYABLE_ENTRY.retryAfterSeconds)
        val ps2 = CollectionRef.inferred("ps2")
        ScanLedger(file).apply {
            record(path, ps2, ScanLedger.State.NO_PLAYABLE_ENTRY, 10, 20, now = 1000,
                   detail = "nothing in the archive is a game of this collection: patch.7z")
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        val kept = read.canSkip(path, ps2, 10, 20, now = 1000 + thirtyDays)
        assertEquals(ScanLedger.State.NO_PLAYABLE_ENTRY, kept?.state)
        assertEquals("nothing in the archive is a game of this collection: patch.7z", kept?.detail)
        assertNull(read.canSkip(path, ps2, 10, 20, now = 1000 + thirtyDays + 1))
        assertNull(read.canSkip(path, ps2, 11, 20, now = 1000), "another archive under the same name")
    }

    // Kept for as long as the file is the one it was: the verdict was read
    // off the file's own bytes, and no day that passes changes those. The
    // game that takes the placeholder's place is another size or another
    // date, and is looked at as any new file is.
    @Test fun `a placeholder is a verdict that stands until the file is another`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME).apply { parentFile.mkdirs() }
        val path = "/roms/gbc/Not Here Yet.gbc"
        val aCentury = 100L * 365 * 24 * 60 * 60
        assertTrue(ScanLedger.State.PLACEHOLDER.cacheable)
        val gbc = CollectionRef.inferred("gbc")
        ScanLedger(file).apply {
            record(path, gbc, ScanLedger.State.PLACEHOLDER, 47, 20, now = 1000,
                   detail = "text file, 47 bytes: not a ROM image")
            save { f, text -> BridgePaths.writeAtomic(f, text) }
        }

        val read = ScanLedger(file)
        val kept = read.canSkip(path, gbc, 47, 20, now = 1000 + aCentury)
        assertEquals(ScanLedger.State.PLACEHOLDER, kept?.state)
        assertEquals("text file, 47 bytes: not a ROM image", kept?.detail)
        assertNull(read.canSkip(path, gbc, 262144, 20, now = 1000), "the game, under the placeholder's name")
        assertNull(read.canSkip(path, gbc, 47, 21, now = 1000), "another file of the same size")
    }

    // A cache hit already means the file matched; the ledger must agree rather
    // than reporting it as never having been looked at.
    @Test fun `a cached match is still counted as matched`(): Unit = runBlocking {
        rom("nes", "Contra (USA).nes", "hash-ctra")
        val found = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1447, "Contra", "NES", "/i.png", 40).asOutcome()
        }
        pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))

        val s2 = pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s2.cachedHits)
        assertEquals(1, s2.states[ScanLedger.State.MATCHED])
    }

    // A match found standing was written into the ledger again with no game,
    // over the entry the scan that matched it had written with one. A ledger
    // that does not say which game a file is cannot be what skips it.
    @Test fun `a cached match keeps the game it matched`(): Unit = runBlocking {
        val f = rom("nes", "Contra (USA).nes", "hash-ctra")
        val found = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1447, "Contra", "NES", "/i.png", 40).asOutcome()
        }
        pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))
        pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))

        assertEquals("MATCHED", ledgerEntry(f)!!.getString("state"))
        assertEquals(1447, ledgerEntry(f)!!.optInt("gameId"), "the rescan wrote the match again without its game")
    }

    // A scan saves in the middle only when it has something to write.
    @Test fun `the ledger knows what it has not saved yet`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val nes = CollectionRef.inferred("nes")
        val ledger = ScanLedger(file)
        assertFalse(ledger.dirty, "a new ledger has nothing to save")

        ledger.record("/roms/nes/a.nes", nes, ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)
        assertTrue(ledger.dirty)

        ledger.save { f, text -> BridgePaths.writeAtomic(f, text) }
        assertFalse(ledger.dirty, "what was recorded has been saved")

        // A verdict found standing is counted and not stored again.
        val standing = ledger.canSkip("/roms/nes/a.nes", nes, 10, 20, now = 1000)!!
        ledger.count("/roms/nes/a.nes", standing)
        assertFalse(ledger.dirty, "counting a verdict stored nothing")

        ledger.record("/roms/nes/b.nes", nes, ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)
        ledger.count("/roms/nes/a.nes", standing)
        assertTrue(ledger.dirty, "counting a verdict took another for saved")

        // One that has just been read from disk has nothing to save either.
        ledger.save { f, text -> BridgePaths.writeAtomic(f, text) }
        assertFalse(ScanLedger(file).dirty)
    }

    // A write that fails has put nothing on disk. Taken for saved, what it
    // held would wait for the next verdict to be written with, and a scan
    // killed before one came would lose it.
    @Test fun `a ledger whose write failed still has it to save`() {
        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val ledger = ScanLedger(file)
        ledger.record("/roms/nes/a.nes", CollectionRef.inferred("nes"), ScanLedger.State.NOT_FOUND, 10, 20, now = 1000)

        ledger.save { _, _ -> throw java.io.IOException("no room left") }
        assertTrue(ledger.dirty, "a write that failed was taken for one that got through")
        assertFalse(file.exists())

        ledger.save { f, text -> BridgePaths.writeAtomic(f, text) }
        assertFalse(ledger.dirty)
        assertEquals(1, JSONObject(file.readText()).getJSONObject("entries").length())
    }

    @Test fun `the entry of a path is given as it was recorded and none for a path never recorded`() {
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME))
        ledger.record("/roms/nes/a.nes", CollectionRef.inferred("nes"), ScanLedger.State.MATCHED, 10, 20, now = 1000,
                      gameId = 11, detail = "as found")

        val entry = ledger.entry("/roms/nes/a.nes")
        assertEquals(ScanLedger.State.MATCHED, entry?.state)
        assertEquals(listOf(1000L, 10L, 20L), listOf(entry?.checkedAt, entry?.fileSize, entry?.lastModified))
        assertEquals(11, entry?.gameId)
        assertEquals("as found", entry?.detail)
        assertNull(ledger.entry("/roms/nes/b.nes"))
    }

    // Asked before a scan, of every file, to say how much of the scan each
    // will be: by the entry alone, so it is yes for a file that has since
    // changed, which the scan then reads, and no wherever the scan would
    // not let the verdict stand for a reason the entry itself gives.
    @Test fun `a verdict stands unread by what the entry holds, with no look at the file`() {
        val nes = CollectionRef.inferred("nes")
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME))
        fun record(path: String, state: ScanLedger.State, detail: String = "") =
            ledger.record(path, nes, state, 4096, 77, now = 1000, detail = detail)

        // No file is at any of these paths.
        record("/roms/nes/found.nes", ScanLedger.State.NOT_FOUND)
        record("/roms/nes/retry.nes", ScanLedger.State.API_RETRY)
        record("/roms/nes/left.zip", ScanLedger.State.UNSUPPORTED_FORMAT,
               "disc.chd: " + ScanLedger.LEFT_BY_NINE)

        assertTrue(ledger.standsUnread("/roms/nes/found.nes", nes, now = 1001))
        assertFalse(ledger.standsUnread("/roms/nes/never.nes", nes, now = 1001), "a path never recorded")
        assertFalse(ledger.standsUnread("/roms/nes/retry.nes", nes, now = 1001), "a verdict that is not kept")
        assertFalse(ledger.standsUnread("/roms/nes/left.zip", nes, now = 1001), "a sentence that is taken again")
        assertFalse(ledger.standsUnread("/roms/nes/found.nes", CollectionRef.inferred("snes"), now = 1001),
                    "another collection's number")
        val season = ScanLedger.State.NOT_FOUND.retryAfterSeconds
        assertFalse(ledger.standsUnread("/roms/nes/found.nes", nes, now = 1001 + season), "a verdict run out")
    }

    // What the index is filtered by: the games a file of which is there. A
    // match written before matches carried their game says nothing of which
    // game it is, and is named apart so that the reader can tell.
    @Test fun `the games of the matches whose files are there are given and a match with no game is named apart`() {
        fun rom(name: String) = File(romRoot, name).apply { parentFile.mkdirs(); writeText(romText(name)) }
        val nes = CollectionRef.inferred("nes")
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME))
        fun record(file: File, state: ScanLedger.State, gameId: Int = 0) =
            ledger.record(file.path, nes, state, file.length(), file.lastModified(), now = 1000, gameId = gameId)

        record(rom("nes/Moss Kingdom (USA).nes"), ScanLedger.State.MATCHED, 11)
        record(rom("nes/Moss Kingdom (Europe).nes"), ScanLedger.State.MATCHED, 11)
        record(rom("nes/Paper Rally.nes"), ScanLedger.State.MATCHED, 12)
        val nameless = rom("nes/Tin Harbour.nes").also { record(it, ScanLedger.State.MATCHED) }
        record(rom("nes/Low Tide.nes"), ScanLedger.State.NOT_FOUND)
        val gone = rom("nes/Glass Orchard.nes").also { record(it, ScanLedger.State.MATCHED, 13) }
        assertTrue(gone.delete())

        assertEquals(setOf(11, 12), ledger.gamesOnDisk())
        assertEquals(listOf(nameless.path), ledger.matchesWithoutGame())

        // And a match with no game whose file is gone holds nothing up.
        assertTrue(nameless.delete())
        assertEquals(emptyList(), ledger.matchesWithoutGame())
    }
}
