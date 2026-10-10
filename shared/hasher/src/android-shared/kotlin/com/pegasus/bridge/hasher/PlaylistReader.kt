package com.pegasus.bridge.hasher

import java.io.File
import java.io.IOException

/**
 * The first disc a playlist names.
 *
 * A game of several discs is kept as an `.m3u` listing them, and its hash is
 * the first disc's. rcheevos follows a playlist itself for some consoles and
 * hashes its text for the others. Read here, the first entry can be planned
 * for as any other file is, whatever the console, and a playlist that leads
 * nowhere, or to another playlist, is an answer with a reason and not a hash
 * of a few lines of text.
 */
object PlaylistReader {

    /** Why a playlist gave no file. */
    enum class Why {
        /** The playlist itself could not be read. */
        UNREADABLE,
        /** It lists nothing. */
        EMPTY,
        /** Its first entry is another playlist. */
        NESTED,
        /** Its first entry is not a file that is there. */
        MISSING
    }

    sealed interface Result {
        /** The first entry, which exists. */
        data class Entry(val file: File) : Result

        /** No file to hash. [reason] is a sentence for a person. */
        data class Refused(val why: Why, val reason: String) : Result
    }

    /** As far into a playlist as its first entry is looked for. A real one is a few lines. */
    const val READ_LIMIT = 64 * 1024

    /**
     * The file the first entry of [playlist] names.
     *
     * Blank lines and lines that begin with `#` are passed over, and the
     * entry is trimmed. A playlist written on Windows has backslashes
     * between its folders, and is read on any system. An entry that is not
     * absolute is in the playlist's own folder or under it.
     */
    fun firstEntry(playlist: File): Result {
        val text = try {
            readStart(playlist)
        } catch (e: IOException) {
            return Result.Refused(Why.UNREADABLE, "the playlist could not be read (${e.javaClass.simpleName})")
        }
        val line = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return Result.Refused(Why.EMPTY, "the playlist lists no file")

        val path = line.replace('\\', '/')
        val target = if (isAbsolute(path)) File(path) else File(playlist.absoluteFile.parentFile, path)
        if (target.name.substringAfterLast('.', "").equals("m3u", ignoreCase = true))
            return Result.Refused(Why.NESTED, "the playlist names another playlist, ${target.name}")
        if (!target.isFile)
            return Result.Refused(Why.MISSING, "the playlist names ${target.name}, which is not there")
        return Result.Entry(target)
    }

    /**
     * The first [READ_LIMIT] bytes as text. When the file goes on past them,
     * the last line may be cut short, and half a name is not an entry: it is
     * left out, unless the very next byte is what ends it.
     */
    private fun readStart(playlist: File): String {
        val buffer = ByteArray(READ_LIMIT + 1)
        var filled = 0
        playlist.inputStream().use { input ->
            while (filled < buffer.size) {
                val count = input.read(buffer, filled, buffer.size - filled)
                if (count < 0) break
                filled += count
            }
        }
        if (filled <= READ_LIMIT) return String(buffer, 0, filled, Charsets.UTF_8)
        val text = String(buffer, 0, READ_LIMIT, Charsets.UTF_8)
        val next = buffer[READ_LIMIT].toInt().toChar()
        if (next == '\n' || next == '\r') return text
        val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
        return if (lastBreak < 0) "" else text.substring(0, lastBreak)
    }

    /** `/x`, or `C:/x` with the backslashes already turned. */
    private fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || (path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '/')
}
