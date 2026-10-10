package com.pegasus.bridge.hasher

import org.tukaani.xz.FinishableWrapperOutputStream
import org.tukaani.xz.LZMA2Options
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Writes a solid 7z: every entry in one compressed stream, which is how
 * 7-Zip packs a folder unless it is told otherwise, and so what a disc
 * somebody packed is likely to be.
 *
 * commons-compress reads such an archive and cannot write one. Its
 * SevenZOutputFile gives each entry a stream of its own, and an entry is
 * then reached without a byte of the others being read. In a solid archive
 * an entry is reached by decompressing everything that lies before it, and
 * the order entries are read in decides whether that is done once or once
 * for each. The tests of a disc taken out of an archive are about that
 * order, so they need the real thing.
 *
 * The format is 7-Zip's own (7zFormat.txt in its sources): a fixed header
 * of 32 bytes, the compressed stream, and after it a header that says what
 * the stream holds. One stream, one LZMA2 coder, and the entries as its
 * parts in the order given.
 */
internal object SolidSevenZ {

    /** The LZMA2 dictionary, and the one byte the header says it in: 2 shl (16 / 2 + 11). */
    private const val DICTIONARY = 1 shl 20
    private const val DICTIONARY_BYTE = 16

    fun write(file: File, vararg entries: Pair<String, ByteArray>): File {
        require(entries.isNotEmpty()) { "a solid archive of nothing has no stream to be solid in" }
        val plain = ByteArrayOutputStream().apply { entries.forEach { write(it.second) } }.toByteArray()
        val packed = ByteArrayOutputStream().also { out ->
            LZMA2Options().apply { dictSize = DICTIONARY }
                .getOutputStream(FinishableWrapperOutputStream(out)).use { it.write(plain) }
        }.toByteArray()

        val header = ByteArrayOutputStream().apply {
            write(0x01)                                         // Header
            write(0x04)                                         // MainStreamsInfo
            write(0x06); number(0); number(1)                   // PackInfo: one stream, at the start
            write(0x09); number(packed.size.toLong()); write(0x00)
            write(0x07); write(0x0B); number(1); write(0)       // UnpackInfo: one folder, described here
            number(1)                                           // of one coder:
            write(0x21); write(0x21); number(1); write(DICTIONARY_BYTE)   // LZMA2 and its one property
            write(0x0C); number(plain.size.toLong())
            write(0x00)
            write(0x08)                                         // SubStreamsInfo
            write(0x0D); number(entries.size.toLong())          // how many entries the stream is
            write(0x09); entries.dropLast(1).forEach { number(it.second.size.toLong()) }
            write(0x0A); write(1); entries.forEach { le(crc(it.second), bytes = 4) }
            write(0x00)
            write(0x00)
            write(0x05); number(entries.size.toLong())          // FilesInfo
            val names = entries.joinToString("") { it.first + "\u0000" }.toByteArray(Charsets.UTF_16LE)
            write(0x11); number(names.size + 1L); write(0); write(names)
            write(0x00)
            write(0x00)
        }.toByteArray()

        val start = ByteArrayOutputStream().apply {
            le(packed.size.toLong(), bytes = 8)                 // where the header is, after these 32 bytes
            le(header.size.toLong(), bytes = 8)
            le(crc(header), bytes = 4)
        }.toByteArray()

        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4))
            out.write(ByteArrayOutputStream().apply { le(crc(start), bytes = 4) }.toByteArray())
            out.write(start)
            out.write(packed)
            out.write(header)
        }
        return file
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun ByteArrayOutputStream.le(value: Long, bytes: Int) {
        for (i in 0 until bytes) write((value ushr (8 * i)).toInt() and 0xFF)
    }

    /**
     * A number as a 7z header writes one: the count of bytes that follow is
     * the count of high bits set in the first, and what is left of the first
     * byte is the top of the value.
     */
    private fun ByteArrayOutputStream.number(value: Long) {
        var first = 0
        var mask = 0x80
        var extra = 0
        while (extra < 8) {
            if (value < (1L shl (7 * (extra + 1)))) {
                first = first or (value ushr (8 * extra)).toInt()
                break
            }
            first = first or mask
            mask = mask ushr 1
            extra++
        }
        write(first)
        for (i in 0 until extra) write((value ushr (8 * i)).toInt() and 0xFF)
    }
}
