package com.pegasus.bridge.hasher

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/**
 * The byte-level half of hashing a ROM, shared by both hashers.
 *
 * [ArchiveReader] opens archives and reads entries out of them, and
 * [ArchiveSelector] says which entry; this only digests the bytes, copies them
 * when rcheevos needs a file, and names that file. Both hashers had their own
 * MD5/CRC loop before, and neither could be stopped halfway through a disc image.
 */
object RomHashIO {

    data class Digests(val md5: String, val crc32: String, val size: Long)

    fun digest(file: File, checkCancelled: () -> Unit = ::checkInterrupted): Digests {
        checkCancelled()
        return file.inputStream().use { copyAndDigest(it, checkCancelled = checkCancelled) }
    }

    /**
     * Reads [input] once, digesting it and, when [output] is given, copying it
     * there in the same pass — so an entry extracted for rcheevos is not written
     * out and then read back just to be digested.
     *
     * [checkCancelled] runs before every 64 KiB buffer. A disc image takes minutes
     * to read, and an interrupt that only lands once the whole file has been read
     * is not much of a cancellation. The streams belong to the caller and are not
     * closed here.
     */
    fun copyAndDigest(
        input: InputStream,
        output: OutputStream? = null,
        checkCancelled: () -> Unit = ::checkInterrupted
    ): Digests {
        val md5 = MessageDigest.getInstance("MD5")
        val crc32 = CRC32()
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output?.write(buffer, 0, count)
            md5.update(buffer, 0, count)
            crc32.update(buffer, 0, count)
            size += count
        }
        checkCancelled()
        return Digests(md5.digest().toHex(), crc32.value.toString(16).padStart(8, '0'), size)
    }

    /**
     * The suffix for the temporary copy of [entryName]: its own extension.
     *
     * rcheevos chooses how to hash a file from its extension. Handed `.bin`, an
     * iNES ROM is hashed whole, header included, as a Mega Drive cartridge would
     * be — a confident hash that matches nothing. Only the last path segment
     * counts, and only a plain alphanumeric extension is kept: `Game.v1/rom`
     * would otherwise put a directory separator into the temporary file's name.
     */
    fun tempSuffix(entryName: String): String {
        val name = entryName.substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', "")
        return if (extension.length in 1..16 && extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            ".${extension.lowercase(Locale.ROOT)}"
        } else ".bin"
    }

    /**
     * Rethrows [t] when it is a cancellation, or what an interrupt looks like from
     * inside IO.
     *
     * Every hasher catches Throwable around an archive, and answers by hashing the
     * container instead. A 7z is read through a FileChannel, which an interrupt
     * closes, so a cancelled scan arrives there as ClosedByInterruptException —
     * an IOException — and would have been "handled" by reading the whole
     * container it had just been told to stop reading.
     */
    fun rethrowIfCancelled(t: Throwable) {
        if (t is CancellationException) throw t
        if (Thread.currentThread().isInterrupted)
            throw CancellationException("ROM hashing interrupted").apply { initCause(t) }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("ROM hashing interrupted")
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val chars = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            chars[i * 2] = digits[value ushr 4]
            chars[i * 2 + 1] = digits[value and 0xf]
        }
        return String(chars)
    }
}
