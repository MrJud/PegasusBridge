package com.pegasus.bridge.hasher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The one MD5/CRC32 loop both hashers now share, and the name rcheevos is handed. */
class RomHashIOTest {

    @Test fun `copy and digests cover every buffer of the same byte stream`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val copied = RomHashIO.copyAndDigest(ByteArrayInputStream(bytes), output)
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), copied.size)
        // Computed independently (Python hashlib and zlib) and fixed, so a
        // truncated last buffer or a lost leading zero shows up as a mismatch.
        assertEquals("415d6e662118c229c6ad3f950c24702a", copied.md5)
        assertEquals("a745c145", copied.crc32)
    }

    @Test fun `nothing still has a digest, and the crc keeps its eight digits`() {
        // An empty entry or a zero-byte placeholder is common in both libraries.
        // Its digest is a real one that matches nothing, not an error.
        val empty = RomHashIO.copyAndDigest(ByteArrayInputStream(ByteArray(0)))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", empty.md5)
        assertEquals("00000000", empty.crc32)
        assertEquals(0L, empty.size)
    }

    @Test fun `a file is digested to its last byte`() {
        val f = Files.createTempFile("romhashio", ".nes").toFile()
        try {
            f.writeText("abc")
            val d = RomHashIO.digest(f)
            assertEquals("900150983cd24fb0d6963f7d28e17f72", d.md5)
            assertEquals("352441c2", d.crc32)
            assertEquals(3L, d.size)
        } finally {
            f.delete()
        }
    }

    @Test fun `cancellation stops copying before the entire ROM is read`() {
        val bytes = ByteArray(200_000)
        val output = ByteArrayOutputStream()
        var checks = 0
        assertFailsWith<CancellationException> {
            RomHashIO.copyAndDigest(ByteArrayInputStream(bytes), output) {
                if (++checks == 3) throw CancellationException("cancelled")
            }
        }
        assertTrue(output.size() in 1 until bytes.size)
    }

    // runInterruptible delivers a coroutine's cancellation as a thread interrupt,
    // so that is what the default check has to recognise.
    @Test fun `an interrupted thread is a cancellation by default`() {
        val f = Files.createTempFile("romhashio", ".nes").toFile()
        try {
            f.writeBytes(ByteArray(200_000))
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { RomHashIO.digest(f) }
        } finally {
            Thread.interrupted()
            f.delete()
        }
    }

    @Test fun `temporary suffix uses only the archive entry file extension`() {
        assertEquals(".nes", RomHashIO.tempSuffix("folder/game.NES"))
        assertEquals(".gb", RomHashIO.tempSuffix("folder\\game.GB"))
        assertEquals(".v64", RomHashIO.tempSuffix("Mario (USA).v64"))
        // A dot in a directory is not an extension, and must not put a path
        // separator into the temporary file's name.
        assertEquals(".bin", RomHashIO.tempSuffix("folder.nes/game"))
        assertEquals(".bin", RomHashIO.tempSuffix("Game.v1/rom"))
        assertEquals(".bin", RomHashIO.tempSuffix("game"))
        assertEquals(".bin", RomHashIO.tempSuffix("game."))
        assertEquals(".bin", RomHashIO.tempSuffix("game.invalid suffix"))
        assertEquals(".bin", RomHashIO.tempSuffix("game.abcdefghijklmnopq"))
    }

    // rcheevos has no handler for these, and hashes the whole file of an extension
    // it does not know. As `.bin`, a raw disc image over 32 MiB is tried as a CD
    // track first; ArchiveSelector accepts `img` and `mdf` for four disc systems.
    @Test fun `an extension rcheevos has no handler for is handed over as bin`() {
        for (name in listOf("Disc.img", "Disc.IMG", "Disc.mdf", "Disc.ecm", "Game.unknownext", "weird.ñes"))
            assertEquals(".bin", RomHashIO.tempSuffix(name), name)
        for ((name, suffix) in listOf("Disc.iso" to ".iso", "Disc.CUE" to ".cue", "Disc.chd" to ".chd",
                                      "Game.pbp" to ".pbp", "Game.md" to ".md", "Game.z64" to ".z64"))
            assertEquals(suffix, RomHashIO.tempSuffix(name), name)
    }

    // An arcade set is hashed by its file name, and the copy's is random.
    @Test fun `a nested zip or 7z is handed over as bin, not as an arcade set`() {
        assertEquals(".bin", RomHashIO.tempSuffix("mslug.zip"))
        assertEquals(".bin", RomHashIO.tempSuffix("mslug.7z"))
    }

    /**
     * The kept extensions are rcheevos's own table, read from the vendored
     * hash.c, so moving to another rcheevos cannot leave the copy behind
     * unnoticed. Gradle runs tests from the module directory, shared/hasher.
     */
    @Test fun `the extensions kept are exactly those rcheevos has a handler for`() {
        val hashC = File("../../hasher/src/main/cpp/rcheevos/src/rhash/hash.c")
        assertTrue(hashC.isFile, "the vendored rcheevos is not at ${hashC.absolutePath}")
        val source = hashC.readText()
        val table = source.substringAfter("rc_hash_iterator_ext_handlers[] = {").substringBefore("};")
        val handled = Regex("""\{\s*"([^"]+)"""").findAll(table).map { it.groupValues[1] }.toSet()
        assertTrue(handled.size > 50, "the table was not found in ${hashC.absolutePath}: $handled")
        assertEquals(handled - setOf("zip", "7z"), RomHashIO.RCHEEVOS_EXTENSIONS)
    }

    @Test fun `a suffix it produces is one the JDK will create`() {
        val dir = Files.createTempDirectory("romhashio").toFile()
        try {
            for (name in listOf("a/b.NES", "x.v1/rom", "weird.ñes", "c:\\roms\\d.Lnx")) {
                val f = File.createTempFile("bridge_", RomHashIO.tempSuffix(name), dir)
                assertEquals(dir, f.parentFile)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
