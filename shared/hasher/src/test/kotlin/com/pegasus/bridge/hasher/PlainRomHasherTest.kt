package com.pegasus.bridge.hasher

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun `the largest entry is the ROM`() {
        val big = ByteArray(4096) { it.toByte() }
        tmp.mkdirs()
        val zip = File(tmp, "multi.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("b.rom")); z.write(big);               z.closeEntry()
            z.putNextEntry(ZipEntry("c.txt")); z.write("yy".toByteArray()); z.closeEntry()
        }
        assertEquals(4096L, PlainRomHasher.hash(zip.absolutePath, tmp)!!.size)
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
