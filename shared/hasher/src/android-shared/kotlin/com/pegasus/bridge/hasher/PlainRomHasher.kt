package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * MD5, CRC32 and size of a ROM, with no rcheevos involved.
 *
 * [ArchiveAwareHasher] already produces these — but only as a passenger on the
 * RetroAchievements hash, which is native: `withPlainHashes` is never reached when
 * `delegate.hash()` returns null, and on desktop the native library is **optional**
 * (`BridgeDaemon` starts and serves everything else without it). Hanging ScreenScraper
 * off that path would make the whole source disappear on any machine missing a `.so`,
 * for a reason that has nothing to do with ScreenScraper.
 *
 * So this computes the same three values on its own. It is deliberately *not* a
 * [RomHasher]: that interface promises a console id and an RA-compatible hash, and this
 * has neither to give.
 *
 * ── What gets hashed ──────────────────────────────────────────
 *
 * The ROM **inside** the archive, never the container: a scraper asked about a zip's MD5
 * matches nothing, because the databases (No-Intro, Redump) list the ROM. Same rule and
 * same reason as [ArchiveAwareHasher], and the same fallback — a failed extraction
 * hashes the file as it lies, because an extension is a claim rather than a fact and a
 * plain ROM named `.7z` is common enough that refusing it loses real games.
 *
 * The **name** is the archive's, though, not the entry's: it is what MAME identifies a
 * romset by, and [FileHashes.name] is what a `romnom` lookup sends.
 */
object PlainRomHasher {

    /**
     * [name] is the file's own name — `pacman.zip`, not the entry inside it.
     * [fromArchive] records whether the digest describes an extracted entry, which is
     * the difference between "these hashes mean something" and "these hashes describe a
     * zip" for anyone reading a log.
     */
    data class FileHashes(
        val name: String,
        val md5: String,
        val crc32: String,
        val size: Long,
        val fromArchive: Boolean
    )

    /** Null only when the file cannot be read at all. */
    fun hash(path: String, tempDir: File): FileHashes? {
        val file = File(path)
        if (!file.isFile) {
            BridgeLog.w(TAG, "no such file: $path")
            return null
        }
        return when (file.extension.lowercase()) {
            "zip" -> fromArchive(file, tempDir, ::zipLargest) ?: digest(file, file.name, false)
            "7z"  -> fromArchive(file, tempDir, ::sevenZLargest) ?: digest(file, file.name, false)
            else  -> digest(file, file.name, false)
        }
    }

    private fun fromArchive(
        file: File,
        tempDir: File,
        extract: (File, File) -> Boolean
    ): FileHashes? = try {
        tempDir.mkdirs()
        val tmp = File.createTempFile("ss_", ".bin", tempDir)
        try {
            if (extract(file, tmp)) digest(tmp, file.name, true) else null
        } finally {
            tmp.delete()
        }
    } catch (t: Throwable) {
        // Throwable, not Exception: a missing optional codec arrives as
        // NoClassDefFoundError. The scan learned that one the hard way.
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.w(TAG, "archive failed: ${file.name}: ${t.message}")
        null
    }

    private fun digest(target: File, name: String, fromArchive: Boolean): FileHashes? = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        val crc = CRC32()
        var size = 0L
        target.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                crc.update(buf, 0, n)
                size += n
            }
        }
        FileHashes(
            name = name,
            md5 = md.digest().joinToString("") { "%02x".format(it) },
            crc32 = "%08x".format(crc.value),
            size = size,
            fromArchive = fromArchive
        )
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.w(TAG, "hash failed: ${target.name}: ${t.message}")
        null
    }

    private fun zipLargest(archive: File, out: File): Boolean =
        ZipFile(archive).use { zf ->
            val largest = zf.entries().asSequence()
                .filter { !it.isDirectory }
                .maxByOrNull { it.size } ?: return false
            zf.getInputStream(largest).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            true
        }

    private fun sevenZLargest(archive: File, out: File): Boolean =
        SevenZFile(archive).use { sz ->
            val largest = sz.entries
                .filter { !it.isDirectory && it.size > 0 }
                .maxByOrNull { it.size } ?: return false
            // SevenZFile only streams the current entry, so walk to the target.
            var entry = sz.nextEntry
            while (entry != null && entry.name != largest.name) entry = sz.nextEntry
            if (entry == null) return false
            sz.getInputStream(entry).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            true
        }

    private const val TAG = "PlainRomHasher"
}
