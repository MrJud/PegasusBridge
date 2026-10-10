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
         * [read] wants one of [entries] itself, not an equal copy. From a 7z it
         * reads that very entry. It used to be found again by name, in an archive
         * opened a second time — and two entries can share a name, which the 7z
         * format does not forbid. The walk stopped at the first of them, so with
         * an empty leftover ahead of the ROM the selector had chosen, the leftover
         * was extracted under the ROM's name and its digest recorded as the ROM's.
         *
         * From a zip it cannot: java.util.zip finds an entry by its name, whatever
         * object it is handed. So an entry whose name another entry shares is not
         * read at all — [read] throws an IOException — because what came back
         * could be the other one.
         */
        class Entries internal constructor(
            val entries: List<ArchiveSelector.Entry>,
            private val stream: (index: Int) -> InputStream
        ) : Opened {
            fun <T> read(entry: ArchiveSelector.Entry, block: (InputStream) -> T): T =
                stream(indexOf(entry)).use(block)

            /**
             * Reads each of [chosen] once and hands it to [sink], in the
             * order the entries lie in the archive, whatever order they are
             * asked for in.
             *
             * For a disc, whose sheet and tracks have to come out together.
             * A 7z is usually solid: its entries are compressed as one
             * stream, and an entry is reached by decompressing all that lie
             * before it. Going forward, each is met on the way to the next
             * and the stream is gone through once. Going back means starting
             * it again from its first byte, once for every step back, and
             * for a disc of several tracks that is the disc several times.
             *
             * As with [read], these are entries of this listing themselves,
             * and a zip entry whose name another shares is not read.
             */
            fun readMany(chosen: List<ArchiveSelector.Entry>, sink: (ArchiveSelector.Entry, InputStream) -> Unit) {
                for (index in chosen.map { indexOf(it) }.distinct().sorted())
                    stream(index).use { sink(entries[index], it) }
            }

            private fun indexOf(entry: ArchiveSelector.Entry): Int {
                val index = entries.indexOfFirst { it === entry }
                require(index >= 0) { "'${entry.name}' is not an entry of this listing" }
                return index
            }
        }

        /**
         * The file could not be read as the archive its extension claims.
         *
         * Two quite different causes, deliberately not separated: a truly corrupt
         * archive, and a plain ROM somebody renamed `.7z`. What is done about
         * either is the caller's affair. [PlainRomHasher] digests the file as it
         * lies, because an extension is a claim rather than a fact. For rcheevos
         * the file is not hashed at all ([ArchiveAwareHasher]): handed a file
         * called `.7z` it hashes the name, whatever the bytes are.
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
                    val sameName = native.groupingBy { it.name }.eachCount()
                    val opened = Opened.Entries(native.map {
                        ArchiveSelector.Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
                    }) { i ->
                        // java.util.zip looks an entry up by its name whatever object it
                        // is handed, and of two that share one the JDK answers with the
                        // last. With the ROM ahead of an empty leftover of the same name,
                        // the leftover was read and the digest of nothing recorded as the
                        // ROM's. Its own writer refuses such a zip, but other tools write
                        // them; refusing to read is the one answer that cannot be wrong.
                        val name = native[i].name
                        val count = sameName[name] ?: 1
                        if (count > 1)
                            throw IOException("${file.name} holds $count entries named '$name', " +
                                              "and a zip entry can only be read by its name")
                        zf.getInputStream(native[i])
                            ?: throw IOException("'$name' vanished from ${file.name}")
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
