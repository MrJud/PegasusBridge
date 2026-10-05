package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The digests ScreenScraper matches on, computed without rcheevos.
 *
 * The existing [PlainHashTest] covers the same three values as a passenger on the
 * RetroAchievements hash; this covers them standing alone, which is the case that has
 * to work on a desktop with no native library — where the other path returns null
 * before it ever reaches them.
 */
class PlainRomHasherTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "plainhash-test-${System.nanoTime()}")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun write(name: String, bytes: ByteArray): File {
        tmp.mkdirs()
        val f = File(tmp, name)
        f.writeBytes(bytes)
        return f
    }

    /** Unlike ZipOutputStream, this lets two entries share a name. */
    private fun sevenZ(name: String, vararg entries: Pair<String, ByteArray>): File {
        tmp.mkdirs()
        val f = File(tmp, name)
        SevenZOutputFile(f).use { out ->
            for ((entry, bytes) in entries) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = entry })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return f
    }

    /** java.util.zip refuses a second entry of one name; commons-compress writes it. */
    private fun zipWithRepeats(name: String, vararg entries: Pair<String, ByteArray>): File {
        tmp.mkdirs()
        val f = File(tmp, name)
        ZipArchiveOutputStream(f).use { out ->
            for ((entry, bytes) in entries) {
                out.putArchiveEntry(ZipArchiveEntry(entry))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return f
    }

    @Test fun `a plain file gives md5, crc32 and size`() {
        // "abc" has textbook digests, so a wrong endianness or a hex-padding slip shows
        // up here rather than as a library-wide miss against the live API.
        val f = write("rom.nes", "abc".toByteArray())
        val h = PlainRomHasher.hash(f.absolutePath, tmp)
        assertNotNull(h)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h.md5)
        assertEquals("352441c2", h.crc32)
        assertEquals(3L, h.size)
        assertEquals("rom.nes", h.name)
        assertTrue(!h.fromArchive)
    }

    @Test fun `a crc32 with a leading zero keeps its eight digits`() {
        // ScreenScraper compares the string. A CRC formatted as seven characters
        // matches nothing, and the failure looks exactly like a game the database does
        // not have — which is the failure mode this whole source has to avoid.
        var bytes = ByteArray(0)
        var found: String? = null
        for (i in 0..4000) {
            val candidate = "seed-$i".toByteArray()
            val crc = java.util.zip.CRC32().apply { update(candidate) }.value
            if (crc < 0x10000000L) { bytes = candidate; found = "%08x".format(crc); break }
        }
        assertNotNull(found, "no small-CRC sample found")
        val f = write("small.nes", bytes)
        assertEquals(8, PlainRomHasher.hash(f.absolutePath, tmp)!!.crc32.length)
    }

    @Test fun `a zip is hashed by its contents, and named by the archive`() {
        // Two facts at once, and both matter: the databases list the ROM, so the digest
        // must describe the entry — but MAME identifies a set by the *archive's* name,
        // so that is what has to survive to the request.
        val inner = "the rom bytes".toByteArray()
        tmp.mkdirs()
        val zip = File(tmp, "game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("readme.txt")); z.write("notes".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("game.nes"));   z.write(inner);                  z.closeEntry()
        }

        val loose = write("loose.nes", inner)
        val fromZip = PlainRomHasher.hash(zip.absolutePath, tmp)
        val fromFile = PlainRomHasher.hash(loose.absolutePath, tmp)

        assertNotNull(fromZip)
        assertNotNull(fromFile)
        assertEquals(fromFile.md5, fromZip.md5, "the digest is the ROM's, not the container's")
        assertEquals(fromFile.size, fromZip.size)
        assertEquals("game.zip", fromZip.name, "the romset name is the archive's")
        assertTrue(fromZip.fromArchive)
    }

    // Not "the largest entry" any more: the two text files are refused because a
    // `.txt` is never a ROM, and `b.rom` is then the only candidate left. Being
    // biggest is a consequence here, not the reason.
    @Test fun `the one playable entry is the ROM, whatever the others weigh`() {
        val big = ByteArray(4096) { it.toByte() }
        tmp.mkdirs()
        val zip = File(tmp, "multi.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("b.rom")); z.write(big);               z.closeEntry()
            z.putNextEntry(ZipEntry("c.txt")); z.write("yy".toByteArray()); z.closeEntry()
        }
        val h = PlainRomHasher.hash(zip.absolutePath, tmp)!!
        assertEquals(4096L, h.size)
        assertEquals("b.rom", h.archiveEntry)
    }

    // A bonus file that outweighs the game is exactly where the old rule broke.
    @Test fun `a picture larger than the game does not become the ROM`() {
        tmp.mkdirs()
        val zip = File(tmp, "Contra (USA).zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Contra (USA).nes")); z.write(ByteArray(1024)); z.closeEntry()
            z.putNextEntry(ZipEntry("box.png"));          z.write(ByteArray(65536)); z.closeEntry()
        }
        val h = PlainRomHasher.hash(zip.absolutePath, tmp, "nes")!!
        assertEquals(1024L, h.size)
        assertEquals("Contra (USA).nes", h.archiveEntry)
    }

    // When it cannot tell, it hashes the container and says which entries it could
    // not choose between — so the caller can decline to spend a lookup on a digest
    // that matches nothing.
    @Test fun `an unresolvable archive reports its candidates`() {
        tmp.mkdirs()
        val zip = File(tmp, "Sonic Collection.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Sonic 1.md")); z.write(ByteArray(512)); z.closeEntry()
            z.putNextEntry(ZipEntry("Sonic 2.md")); z.write(ByteArray(1024)); z.closeEntry()
        }
        val h = PlainRomHasher.hash(zip.absolutePath, tmp, "megadrive")!!
        assertEquals(2, h.ambiguous.size)
        assertTrue(!h.fromArchive, "the digests describe the container, and must say so")
    }

    // The entry streams straight into the digest, so nothing is written — and a
    // ScreenScraper lookup no longer depends on a cache directory being writable.
    @Test fun `archive digests need no writable temporary directory`() {
        tmp.mkdirs()
        val zip = File(tmp, "game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("game.nes")); z.write("abc".toByteArray()); z.closeEntry()
        }
        // A file cannot contain temporary files, even when tests run as root.
        val unusableTempDir = write("not-a-directory", byteArrayOf(1))
        val h = PlainRomHasher.hash(zip.absolutePath, unusableTempDir)
        assertNotNull(h)
        assertTrue(h.fromArchive)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h.md5)
        assertEquals(3L, h.size)
        assertEquals(1L, unusableTempDir.length())
    }

    // An empty leftover ahead of the ROM, both called `game.nes`. The selector
    // refuses the empty one, and the entry read must be the one it chose rather
    // than the first that answers to the name.
    @Test fun `a 7z digests the entry the selector chose, not the first with its name`() {
        val archive = sevenZ("game.7z", "game.nes" to ByteArray(0), "game.nes" to "abc".toByteArray())
        val h = PlainRomHasher.hash(archive.absolutePath, tmp)
        assertNotNull(h)
        assertTrue(h.fromArchive)
        assertEquals("game.nes", h.archiveEntry)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h.md5)
        assertEquals("352441c2", h.crc32)
        assertEquals(3L, h.size)
    }

    // The same pair in a zip, ROM first. java.util.zip can only find an entry by
    // its name and answers with the last, so the empty one was digested and
    // recorded as the ROM. Not readable as chosen, the zip is described by its
    // container, and says so.
    @Test fun `a zip whose chosen entry shares its name is digested as the container`() {
        val archive = zipWithRepeats("game.zip", "game.nes" to ByteArray(40_000) { it.toByte() },
                                                 "game.nes" to ByteArray(0))
        val h = PlainRomHasher.hash(archive.absolutePath, tmp)
        assertNotNull(h)
        assertTrue(!h.fromArchive, "the digests describe the container, and must say so")
        assertEquals("", h.archiveEntry)
        assertEquals(archive.length(), h.size)
    }

    // An empty entry is never taken for the ROM, so an archive holding nothing
    // else is described by its container — and says so.
    @Test fun `an archive holding only an empty entry is digested as the container`() {
        val archive = sevenZ("empty.7z", "empty.nes" to ByteArray(0))
        val h = PlainRomHasher.hash(archive.absolutePath, tmp)
        assertNotNull(h)
        assertTrue(!h.fromArchive)
        assertEquals("", h.archiveEntry)
        assertEquals(archive.length(), h.size)
    }

    // A cancelled lookup must stop, not be "handled" as an extraction that failed
    // and answered with the container's digest. That answer would throw as well,
    // since the digest looks for the interrupt before it reads, so the outcome
    // alone cannot tell the two apart: the failed extraction it logs on the way can.
    @Test fun `an interrupt while reading a zip entry is a cancellation, not a failed extraction`() {
        tmp.mkdirs()
        val zip = File(tmp, "game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("game.nes")); z.write(ByteArray(200_000)); z.closeEntry()
        }
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val previous = BridgeLog.current
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { logs += msg }
            override fun i(tag: String, msg: String) { logs += msg }
            override fun w(tag: String, msg: String, t: Throwable?) { logs += msg }
            override fun e(tag: String, msg: String, t: Throwable?) { logs += msg }
        }
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { PlainRomHasher.hash(zip.absolutePath, tmp) }
        } finally {
            Thread.interrupted()
            BridgeLog.current = previous
        }
        assertTrue(logs.none { it.contains("archive failed") }, "the interrupt was taken for a failed extraction: $logs")
    }

    // In a 7z the interrupt arrives while listing, as the ClosedByInterruptException
    // a FileChannel throws: an IOException, which used to mean "not an archive" and
    // was answered by hashing the whole container. The archive must not be handed
    // on at all, as unreadable or otherwise.
    @Test fun `an interrupt while opening a 7z is a cancellation, not an unreadable archive`() {
        val sz = sevenZ("game.7z", "game.nes" to ByteArray(200_000))
        var handed: ArchiveReader.Opened? = null
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { ArchiveReader.open(sz) { handed = it } }
        } finally {
            Thread.interrupted()
        }
        assertNull(handed, "the 7z was handed on as $handed")
    }

    @Test fun `a ROM misnamed as an archive is hashed anyway`() {
        // The extension is a claim, not a fact, and a plain ROM renamed `.7z` is common
        // enough that refusing it loses real games. A wrong match is impossible here —
        // the worst case is a miss, which is what refusing produces anyway.
        val f = write("liar.7z", "not really an archive".toByteArray())
        val h = PlainRomHasher.hash(f.absolutePath, tmp)
        assertNotNull(h)
        assertEquals(21L, h.size)
        assertTrue(!h.fromArchive)
    }

    @Test fun `a missing file is null rather than an exception`() {
        assertNull(PlainRomHasher.hash(File(tmp, "nope.nes").absolutePath, tmp))
    }

    @Test fun `an empty file still answers`() {
        // A zero-byte placeholder is not a crash and not a ROM. Both test libraries are
        // full of them, so this path is walked hundreds of times per scan — and the
        // digest of nothing is a real digest that simply matches nothing upstream.
        val h = PlainRomHasher.hash(write("empty.nes", ByteArray(0)).absolutePath, tmp)
        assertNotNull(h)
        assertEquals(0L, h.size)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", h.md5)
    }
}
