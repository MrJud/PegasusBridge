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
     * The suffix for the temporary copy of [entryName]: its own extension when
     * rcheevos has a handler for it, `.bin` when it has none.
     *
     * rcheevos chooses how to hash a file from its extension. Handed `.bin`, an
     * iNES ROM is hashed whole, header included, as a Mega Drive cartridge would
     * be — a confident hash that matches nothing. But an extension it has no
     * handler for is worse than `.bin`, not better: rcheevos then hashes the
     * whole file, while a `.bin` over 32 MiB is first tried as a CD track. So a
     * raw disc image named `.img` or `.mdf` that kept its extension got the MD5
     * of the whole file, and as `.bin` it gets the disc's hash. Under 32 MiB both
     * are the same whole-file MD5, so `.bin` is never the worse of the two.
     *
     * Only the last path segment counts: `Game.v1/rom` has no extension, and a
     * directory separator never reaches the temporary file's name.
     */
    fun tempSuffix(entryName: String): String {
        val name = entryName.substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in RCHEEVOS_EXTENSIONS) ".$extension" else ".bin"
    }

    /**
     * The extensions rcheevos has a handler for, copied entry by entry from
     * `rc_hash_iterator_ext_handlers` in the vendored rcheevos v12.3.0
     * (hasher/src/main/cpp/rcheevos/src/rhash/hash.c). Moving to another
     * rcheevos means comparing this with its table again.
     *
     * Less two: rcheevos hashes a `zip` or a `7z` by its file name, as an arcade
     * set (rc_hash_arcade), and the temporary copy is called `bridge_<random>`.
     * An entry with either extension is never selected anyway, but if one were,
     * `.bin` at least gives the same answer twice.
     */
    internal val RCHEEVOS_EXTENSIONS = setOf(
        "2d", "3ds", "3dsx", "83g", "83p", "a26", "a78", "app", "arduboy", "axf",
        "bin", "bs", "cart", "cas", "cci", "chd", "chf", "cia", "col", "csw",
        "cue", "cxi", "d64", "d88", "dosz", "dsk", "elf", "fd", "fds", "fig",
        "gb", "gba", "gbc", "gdi", "gg", "hex", "iso", "jag", "k7", "lnx",
        "m3u", "m5", "m7", "md", "min", "mx1", "mx2", "n64", "ndd", "nds",
        "nes", "ngc", "nib", "pbp", "pce", "pgm", "pzx", "ri", "rom", "sap",
        "scl", "sfc", "sg", "sgx", "smc", "sv", "swc", "tap", "tic", "trd",
        "tvc", "tzx", "uze", "v64", "vb", "wad", "wasm", "woz", "wsc", "z64"
    )

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
