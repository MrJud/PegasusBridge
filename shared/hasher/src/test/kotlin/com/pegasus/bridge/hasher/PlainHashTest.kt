package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plain file hashes [ArchiveAwareHasher] adds alongside the rcheevos one,
 * for databases that match by file rather than by title.
 */
class PlainHashTest {

    private lateinit var dir: File
    private lateinit var tempDir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("plainhash").toFile()
        tempDir = File(dir, "tmp")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /**
     * Stands in for rcheevos: returns a fixed hash, and records the file it was
     * handed — its path, and its bytes at that moment, since a temporary copy is
     * gone by the time a test can look.
     */
    private class FixedHasher(private val value: String = "RCHEEVOS") : RomHasher {
        var lastPath: String? = null
        var lastBytes: ByteArray? = null
        var calls = 0
        override fun hash(path: String): HashResult? {
            calls++
            lastPath = path
            val f = File(path)
            if (!f.exists()) return null
            lastBytes = f.readBytes()
            return HashResult(value, 7)
        }
    }

    /**
     * Stands in for rcheevos on a disc: keeps the console it was asked for
     * and what lay in the folder of the file it was handed, names and bytes,
     * at that moment. By the time a test can look the folder is gone.
     */
    private class DiscHasher(
        private val answer: (File) -> HashOutcome = { HashOutcome.Ok(HashResult("DISC", 12)) }
    ) : RomHasher {
        var calls = 0
        var console = -1
        var handed: File? = null
        var beside: Map<String, ByteArray> = emptyMap()
        override fun hash(path: String): HashResult? = (hashForConsole(path, 0) as? HashOutcome.Ok)?.result
        override fun hashForConsole(path: String, consoleId: Int): HashOutcome {
            calls++
            console = consoleId
            val file = File(path)
            handed = file
            beside = file.parentFile.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
            return answer(file)
        }
    }

    /** A sheet with one data track in each of [files]. */
    private fun cue(vararg files: String): ByteArray =
        files.joinToString("") { "FILE \"$it\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" }
            .toByteArray()

    // "abc" has well-known digests, so these are checked against constants
    // rather than against a second implementation of the same arithmetic.
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"
    private val abcCrc = "352441c2"

    @Test
    fun `plain md5 and crc are computed for a bare file`() {
        val rom = File(dir, "game.gb").apply { writeText("abc") }
        val r = ArchiveAwareHasher(FixedHasher(), tempDir).hash(rom.absolutePath)!!
        assertEquals("RCHEEVOS", r.hash)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
    }

    @Test
    fun `the rcheevos hash is left untouched`() {
        val rom = File(dir, "game.gb").apply { writeText("abc") }
        val r = ArchiveAwareHasher(FixedHasher("NOT-AN-MD5"), tempDir).hash(rom.absolutePath)!!
        assertEquals("NOT-AN-MD5", r.hash)
        assertNotEquals(r.hash, r.fileMd5)
    }

    @Test
    fun `an archive hashes its contents, not the container`() {
        val rom = File(dir, "game.zip")
        ZipOutputStream(rom.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("game.gb"))
            zos.write("abc".toByteArray())
            zos.closeEntry()
        }
        val r = ArchiveAwareHasher(FixedHasher(), tempDir).hash(rom.absolutePath)!!
        // The inner ROM's digests, never the zip's own — a scraper asked about
        // the container would match nothing.
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
        assertNotEquals(md5Of(rom), r.fileMd5)
    }

    // rcheevos picks its algorithm from the extension. Handed `.bin`, this would be
    // hashed whole, the way a Mega Drive cartridge is — iNES header included, which
    // `.nes` would have skipped — and the hash would match nothing.
    @Test
    fun `an extracted ROM keeps the extension rcheevos chooses its algorithm by`() {
        val rom = zip("game.zip", "folder/game.NES" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)!!
        val handed = File(native.lastPath!!)
        assertEquals("nes", handed.extension)
        assertEquals(tempDir.canonicalFile, handed.parentFile.canonicalFile)
        // Copied and digested in one pass: the copy must still be whole, and
        // closed, by the time rcheevos opens it.
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
        assertEquals("folder/game.NES", r.archiveEntry)
        assertFalse(handed.exists(), "temporary ROM is removed after hashing")
    }

    // The other side of the same rule: rcheevos has no handler for `.img`, and
    // would hash the whole image. Named `.bin`, a raw disc image over 32 MiB is
    // tried as a CD track first; GoldenHashTest shows the hash that gives.
    @Test
    fun `a raw disc image named img is handed to rcheevos as a bin`() {
        val rom = zip("Disc.zip", "Disc.img" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "psx")
        assertEquals("bin", File(native.lastPath!!).extension)
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals("Disc.img", (r as HashOutcome.Ok).result.archiveEntry)
    }

    // ------------------------------------------------------------ discs
    //
    // A disc in an archive is a sheet and the tracks it names, and rcheevos
    // opens the tracks from beside the sheet. The sheet used to be all that
    // was looked at, and it was refused unread.

    // The sheet says `LANTERN KEEP (Track 1).bin` of an entry called
    // `Lantern Keep (Track 1).bin`, as a sheet written on Windows may. The
    // file is written under the sheet's spelling, which is what will be
    // asked for, and both tracks are there when the hasher is called. In a
    // solid 7z the sheet lies after its tracks.
    @Test
    fun `a disc in an archive is taken out whole, each track under the name its sheet uses`() {
        val one = ByteArray(5000) { 1 }
        val two = ByteArray(3000) { 2 }
        val sheet = cue("LANTERN KEEP (Track 1).bin", "Lantern Keep (Track 2).bin")
        val entries = arrayOf("Lantern Keep/Lantern Keep (Track 1).bin" to one,
                              "Lantern Keep/Lantern Keep (Track 2).bin" to two,
                              "Lantern Keep/Notes.bin" to ByteArray(700),
                              "Lantern Keep/Lantern Keep.cue" to sheet)
        for (archive in listOf(zip("Lantern Keep.zip", *entries),
                               SolidSevenZ.write(File(dir, "Lantern Keep.7z"), *entries))) {
            val native = DiscHasher()
            val r = assertIs<HashOutcome.Ok>(
                ArchiveAwareHasher(native, tempDir).hashDetailed(archive.absolutePath, "psx"), archive.name).result

            assertEquals(1, native.calls, archive.name)
            assertEquals(12, native.console, "the console of the collection")
            val handed = native.handed!!
            assertEquals("disc.cue", handed.name)
            assertTrue(handed.parentFile.name.startsWith("bridge_set_"), handed.path)
            assertEquals(tempDir.canonicalFile, handed.parentFile.parentFile.canonicalFile)
            assertEquals(setOf("disc.cue", "LANTERN KEEP (Track 1).bin", "Lantern Keep (Track 2).bin"),
                         native.beside.keys, archive.name)
            assertContentEquals(sheet, native.beside["disc.cue"])
            assertContentEquals(one, native.beside["LANTERN KEEP (Track 1).bin"])
            assertContentEquals(two, native.beside["Lantern Keep (Track 2).bin"])

            // The sheet is the file the digests are of, as for a disc lying loose.
            assertEquals("DISC", r.hash)
            assertEquals(md5Of(sheet), r.fileMd5)
            assertEquals("Lantern Keep/Lantern Keep.cue", r.archiveEntry)
            assertTrue(tempDir.listFiles()!!.isEmpty(), "the disc was left behind")
        }
    }

    @Test
    fun `a dreamcast gdi and its three tracks reach the hasher as console 40`() {
        val gdi = ("3\r\n1 0 4 2352 track01.bin 0\r\n2 756 0 2352 \"track 02.raw\" 0\r\n" +
                   "3 45000 4 2352 track03.bin 0\r\n").toByteArray()
        val archive = zip("Lantern Keep.zip", "Lantern Keep.gdi" to gdi, "track01.bin" to ByteArray(600) { 1 },
                          "track 02.raw" to ByteArray(700) { 2 }, "TRACK03.BIN" to ByteArray(800) { 3 })
        val native = DiscHasher()
        val r = assertIs<HashOutcome.Ok>(
            ArchiveAwareHasher(native, tempDir).hashDetailed(archive.absolutePath, "dreamcast")).result

        assertEquals(40, native.console)
        assertEquals("disc.gdi", native.handed!!.name)
        assertEquals(mapOf("disc.gdi" to gdi.size, "track01.bin" to 600, "track 02.raw" to 700, "track03.bin" to 800),
                     native.beside.mapValues { it.value.size })
        assertEquals("Lantern Keep.gdi", r.archiveEntry)
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // The playlist is the entry point, and the disc it names first is the
    // game: the second disc lies first in the archive and is not taken out.
    @Test
    fun `a playlist in an archive leads to the disc it names first`() {
        val playlist = "# two discs\r\nd1.cue\r\nd2.cue\r\n".toByteArray()
        val discs = zip("Lantern Keep.zip", "d2.cue" to cue("d2.bin"), "d2.bin" to ByteArray(900) { 2 },
                        "Multi.m3u" to playlist, "d1.cue" to cue("d1.bin"), "d1.bin" to ByteArray(800) { 1 })
        val native = DiscHasher()
        val r = assertIs<HashOutcome.Ok>(
            ArchiveAwareHasher(native, tempDir).hashDetailed(discs.absolutePath, "psx")).result
        assertEquals(1, native.calls)
        assertEquals(setOf("disc.cue", "d1.bin"), native.beside.keys)
        assertContentEquals(ByteArray(800) { 1 }, native.beside["d1.bin"])
        // As for a playlist on a disk, the digests are the playlist's.
        assertEquals(md5Of(playlist), r.fileMd5)
        assertEquals("Multi.m3u", r.archiveEntry)

        // Discs that are one image each: the first is copied out as any single entry is.
        val images = zip("Images.zip", "Multi.m3u" to "Disc 1.iso\n".toByteArray(),
                         "Disc 2.iso" to ByteArray(900) { 2 }, "Disc 1.iso" to ByteArray(800) { 1 })
        val single = DiscHasher()
        val image = assertIs<HashOutcome.Ok>(
            ArchiveAwareHasher(single, tempDir).hashDetailed(images.absolutePath, "ps2")).result
        assertEquals("iso", single.handed!!.extension)
        assertContentEquals(ByteArray(800) { 1 }, single.beside[single.handed!!.name])
        assertEquals(md5Of("Disc 1.iso\n".toByteArray()), image.fileMd5)
        assertEquals("Multi.m3u", image.archiveEntry)
        assertTrue(tempDir.listFiles()!!.isEmpty())

        // A playlist that names its discs with their folder, in either kind
        // of slash. The name is only looked up among the entries, by its
        // last part, and nothing is written under it.
        for (line in listOf("discs/d1.cue", "discs\\d1.cue", "..\\Lantern Keep\\discs\\d1.cue")) {
            val foldered = zip("Folders.zip", "Lantern Keep/Multi.m3u" to "$line\r\n".toByteArray(),
                               "Lantern Keep/discs/d2.cue" to cue("d2.bin"), "Lantern Keep/discs/d2.bin" to ByteArray(900) { 2 },
                               "Lantern Keep/discs/d1.cue" to cue("d1.bin"), "Lantern Keep/discs/d1.bin" to ByteArray(800) { 1 })
            val inFolders = DiscHasher()
            val r = assertIs<HashOutcome.Ok>(
                ArchiveAwareHasher(inFolders, tempDir).hashDetailed(foldered.absolutePath, "psx"), line).result
            assertEquals(setOf("disc.cue", "d1.bin"), inFolders.beside.keys, line)
            assertEquals("Lantern Keep/Multi.m3u", r.archiveEntry)
        }
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // Each of these is known from the listing and the sheet, and will be
    // no different at the next scan: nothing is copied out (the temporary
    // folder is only made for a copy), rcheevos is not asked, and the
    // failure is one that is kept. A `.ccd`, a `.toc` and an `.mds` are
    // sheets rcheevos has no reader for.
    @Test
    fun `a disc that cannot be put together, or whose sheet nobody reads, fails for good and nothing is copied`() {
        val track = ByteArray(4096)
        val rows = listOf(
            Triple("psx", arrayOf("Disc.cue" to cue("Lost.bin"), "Disc.bin" to track),
                   "'Disc.cue' in the archive: it names 'Lost.bin', which is not in the archive"),
            Triple("psx", arrayOf("Disc.cue" to cue("tracks/x.bin"), "tracks/x.bin" to track),
                   "'Disc.cue' in the archive: it names 'tracks/x.bin', which is not a file beside it"),
            Triple("psx", arrayOf("Disc.cue" to cue("../Disc.bin"), "Disc.bin" to track),
                   "'Disc.cue' in the archive: it names '../Disc.bin', which is not a file beside it"),
            Triple("psx", arrayOf("Disc.cue" to cue("Disc.cue"), "Disc.bin" to track),
                   "'Disc.cue' in the archive: it names itself"),
            Triple("psx", arrayOf("Disc.cue" to "REM no track\r\n".toByteArray(), "Disc.bin" to track),
                   "'Disc.cue' in the archive: it names no file"),
            // The sheet's copy is called disc.cue, whatever the sheet was
            // called, and a track of that name would be written over it.
            Triple("psx", arrayOf("Lantern.cue" to cue("DISC.cue"), "disc.CUE" to track, "Lantern.bin" to track),
                   "'Lantern.cue' in the archive: it names 'DISC.cue', which is what its own copy is called"),
            Triple("psx", arrayOf("Disc.cue" to ByteArray(DescriptorSet.SHEET_LIMIT + 1) { ' '.code.toByte() }, "Disc.bin" to track),
                   "'Disc.cue' in the archive is too long to be what its name says"),
            Triple("psx", arrayOf("Disc.ccd" to "[CloneCD]".toByteArray(), "Disc.img" to track, "Disc.sub" to ByteArray(96)),
                   "'Disc.ccd' in the archive is a .ccd sheet, which rcheevos does not read"),
            Triple("saturn", arrayOf("Disc.toc" to "CD_ROM".toByteArray(), "Disc.bin" to track),
                   "'Disc.toc' in the archive is a .toc sheet, which rcheevos does not read"),
            Triple("saturn", arrayOf("Disc.mds" to ByteArray(300), "Disc.mdf" to track),
                   "'Disc.mds' in the archive is a .mds sheet, which rcheevos does not read"),
            Triple("psx", arrayOf("Disc.m3u" to "Other.m3u\n".toByteArray(), "Other.m3u" to "Disc.cue\n".toByteArray()),
                   "a playlist named by a playlist is not followed"),
            Triple("psx", arrayOf("Disc.m3u" to "# nothing\n".toByteArray(), "Disc.bin" to track),
                   "'Disc.m3u' in the archive: it names no file"),
            Triple("", arrayOf("Disc.m3u" to "Lost.cue\n".toByteArray(), "Disc.bin" to track),
                   "'Disc.m3u' in the archive: it names 'Lost.cue', which is not in the archive")
        )
        val wrong = rows.mapNotNull { (platform, entries, reason) ->
            // Named after its first entry, which is the one to be chosen.
            val archive = zip(entries[0].first.substringBeforeLast('.') + ".zip", *entries)
            val native = DiscHasher()
            val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(archive.absolutePath, platform)
            when {
                outcome != HashOutcome.Failed(reason, retryable = false) -> "$reason: got $outcome"
                native.calls != 0 -> "$reason: rcheevos was asked"
                tempDir.exists() -> "$reason: something was copied out"
                else -> null
            }
        }
        assertEquals(emptyList(), wrong)
    }

    // A sheet in a collection of cartridges is found out from its name, as
    // one lying loose is, before its tracks are taken out for nothing.
    @Test
    fun `a sheet its collection's console does not hash as a disc is turned away before anything is copied`() {
        val archive = zip("Disc.zip", "Disc.cue" to cue("Disc.bin"), "Disc.bin" to ByteArray(4096))
        val native = DiscHasher()
        assertEquals(HashOutcome.UnsupportedFormat("'Disc.cue' in the archive: a .cue describes a disc, " +
                                                   "and console 46 is not hashed as one"),
                     ArchiveAwareHasher(native, tempDir).hashDetailed(archive.absolutePath, "vectrex"))
        assertEquals(0, native.calls)
        assertFalse(tempDir.exists(), "something was copied out")
    }

    // The room is counted from the listing before a byte is written, and
    // too little of it today says nothing of tomorrow.
    @Test
    fun `a disc there is no room to take out is a failure to try again`() {
        val archive = zip("Disc.zip", "Disc.cue" to cue("Disc.bin"), "Disc.bin" to ByteArray(4096))
        val sheet = cue("Disc.bin").size
        val native = DiscHasher()
        val asked = mutableListOf<File>()
        val cramped = ArchiveAwareHasher(native, tempDir) { asked += it; 4096L + sheet - 1 }
        assertEquals(HashOutcome.Failed("no room to take 'Disc.cue' and its tracks out of the archive: " +
                                        "${4096 + sheet} bytes needed, ${4096 + sheet - 1} free"),
                     cramped.hashDetailed(archive.absolutePath, "psx"))
        assertEquals(0, native.calls)
        assertEquals(listOf(tempDir), asked, "the room is asked of the folder the copies are made in")
        assertTrue(tempDir.listFiles()!!.isEmpty())

        // A byte more is enough.
        val roomy = ArchiveAwareHasher(native, tempDir) { 4096L + sheet }
        assertIs<HashOutcome.Ok>(roomy.hashDetailed(archive.absolutePath, "psx"))
    }

    // The room was counted by the sizes the archive lists, and a listing
    // can say less than its entry then gives: a zip written wrong, or made
    // to fill a disk. A track is written as far as it was listed and no
    // further, and the disc does not reach the hasher short of a track.
    @Test
    fun `a track that holds more than the archive lists for it is not written past that`() {
        val archive = zip("Disc.zip", "Disc.cue" to cue("Disc.bin"), "Disc.bin" to ByteArray(300_000) { (it * 31).toByte() })
        listedAs(archive, "Disc.bin", 1000)
        val sheet = cue("Disc.bin").size

        val native = DiscHasher()
        val asked = mutableListOf<Long>()
        // Room for what is listed and not a byte more: it is enough, so the listing is what was counted.
        val hasher = ArchiveAwareHasher(native, tempDir) { (1000L + sheet).also { asked += it } }
        assertEquals(HashOutcome.Failed("could not extract 'Disc.bin': it holds more than the 1000 bytes " +
                                        "the archive lists for it"),
                     hasher.hashDetailed(archive.absolutePath, "psx"))
        assertEquals(1, asked.size, "the room was not asked for")
        assertEquals(0, native.calls, "a disc short of a track reached the hasher")
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `nothing of a disc is left behind after a failure, a cancellation or an interrupt`() {
        val archive = zip("Disc.zip", "Disc.cue" to cue("Disc.bin"), "Disc.bin" to ByteArray(200_000))

        val refusing = DiscHasher { HashOutcome.Failed("Not a PlayStation disc") }
        assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue': Not a PlayStation disc", retryable = false),
                     ArchiveAwareHasher(refusing, tempDir).hashDetailed(archive.absolutePath, "psx"))
        assertEquals(setOf("disc.cue", "Disc.bin"), refusing.beside.keys)
        assertTrue(tempDir.listFiles()!!.isEmpty(), "after a failure")

        val cancelled = DiscHasher { throw kotlinx.coroutines.CancellationException("cancelled") }
        assertFailsWith<CancellationException> {
            ArchiveAwareHasher(cancelled, tempDir).hashDetailed(archive.absolutePath, "psx")
        }
        assertEquals(1, cancelled.calls)
        assertTrue(tempDir.listFiles()!!.isEmpty(), "after a cancellation")

        // The copy of a track looks for the interrupt before every buffer.
        val never = DiscHasher()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                ArchiveAwareHasher(never, tempDir).hashDetailed(archive.absolutePath, "psx")
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, never.calls, "the disc reached the hasher half copied")
        assertTrue(tempDir.listFiles()!!.isEmpty(), "after an interrupt")
    }

    // "Could not open" a track, with its path, is what a console says of a
    // disc lying loose whose track is missing, and that is tried again. Of a
    // disc taken out a moment ago it means the console reads the sheet for
    // another name than was written, and it will at every scan, each time
    // after the whole disc has been taken out again. Unless what was
    // written has gone from under it, which is nobody's verdict on the disc.
    @Test
    fun `a console that asks for a track the sheet was not read to name is not asked again`() {
        val archive = zip("Disc.zip", "Disc.cue" to cue("Disc.bin"), "Disc.bin" to ByteArray(4096))
        fun askingFor(name: String, before: (File) -> Unit = {}) = DiscHasher { sheet ->
            before(sheet)
            HashOutcome.Failed("Could not open ${File(sheet.parentFile, name).path}; Could not open track")
        }

        // The folder made for the disc is not in the reason: it is another at every scan.
        assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue': Could not open Other.bin; " +
                                        "Could not open track", retryable = false),
                     ArchiveAwareHasher(askingFor("Other.bin"), tempDir).hashDetailed(archive.absolutePath, "psx"))

        val emptied = askingFor("Disc.bin") { File(it.parentFile, "Disc.bin").delete() }
        assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue': Could not open Disc.bin; " +
                                        "Could not open track", retryable = true),
                     ArchiveAwareHasher(emptied, tempDir).hashDetailed(archive.absolutePath, "psx"))

        // The library keeps so many bytes of what the consoles said, and
        // the last path may stop anywhere: in the middle of the folder's
        // name it is still a path of the folder, and no more of a reason.
        fun cutShort(before: String, drop: Int) = DiscHasher { sheet ->
            HashOutcome.Failed(before + "Could not open " + (sheet.parentFile.path + File.separator).dropLast(drop))
        }
        for (drop in listOf(1, 9, 25)) {
            assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue': Not a PlayStation disc", retryable = false),
                         ArchiveAwareHasher(cutShort("Not a PlayStation disc; ", drop), tempDir)
                             .hashDetailed(archive.absolutePath, ""), "$drop characters short")
        }
        assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue'", retryable = false),
                     ArchiveAwareHasher(cutShort("", 4), tempDir).hashDetailed(archive.absolutePath, "psx"))

        // A file that could not be opened and is not said to be of the disc.
        val elsewhere = DiscHasher { HashOutcome.Failed("Could not open file") }
        assertEquals(HashOutcome.Failed("the hasher could not read 'Disc.cue': Could not open file", retryable = true),
                     ArchiveAwareHasher(elsewhere, tempDir).hashDetailed(archive.absolutePath, "psx"))
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // A scan that is killed removes nothing, and what it was copying stays:
    // a disc, at worst. Building the hasher is where the next scan starts.
    // On Android the folder is the app's whole cache, so only what bears
    // this class's prefix and is older than a day goes.
    @Test
    fun `what an earlier scan left behind is removed when a hasher is built, and nothing else`() {
        val twoDays = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        tempDir.mkdirs()
        fun old(file: File) = file.apply { assertTrue(setLastModified(twoDays), "could not age $name") }
        fun folder(name: String) = File(tempDir, name).apply { mkdirs(); File(this, "Track 1.bin").writeBytes(ByteArray(64)) }

        val staleDisc = old(folder("bridge_set_4821"))
        val staleCopy = old(File(tempDir, "bridge_9130.bin").apply { writeText("abc") })
        val freshDisc = folder("bridge_set_77")
        val freshCopy = File(tempDir, "bridge_78.nes").apply { writeText("abc") }
        val othersFile = old(File(tempDir, "thumbnail.bin").apply { writeText("abc") })
        val othersFolder = old(folder("image_cache"))
        val nearName = old(File(tempDir, "Bridge_set_1").apply { writeText("abc") })

        // A link with the prefix, to a folder that is not this class's.
        val outside = File(dir, "elsewhere").apply { mkdirs(); File(this, "kept.bin").writeText("abc") }
        val link = File(tempDir, "bridge_set_link")
        val linked = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            old(outside)
            true
        } catch (e: Exception) {
            println("PlainHashTest: no symbolic link could be made here (${e.javaClass.simpleName}), so none was tried")
            false
        }

        ArchiveAwareHasher(FixedHasher(), tempDir)

        assertFalse(staleDisc.exists(), "a disc left two days ago is still there")
        assertFalse(staleCopy.exists(), "a copy left two days ago is still there")
        assertTrue(File(freshDisc, "Track 1.bin").isFile, "a disc of a scan that may be running was removed")
        assertTrue(freshCopy.isFile, "a copy of a scan that may be running was removed")
        assertTrue(othersFile.isFile && File(othersFolder, "Track 1.bin").isFile && nearName.isFile,
                   "a file that is not this class's was removed")
        if (linked) {
            assertFalse(Files.isSymbolicLink(link.toPath()), "the stale link is still there")
            assertTrue(File(outside, "kept.bin").isFile, "the link was followed, and what it leads to removed")
        }

        // And a folder that is not there is not made by looking into it.
        val absent = File(dir, "never-made")
        ArchiveAwareHasher(FixedHasher(), absent)
        assertFalse(absent.exists())
    }

    // Two entries called `game.nes`, an empty leftover first. The selector refuses
    // the empty one; finding its choice again by name used to land on the leftover
    // and record the digest of nothing as the ROM's.
    @Test
    fun `a 7z hands over the entry the selector chose, not the first with its name`() {
        val rom = sevenZ("game.7z", "game.nes" to ByteArray(0), "game.nes" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)!!
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals("nes", File(native.lastPath!!).extension)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
    }

    // The same pair in a zip, ROM first. java.util.zip finds an entry by name and
    // answers with the last, so the leftover went to rcheevos as the ROM, and its
    // empty digest was recorded. The zip is refused instead, and says why.
    @Test
    fun `a zip whose chosen entry shares its name is refused, not read as the other`() {
        val rom = File(dir, "game.zip").also { f ->
            ZipArchiveOutputStream(f).use { out ->
                for (bytes in listOf("abc".toByteArray(), ByteArray(0))) {
                    out.putArchiveEntry(ZipArchiveEntry("game.nes")); out.write(bytes); out.closeArchiveEntry()
                }
            }
        }
        val native = FixedHasher()
        val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "nes")
        assertEquals(HashOutcome.Failed("could not extract 'game.nes': game.zip holds 2 entries named " +
                                        "'game.nes', and a zip entry can only be read by its name"), outcome)
        assertEquals(0, native.calls)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive cancellation is propagated and removes the temporary ROM`() {
        val rom = zip("game.zip", "game.gb" to "abc".toByteArray())
        var calls = 0
        val native = object : RomHasher {
            override fun hash(path: String): HashResult? {
                calls++
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertFailsWith<CancellationException> {
            ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
        }
        assertEquals(1, calls, "cancellation must not retry the archive as a bare file")
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // How runInterruptible delivers a cancelled scan. The copy checks before every
    // 64 KiB, so it stops before rcheevos is reached at all.
    @Test
    fun `an interrupt stops the extraction and leaves nothing behind`() {
        val rom = zip("game.zip", "game.gb" to ByteArray(200_000))
        val native = FixedHasher()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, native.calls, "neither the entry nor the container reaches the hasher")
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    // A 7z is read through a FileChannel, which an interrupt closes. That arrives
    // as an IOException while listing — which used to mean "not an archive", and
    // was answered by hashing the whole container.
    @Test
    fun `an interrupt while opening a 7z is not taken for an unreadable archive`() {
        val rom = sevenZ("game.7z", "game.gb" to "abc".toByteArray())
        val native = FixedHasher()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, native.calls)
    }

    // It was hashed as it lay, on the reasoning that an extension is a claim
    // and a renamed ROM a real game. But what rcheevos is handed is still
    // called `.7z` or `.zip`, and it hashes the name of such a file as an
    // arcade set's: the answer was never the ROM's.
    @Test
    fun `a ROM misnamed as an archive is refused, not hashed as a container`() {
        for (name in listOf("notreally.7z", "notreally.zip")) {
            val rom = File(dir, name).apply { writeText("abc") }
            val native = FixedHasher()
            val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "nes")
            assertTrue(outcome is HashOutcome.Failed && !outcome.retryable &&
                       outcome.reason.startsWith("not a readable archive: "), "$name: $outcome")
            assertEquals(0, native.calls, "$name was handed to rcheevos")
            assertNull(ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath), "$name through hash(path)")
        }
    }

    // A file that would not open is another matter from an archive that
    // would not: nothing of it has been seen. One the scan is not allowed to
    // read, or one on a card taken out a moment ago, may be as good a game
    // as any, and kept as a file that cannot be hashed it was not looked at
    // again for a month.
    @Test
    fun `an archive that could not be opened at all is tried again`() {
        val locked = zip("Locked.zip", "Locked.nes" to "abc".toByteArray())
        if (!locked.setReadable(false, false) || runCatching { locked.inputStream().close() }.isSuccess) {
            println("PlainHashTest: no file could be made unreadable here, so none was tried")
            return
        }
        val native = FixedHasher()
        val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(locked.absolutePath, "nes")
        assertTrue(outcome is HashOutcome.Failed && outcome.retryable &&
                   outcome.reason.startsWith("the archive could not be opened: "), "$outcome")
        assertEquals(0, native.calls, "it was handed to rcheevos")
    }

    // An archive that opens and has no game in it for its platform was the
    // other file hashed as a container. It is its own answer now, and says
    // what the archive does hold.
    @Test
    fun `an archive with no game in it says what it holds and is not hashed`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)

        val patch = zip("patch.zip", "fix/patch.7z" to "abc".toByteArray(), "notes.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: patch.7z"),
                     hasher.hashDetailed(patch.absolutePath, "ps2"))

        val many = zip("many.zip", *(1..7).map { "chip$it.rom" to "abc".toByteArray() }.toTypedArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: " +
                                                 "chip1.rom, chip2.rom, chip3.rom, chip4.rom, chip5.rom, and 2 more"),
                     hasher.hashDetailed(many.absolutePath, "nes"))

        // Only a readme, and nothing at all.
        val readme = zip("readme.zip", "readme.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: readme.txt"),
                     hasher.hashDetailed(readme.absolutePath, "nes"))
        val empty = zip("empty.zip")
        assertEquals(HashOutcome.NoPlayableEntry("the archive holds no file"),
                     hasher.hashDetailed(empty.absolutePath, "nes"))

        assertEquals(0, native.calls)
        assertFalse(tempDir.exists(), "an entry was copied out")
    }

    // The entry an archive holds for its platform is planned for by its own
    // name, as a file lying loose is. A packed disc image that has been
    // zipped as well was copied out and handed over, and what came back was
    // the hash of the image's container.
    @Test
    fun `an entry of a format nobody reads is not copied out`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)

        val packed = zip("Disc.zip", "Disc.chd" to "abc".toByteArray(), "readme.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.UnsupportedFormat("'Disc.chd' in the archive: " +
                                                   ".chd is a format this build has no reader for"),
                     hasher.hashDetailed(packed.absolutePath, "ps2"))
        val converted = zip("Other.zip", "Other.cso" to "abc".toByteArray())
        assertEquals(HashOutcome.UnsupportedFormat("'Other.cso' in the archive: .cso is a format rcheevos does not read"),
                     hasher.hashDetailed(converted.absolutePath, "psp"))

        assertEquals(0, native.calls)
        assertFalse(tempDir.exists(), "an entry was copied out")
    }

    // A caller that is not a scan and hands over a file a scan would have
    // turned away gets the same answer, and the file is not read.
    @Test
    fun `a file or a collection nobody can hash is refused by the hasher too`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)
        val wrong = listOf(
            Triple("ps2", "Disc.chd", ".chd is a format this build has no reader for"),
            Triple("arcade", "chip.bin", "an arcade set is a .zip or a .7z, and this is a .bin"),
            Triple("switch", "Game.zip", "RetroAchievements has no console for switch"),
            Triple("amiga", "Disk.adf", "rcheevos has no hashing algorithm for RC_CONSOLE_AMIGA (id 35)")
        ).mapNotNull { (platform, name, reason) ->
            val file = File(dir, name).apply { writeText("abc") }
            val outcome = hasher.hashDetailed(file.absolutePath, platform)
            if (outcome == HashOutcome.UnsupportedFormat(reason)) null else "$platform/$name: $outcome"
        }
        assertEquals(emptyList(), wrong)
        assertEquals(0, native.calls)
    }

    @Test
    fun `no result means no hashes`() {
        val hasher = ArchiveAwareHasher(FixedHasher(), tempDir)
        assertNull(hasher.hash(File(dir, "missing.gb").absolutePath))
    }

    @Test
    fun `hashes cover the whole file, not a prefix`() {
        val a = File(dir, "a.gb").apply { writeBytes(ByteArray(200_000) { 0 }) }
        val b = File(dir, "b.gb").apply {
            writeBytes(ByteArray(200_000) { 0 }.also { it[199_999] = 1 })
        }
        val hasher = ArchiveAwareHasher(FixedHasher(), tempDir)
        val ra = hasher.hash(a.absolutePath)!!
        val rb = hasher.hash(b.absolutePath)!!
        // Differing only in the last byte, across several read buffers.
        assertNotEquals(ra.fileMd5, rb.fileMd5)
        assertNotEquals(ra.fileCrc32, rb.fileCrc32)
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(dir, name).also { f ->
            ZipOutputStream(f.outputStream()).use { zos ->
                for ((entry, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(entry)); zos.write(bytes); zos.closeEntry()
                }
            }
        }

    /** Unlike ZipOutputStream, this lets two entries share a name. */
    /**
     * Makes the listing of [zip] say [entry] is [size] bytes long, whatever
     * it holds: the size is written twice in a zip, and the one in the
     * directory at its end is the one a listing is read from.
     */
    private fun listedAs(zip: File, entry: String, size: Int) {
        val bytes = zip.readBytes()
        val name = entry.toByteArray()
        // A directory record: its signature, the size at 24, the length of the name at 28, the name at 46.
        val at = (0..bytes.size - 46 - name.size).first { i ->
            bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() && bytes[i + 2] == 1.toByte() &&
                bytes[i + 3] == 2.toByte() && bytes[i + 28].toInt() == name.size && bytes[i + 29].toInt() == 0 &&
                bytes.copyOfRange(i + 46, i + 46 + name.size).contentEquals(name)
        }
        for (b in 0..3) bytes[at + 24 + b] = (size ushr (8 * b)).toByte()
        zip.writeBytes(bytes)
    }

    private fun sevenZ(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(dir, name).also { f ->
            SevenZOutputFile(f).use { out ->
                for ((entry, bytes) in entries) {
                    out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = entry })
                    out.write(bytes)
                    out.closeArchiveEntry()
                }
            }
        }

    private fun md5Of(f: File): String = md5Of(f.readBytes())

    private fun md5Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
