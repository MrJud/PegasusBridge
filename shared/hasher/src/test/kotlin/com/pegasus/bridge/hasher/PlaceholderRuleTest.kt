package com.pegasus.bridge.hasher

import com.pegasus.bridge.hasher.PlaceholderRule.Kind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule that tells a placeholder from a ROM, on bytes made here. The
 * stubs are modelled on the ones a tablet's library was found to hold: one
 * sentence, no line end, under the extension of the console it names.
 */
class PlaceholderRuleTest {

    /** A sentence of the shape the real stubs have, for a game nobody made. */
    private val stub = "Placeholder for Lantern Keep on GameBoy Advance".toByteArray()

    private fun classify(extension: String, bytes: ByteArray): Kind? =
        PlaceholderRule.classify(extension, bytes.size.toLong()) { bytes }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                // Stored, so that the size of the archive is the same on every JVM.
                z.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    compressedSize = content.size.toLong()
                    crc = CRC32().apply { update(content) }.value
                })
                z.write(content); z.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `what is and is not a placeholder`() {
        assertEquals(47, stub.size)

        // Nothing in it: a placeholder whatever it is called, a sheet's name included.
        for (extension in listOf("jud", "nes", "cue", "zip", "m3u", ""))
            assertEquals(Kind.EMPTY, classify(extension, ByteArray(0)), "an empty .$extension")

        // A sentence under a ROM's name. A zip is not let off: a line of
        // text was never an archive. Nor is a name in capitals.
        for (extension in listOf("gbc", "gg", "md", "32x", "bin", "zip", "7z", "GBC", "jud", ""))
            assertEquals(Kind.TEXT_STUB, classify(extension, stub), "a sentence called .$extension")
        val accents = "Placeholder for Café Señorita — Edición Añeja on Game Gear"
        assertEquals(Kind.TEXT_STUB, classify("gg", accents.toByteArray(Charsets.UTF_8)), "accents in UTF-8")
        assertEquals(Kind.TEXT_STUB, classify("gbc", "\uFEFFPlaceholder\tfor a game\r\n".toByteArray()),
                     "a byte order mark, a tab and a line end")
        assertEquals(Kind.TEXT_STUB, classify("gbc", "ゲームのプレースホルダー \uD842\uDFB7".toByteArray()),
                     "three and four bytes to a character")
        assertEquals(Kind.TEXT_STUB, classify("gbc", ByteArray(512) { 'x'.code.toByte() }), "512 bytes of text")

        // Text that is what its name says: the sheets and playlists a disc
        // is read through, in either case.
        val playlist = "Lantern Keep.cue\r\n".toByteArray()
        assertEquals(18, playlist.size)
        assertNull(classify("m3u", playlist), "a playlist of one line")
        val sheet = "FILE \"Lantern Keep (USA).bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n"
            .padEnd(100, ' ').toByteArray()
        assertEquals(100, sheet.size)
        for (extension in listOf("cue", "CUE", "gdi", "ccd", "toc", "hex"))
            assertNull(classify(extension, sheet), "text called .$extension")

        // Bytes no text has.
        val emptyZip = zipOf()
        assertEquals(22, emptyZip.size)
        assertNull(classify("zip", emptyZip), "a zip with no entry")
        val smallZip = zipOf("Lantern Keep.nes" to ByteArray(30) { 'x'.code.toByte() })
        assertEquals(160, smallZip.size)
        assertNull(classify("zip", smallZip), "a zip of one small entry")
        assertNull(classify("bin", ByteArray(256) { it.toByte() }), "every byte there is")
        assertNull(classify("nes", byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 0x01, 0x01)), "an iNES header cut short")
        assertNull(classify("gbc", stub + 0), "a sentence and one NUL")
        assertNull(classify("gbc", stub + 0x7F), "a sentence and a DEL")
        assertNull(classify("gbc", stub + 0x1B), "a sentence and an escape")

        // Too long to be a sentence somebody left in place of a game.
        assertNull(classify("gbc", ByteArray(513) { 'x'.code.toByte() }), "513 bytes of text")
        assertNull(classify("gbc", ByteArray(600) { 'x'.code.toByte() }), "600 bytes of text")

        // An accent written as Latin-1 is one byte that is not UTF-8, and
        // one such byte is as likely a program's as a letter: left to be
        // hashed, as it was.
        val latin1 = "Placeholder for Café Racer on Game Gear".toByteArray(Charsets.ISO_8859_1)
        assertEquals(1, latin1.count { it == 0xE9.toByte() })
        assertNull(classify("gg", latin1), "an accent in Latin-1")
    }

    // What a decoder that forgives would let through: each is a run of bytes
    // above 0x7F that looks like UTF-8 and is not. On the desktop and on
    // Android alike, since the rule decodes by its own table.
    @Test fun `bytes that only look like UTF-8 are not text`() {
        fun bytes(vararg b: Int) = stub + ByteArray(b.size) { b[it].toByte() }
        val malformed = mapOf(
            "a continuation byte alone" to bytes(0x80),
            "two bytes cut after the first" to bytes(0xC3),
            "three bytes cut after the second" to bytes(0xE3, 0x82),
            "four bytes cut after the third" to bytes(0xF0, 0x9F, 0x8E),
            "a lead byte and a letter" to bytes(0xC3, 0x41),
            "a lead byte and another" to bytes(0xC3, 0xE9),
            "three bytes, the last of them none of a sequence" to bytes(0xE3, 0x82, 0xFF),
            "NUL written in two bytes" to bytes(0xC0, 0x80),
            "a slash written in two bytes" to bytes(0xC1, 0xAF),
            "a letter written in three bytes" to bytes(0xE0, 0x81, 0x81),
            "half of a surrogate pair" to bytes(0xED, 0xA0, 0x80),
            "a character written in four bytes that needs three" to bytes(0xF0, 0x80, 0x80, 0x80),
            "past the last code point" to bytes(0xF4, 0x90, 0x80, 0x80),
            "the first lead byte past the table" to bytes(0xF5, 0x80, 0x80, 0x80),
            "a lead byte no sequence has" to bytes(0xF8, 0x88, 0x80, 0x80, 0x80),
            "0xFF" to bytes(0xFF))
        assertEquals(emptyList(), malformed.filter { (_, b) -> classify("gbc", b) != null }.keys.toList())

        // The edges of the same table, from the right side.
        val wellFormed = mapOf(
            "U+0080" to bytes(0xC2, 0x80), "U+07FF" to bytes(0xDF, 0xBF),
            "U+0800" to bytes(0xE0, 0xA0, 0x80), "U+D7FF" to bytes(0xED, 0x9F, 0xBF),
            "U+E000" to bytes(0xEE, 0x80, 0x80), "U+FFFF" to bytes(0xEF, 0xBF, 0xBF),
            "U+10000" to bytes(0xF0, 0x90, 0x80, 0x80), "U+10FFFF" to bytes(0xF4, 0x8F, 0xBF, 0xBF))
        assertEquals(emptyList(), wellFormed.filter { (_, b) -> classify("gbc", b) != Kind.TEXT_STUB }.keys.toList())
    }

    // The rule is given a way to read and not the file, so that what it
    // costs can be seen: nothing where the size or the name has decided, and
    // one read where it has not. A file that will not be read, or that is
    // not the size it was said to be, is left to the hasher, which says why.
    @Test fun `a file is read once, and only when it could be a stub`() {
        var reads = 0
        fun counted(extension: String, size: Long, bytes: ByteArray = stub) =
            PlaceholderRule.classify(extension, size) { reads++; bytes }

        assertEquals(Kind.EMPTY, counted("gbc", 0))
        assertNull(counted("gbc", 513))
        assertNull(counted("iso", 4_700_000_000))
        assertNull(counted("cue", 47))
        assertNull(counted("M3U", 47))
        assertEquals(0, reads, "a file was read that its size or its name had answered for")

        assertEquals(Kind.TEXT_STUB, counted("gbc", 47))
        assertEquals(1, reads)

        assertNull(PlaceholderRule.classify("gbc", 47) { throw IOException("Permission denied") },
                   "a file that cannot be read")
        assertNull(PlaceholderRule.classify("gbc", 47) { throw SecurityException("not allowed") },
                   "a file nobody may read")
        assertNull(counted("gbc", 47, stub.copyOf(46)), "a file shorter than it was said to be")
        assertNull(counted("gbc", 47, stub + stub), "a file longer than it was said to be")
        assertNull(counted("gbc", 47, ByteArray(0)), "a file emptied since")
    }
}
