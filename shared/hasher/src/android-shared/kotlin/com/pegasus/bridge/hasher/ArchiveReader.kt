package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

/**
 * Reading zip and 7z, in one place.
 *
 * Both hashers needed it and both grew their own copy — same "largest entry"
 * rule, same 7z walk, same `Throwable` catch for the missing-codec case, written
 * twice. A policy change then had to be made twice, which is how the two came to
 * disagree about what counts as a ROM in the first place.
 *
 * What is *selected* is [ArchiveSelector]'s decision; this only opens, lists and
 * extracts.
 */
object ArchiveReader {

    /** Extensions this can open. Anything else is a plain file. */
    val ARCHIVE_EXTENSIONS = setOf("zip", "7z")

    fun isArchive(file: File): Boolean = file.extension.lowercase() in ARCHIVE_EXTENSIONS

    sealed interface Opened {
        data class Entries(val entries: List<ArchiveSelector.Entry>) : Opened

        /**
         * The file could not be read as the archive its extension claims.
         *
         * Two quite different causes, deliberately not separated: a truly corrupt
         * archive, and a plain ROM somebody renamed `.7z`. Both are handled the
         * same way — hash the file as it lies — because an extension is a claim
         * rather than a fact, and refusing renamed ROMs loses real games while the
         * fallback can only ever produce a miss.
         */
        data class Unreadable(val reason: String) : Opened
    }

    fun list(file: File): Opened = try {
        when (file.extension.lowercase()) {
            "zip" -> ZipFile(file).use { zf ->
                Opened.Entries(zf.entries().asSequence().map {
                    ArchiveSelector.Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
                }.toList())
            }
            "7z" -> SevenZFile(file).use { sz ->
                Opened.Entries(sz.entries.map {
                    ArchiveSelector.Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
                })
            }
            else -> Opened.Unreadable("not an archive extension")
        }
    } catch (t: Throwable) {
        // Throwable, not Exception: commons-compress declares xz as *optional*, so
        // a 7z using LZMA2 — which is most of them — arrives as NoClassDefFoundError,
        // which is an Error. Catching only Exception let it escape and killed a whole
        // scan over one archive.
        if (t is kotlinx.coroutines.CancellationException) throw t
        Opened.Unreadable(t.message ?: t.javaClass.simpleName)
    }

    /**
     * Writes one named entry to [out]. False when it is not there or cannot be read.
     *
     * A 7z is walked rather than indexed because `SevenZFile` only streams the
     * *current* entry — asking for one by name means advancing to it.
     */
    fun extract(file: File, entryName: String, out: File): Boolean = try {
        when (file.extension.lowercase()) {
            "zip" -> ZipFile(file).use { zf ->
                val e = zf.getEntry(entryName)
                if (e == null) false else {
                    zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    true
                }
            }
            "7z" -> SevenZFile(file).use { sz ->
                var entry = sz.nextEntry
                while (entry != null && entry.name != entryName) entry = sz.nextEntry
                if (entry == null) false else {
                    sz.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    true
                }
            }
            else -> false
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.w(TAG, "could not extract '$entryName' from ${file.name}: ${t.message}")
        false
    }

    private const val TAG = "ArchiveReader"
}
