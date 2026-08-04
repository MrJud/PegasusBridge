package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

data class HashResult(val hash: String, val consoleId: Int)

/**
 * Computes a RetroAchievements-compatible hash for one ROM file.
 *
 * The implementation is native (rcheevos), and how it is reached differs per
 * platform: on Android the `.so` ships inside the APK and is loaded by the
 * classloader, on desktop it is a system library or a helper binary. Hence the
 * interface — the pipeline must not care.
 *
 * The native code itself is identical everywhere: the same ROM produces the same
 * hash from the x86_64 and the arm64 builds.
 */
interface RomHasher {
    fun hash(path: String): HashResult?
}

/**
 * Wraps a [RomHasher] with archive support: zip and 7z are extracted to a temp
 * file first, because the native hasher works on plain files.
 *
 * The largest entry is the one hashed — archives generally hold one ROM plus
 * small extras.
 */
class ArchiveAwareHasher(
    private val delegate: RomHasher,
    private val tempDir: File
) : RomHasher {

    override fun hash(path: String): HashResult? {
        val file = File(path)
        return when (file.extension.lowercase()) {
            "zip" -> hashArchive(file, ::zipLargest)
            "7z"  -> hashArchive(file, ::sevenZLargest)
            else  -> delegate.hash(path)
        }
    }

    private fun hashArchive(file: File, extract: (File, File) -> Boolean): HashResult? = try {
        tempDir.mkdirs()
        val tmp = File.createTempFile("bridge_", ".bin", tempDir)
        try {
            if (extract(file, tmp)) delegate.hash(tmp.absolutePath) else null
        } finally {
            tmp.delete()
        }
    } catch (e: Exception) {
        BridgeLog.e(TAG, "archive failed: ${file.name}", e)
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

    private companion object {
        const val TAG = "ArchiveAwareHasher"
    }
}
