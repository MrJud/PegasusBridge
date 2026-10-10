package com.pegasus.bridge.core

import java.io.File
import java.io.InputStream

/**
 * The part of a Pegasus metadata file that says which collection a folder
 * is: its names, and the lines of each `collection:` above the first game.
 *
 * Here, in the module everything else is built on, and not beside the reader
 * the launch editor has in the pegasus module, because a scan has to ask the
 * question and cannot reach that module: it is the one that depends on the
 * scan. That reader also takes the header for one flat set of fields, which is
 * what editing a launch command wants and is not what a file with two
 * collections in it says.
 *
 * Only the header is read, and only as far as it goes. A metafile that lists
 * every game of a collection runs to hundreds of kilobytes, on a card or a
 * disk that is slow to give them, and a scan asks about every folder it walks.
 */
object PegasusMetafile {

    /** The names Pegasus accepts, in the order it looks for them. */
    val FILE_NAMES = listOf("metadata.pegasus.txt", "metadata.txt")

    /** What a file of any other name has to end in for Pegasus to read it too. */
    private val SUFFIXES = FILE_NAMES.map { ".$it" }

    /**
     * Whether Pegasus reads a file called [name] as a metadata file: one of
     * [FILE_NAMES], or any name that ends in a dot and one of them, which is
     * how a second file for the same folder is named. By the letter, as
     * Pegasus compares.
     */
    fun isMetafile(name: String): Boolean = name in FILE_NAMES || SUFFIXES.any { name.endsWith(it) }

    /**
     * Every metadata file in [dir]: the two plain names first, in the order
     * Pegasus looks for them, then the others by name. None for a folder that
     * cannot be listed.
     *
     * From one listing of the folder, so that a folder with none costs no
     * more than that.
     */
    fun filesIn(dir: File): List<File> {
        val names = dir.list()?.filter(::isMetafile) ?: return emptyList()
        val ordered = FILE_NAMES.filter { it in names } + names.filter { it !in FILE_NAMES }.sorted()
        return ordered.map { File(dir, it) }.filter { it.isFile }
    }

    /**
     * One `collection:` of a metafile, with what its lines say of the files
     * that belong to it.
     *
     * [shortName] is the collection's own where it has a `shortname:` line,
     * and otherwise the name in lower case, which is what Pegasus gives a
     * collection that has none. [declaresShortName] tells the two apart, for a
     * reader that finds the same collection in a second file.
     *
     * [extensions] and [ignoreExtensions] are in lower case and without
     * repeats. [files] and [directories] are as written, one for each line.
     */
    data class Block(
        val name: String,
        val shortName: String,
        val extensions: List<String> = emptyList(),
        val ignoreExtensions: List<String> = emptyList(),
        val files: List<String> = emptyList(),
        val directories: List<String> = emptyList(),
        val declaresShortName: Boolean = false
    )

    /**
     * The collections [file] declares, in the order it declares them. None
     * for a file that only lists games, and none for one that cannot be read:
     * a metafile that is not there for this is a folder that says nothing of
     * itself, and whoever asks has a rule for that.
     */
    fun collections(file: File): List<Block> =
        try {
            file.inputStream().use(::collections)
        } catch (e: Exception) {
            BridgeLog.w(TAG, "could not read ${file.name} in ${file.parentFile?.name}: ${e.javaClass.simpleName}")
            emptyList()
        }

    /**
     * The same of a file's bytes, read from [input] no further than the
     * header goes: to the first `game:`, or to [HEADER_LIMIT].
     *
     * Lines are taken as MetadataFile.readHeaderFields takes them, since the
     * two read the same files and are compared in a test. A line that begins
     * with white space carries on the value above it. `#` begins a comment. A
     * name is whatever stands before the first colon, in any case. What a
     * line says before any `collection:` belongs to nothing and is dropped.
     *
     * Where the two differ, this one does as Pegasus does. Each `collection:`
     * begins a block of its own, where the flat reader keeps the last value
     * of every name. And a name written twice in a block adds to the list, as
     * `file:` is written once for each file, where the flat reader keeps the
     * last line.
     */
    internal fun collections(input: InputStream): List<Block> {
        val blocks = ArrayList<Builder>()
        var key: String? = null
        val value = StringBuilder()

        fun flush() {
            val k = key ?: return
            val v = value.toString().trim()
            value.setLength(0)
            key = null
            if (k == "collection") {
                // The name is one line. A collection with no name is not one,
                // and what follows it belongs to the one before.
                val name = v.lineSequence().first().trim()
                if (name.isNotEmpty()) blocks += Builder(name)
                return
            }
            blocks.lastOrNull()?.take(k, v)
        }

        var first = true
        for (raw in HeaderLines(input)) {
            // A byte order mark is part of the first line to a decoder, and
            // would make `collection` a name nobody looks for.
            val line = (if (first) raw.removePrefix(BYTE_ORDER_MARK) else raw).trimEnd()
            first = false
            if (line.isBlank()) continue
            if (line.trimStart().startsWith("#")) continue

            if (line.first().isWhitespace() && key != null) {
                value.append('\n').append(line.trim())
                continue
            }

            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            flush()
            // The header ends where the games begin.
            if (name == "game") break
            key = name
            value.append(line.substring(colon + 1).trim())
        }
        flush()
        return blocks.map { it.build() }
    }

    private class Builder(val name: String) {
        var shortName: String? = null
        val extensions = LinkedHashSet<String>()
        val ignoreExtensions = LinkedHashSet<String>()
        val files = ArrayList<String>()
        val directories = ArrayList<String>()

        fun take(key: String, value: String) {
            when (key) {
                "shortname" -> value.lineSequence().first().trim().takeIf { it.isNotEmpty() }?.let { shortName = it }
                "extension", "extensions" -> extensions += splitList(value)
                "ignore-extension", "ignore-extensions" -> ignoreExtensions += splitList(value)
                "file", "files" -> files += lines(value)
                "directory", "directories" -> directories += lines(value)
            }
        }

        fun build() = Block(
            name = name,
            shortName = shortName ?: name.lowercase(),
            extensions = extensions.toList(),
            ignoreExtensions = ignoreExtensions.toList(),
            files = files,
            directories = directories,
            declaresShortName = shortName != null
        )
    }

    /** Extensions as MetadataFile.splitList splits them: on commas and line ends, in lower case. */
    private fun splitList(value: String): List<String> =
        value.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun lines(value: String): List<String> =
        value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * The lines of a stream as UTF-8, read a few kilobytes at a time and no
     * further than whoever takes them goes on asking.
     *
     * Written out, where a BufferedReader would do the same in one line,
     * because of how much each reads ahead. A reader fills 8 KiB before it
     * gives its first line, and its decoder as much again, whatever size it
     * is asked for. This takes [CHUNK] at a time, so that a header of three
     * lines costs one small read of a file however long.
     *
     * It gives up at [HEADER_LIMIT], and a line cut off there is not given:
     * the rest of it was not read.
     */
    private class HeaderLines(private val input: InputStream) : Iterator<String> {
        private val chunk = ByteArray(CHUNK)
        private var filled = 0
        private var at = 0
        private var taken = 0L
        private var ended = false
        private var next: String? = null

        override fun hasNext(): Boolean {
            if (next == null) next = read()
            return next != null
        }

        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException()
            return next!!.also { next = null }
        }

        private fun read(): String? {
            if (ended) return null
            val line = java.io.ByteArrayOutputStream()
            while (true) {
                if (at == filled) {
                    if (taken >= HEADER_LIMIT) { ended = true; return null }
                    filled = input.read(chunk)
                    at = 0
                    if (filled <= 0) {
                        ended = true
                        return if (line.size() > 0) decode(line) else null
                    }
                    taken += filled
                }
                val from = at
                while (at < filled && chunk[at] != NEWLINE) at++
                line.write(chunk, from, at - from)
                if (at < filled) { at++; return decode(line) }
            }
        }

        // A carriage return before the line end is left on, and goes with
        // the white space every line has taken off its end.
        private fun decode(line: java.io.ByteArrayOutputStream) = String(line.toByteArray(), Charsets.UTF_8)
    }

    private const val TAG = "PegasusMetafile"
    private const val NEWLINE = '\n'.code.toByte()
    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val CHUNK = 4096

    /**
     * How much of a file is read in search of its header's end. A real header
     * is a few hundred bytes, and a long launch command a few thousand. A file
     * with a metafile's name and something else inside is not read whole to
     * find that out.
     */
    const val HEADER_LIMIT = 1L shl 20
}
