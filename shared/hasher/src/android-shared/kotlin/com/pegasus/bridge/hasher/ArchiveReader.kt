package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.io.InputStream
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
 * reads. Digesting what comes out is [RomHashIO]'s.
 */
object ArchiveReader {

    /** Extensions this can open. Anything else is a plain file. */
    val ARCHIVE_EXTENSIONS = setOf("zip", "7z")

    fun isArchive(file: File): Boolean = file.extension.lowercase() in ARCHIVE_EXTENSIONS

    sealed interface Opened {
        /**
         * The listing of an archive that is still open, and the way back into it.
         *
         * [read] wants one of [entries] itself, not an equal copy, and reads that
         * very entry. It used to be found again by name, in an archive opened a
         * second time — and two entries can share a name, which the 7z format does
         * not forbid. The walk stopped at the first of them, so with an empty
         * leftover ahead of the ROM the selector had chosen, the leftover was
         * extracted under the ROM's name and its digest recorded as the ROM's.
         */
        class Entries internal constructor(
            val entries: List<ArchiveSelector.Entry>,
            private val stream: (index: Int) -> InputStream
        ) : Opened {
            fun <T> read(entry: ArchiveSelector.Entry, block: (InputStream) -> T): T {
                val index = entries.indexOfFirst { it === entry }
                require(index >= 0) { "'${entry.name}' is not an entry of this listing" }
                return stream(index).use(block)
            }
        }

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

    /**
     * Opens [file] once, lists it, and hands the listing to [use], which reads
     * the entry it picks before returning. The archive is closed afterwards.
     *
     * Once, rather than once to list and again to extract: reading an entry by
     * its object is only possible from the archive that produced it, and a 7z
     * with compressed headers decompresses them on every open.
     *
     * Only a failure to open or list makes the file [Opened.Unreadable]. Whatever
     * [use] throws is the caller's — an entry that will not decompress says
     * nothing about whether this is an archive — and a cancellation is never
     * mistaken for either.
     */
    fun <T> open(file: File, use: (Opened) -> T): T {
        var listed = false
        try {
            when (file.extension.lowercase()) {
                "zip" -> return ZipFile(file).use { zf ->
                    val native = zf.entries().toList()
                    val opened = Opened.Entries(native.map {
                        ArchiveSelector.Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
                    }) { i ->
                        // java.util.zip looks an entry up by its name whatever object it
                        // is handed — the JDK answers with the last of two same-named
                        // entries — so those are not told apart here. Its own writer
                        // refuses to produce such a zip, and the library has none.
                        zf.getInputStream(native[i])
                            ?: throw IOException("'${native[i].name}' vanished from ${file.name}")
                    }
                    listed = true
                    use(opened)
                }
                "7z" -> return SevenZFile(file).use { sz ->
                    val native = sz.entries.toList()
                    val opened = Opened.Entries(native.map {
                        ArchiveSelector.Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
                    }) { i ->
                        // By the entry object, which SevenZFile matches by identity:
                        // the one the selector chose, whatever else shares its name.
                        sz.getInputStream(native[i])
                    }
                    listed = true
                    use(opened)
                }
            }
        } catch (t: Throwable) {
            if (listed) throw t
            RomHashIO.rethrowIfCancelled(t)
            // Throwable, not Exception: commons-compress declares xz as *optional*, so
            // a 7z using LZMA2 — which is most of them — arrives as NoClassDefFoundError,
            // which is an Error. Catching only Exception let it escape and killed a whole
            // scan over one archive.
            return use(Opened.Unreadable(t.message ?: t.javaClass.simpleName))
        }
        return use(Opened.Unreadable("not an archive extension"))
    }
}
