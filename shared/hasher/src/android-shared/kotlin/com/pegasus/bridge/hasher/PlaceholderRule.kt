package com.pegasus.bridge.hasher

import java.io.IOException
import java.util.Locale

/**
 * Whether a file is a placeholder: a name kept in a library for a game that
 * is not there.
 *
 * A frontend lists a game by its file. A library that wants a game shown and
 * does not hold it keeps a file of the right name with nothing in it, or with
 * a line of text that says what it stands for, forty-odd bytes called `.gbc`
 * or `.md` or `.bin`. To rcheevos that is a cartridge like any other: it gave
 * the MD5 of the line, the scan asked about it, and the answer, no, was kept
 * as a game the database lacks. An empty one was a file that could not be
 * hashed, and was read again at every scan. One tablet had eighty.
 *
 * The rule itself reads no file. It is told the extension and the size and
 * handed a way to read the bytes, which it uses once, and only for a file
 * small enough to be a line of text.
 */
object PlaceholderRule {

    enum class Kind {
        /** No bytes at all, whatever the file is called. */
        EMPTY,
        /** A few lines of text under a name that is not a text file's. */
        TEXT_STUB
    }

    /**
     * The most bytes a file of text may have and still be taken for a stub.
     *
     * A stub is a sentence, and this leaves room for a long one in any
     * script. It does not part stubs from every other small text: the one
     * line a launcher such as ScummVM keeps for a game is under it and is
     * counted with the stubs. For a scan that is right, since neither is
     * an image to hash.
     */
    const val TEXT_LIMIT = 512

    /**
     * Extensions whose files are text when they are what they say: the
     * sheets and playlists that name a disc's files, read by rcheevos or
     * ahead of it, and `hex`, in which an Arduboy game is kept. One of
     * these is never a stub by its bytes; one that is wrong is refused by
     * whoever reads it, with a reason.
     */
    val TEXT_BY_NATURE: Set<String> = setOf("cue", "gdi", "m3u", "ccd", "toc", "hex")

    /**
     * What kind of placeholder a file with [extension] (in any case, without
     * the dot) and of [size] bytes is, or null when it is none.
     *
     * [readHead] gives the file's bytes, and need give no more than one past
     * [TEXT_LIMIT]. It is not called for an empty file, for one over the
     * limit, or for an extension of [TEXT_BY_NATURE]. A file that cannot be
     * read is no placeholder: the hasher is handed it and says why. Neither
     * is one whose bytes are not as many as [size], which is another file
     * by now.
     *
     * A zip or a 7z is asked like any file. One that is a line of text was
     * never an archive, and a real one, however small, begins with bytes no
     * text has.
     */
    fun classify(extension: String, size: Long, readHead: () -> ByteArray): Kind? {
        if (size == 0L) return Kind.EMPTY
        if (size > TEXT_LIMIT || extension.lowercase(Locale.ROOT) in TEXT_BY_NATURE) return null
        val bytes = try {
            readHead()
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        if (bytes.size.toLong() != size) return null
        return if (isText(bytes)) Kind.TEXT_STUB else null
    }

    /**
     * Whether [bytes] are text and nothing else: tabs, line ends, printable
     * ASCII, and well-formed UTF-8 for everything above it, a byte order
     * mark included. One NUL, one other control byte, or one byte that is
     * not UTF-8 where it stands, and they are not.
     *
     * Strict on purpose. Calling a ROM a placeholder would cost a game its
     * hash for good, and calling a placeholder a ROM costs what it always
     * did, one read and one request. So a title with an accent written in
     * Latin-1 is left to be hashed: a lone byte above 0x7F is as likely the
     * start of a program as a letter.
     */
    internal fun isText(bytes: ByteArray): Boolean {
        var at = 0
        while (at < bytes.size) {
            val b = bytes[at].toInt() and 0xFF
            at += when {
                b == 0x09 || b == 0x0A || b == 0x0D || b in 0x20..0x7E -> 1
                b < 0x80 -> return false
                else -> sequenceAt(bytes, at).also { if (it == 0) return false }
            }
        }
        return true
    }

    /**
     * How many bytes the UTF-8 sequence that begins at [at] has, or 0 when
     * none begins there: the well-formed sequences of the Unicode Standard,
     * so no character written longer than it need be, no half of a surrogate
     * pair, and nothing past U+10FFFF. Written out here and not left to a
     * decoder, so that the desktop and Android cannot come to differ over
     * what they let through.
     */
    private fun sequenceAt(bytes: ByteArray, at: Int): Int {
        fun byte(k: Int): Int = if (at + k < bytes.size) bytes[at + k].toInt() and 0xFF else -1
        fun tail(k: Int): Boolean = byte(k) in 0x80..0xBF
        return when (byte(0)) {
            in 0xC2..0xDF -> if (tail(1)) 2 else 0
            0xE0 -> if (byte(1) in 0xA0..0xBF && tail(2)) 3 else 0
            in 0xE1..0xEC, 0xEE, 0xEF -> if (tail(1) && tail(2)) 3 else 0
            0xED -> if (byte(1) in 0x80..0x9F && tail(2)) 3 else 0
            0xF0 -> if (byte(1) in 0x90..0xBF && tail(2) && tail(3)) 4 else 0
            in 0xF1..0xF3 -> if (tail(1) && tail(2) && tail(3)) 4 else 0
            0xF4 -> if (byte(1) in 0x80..0x8F && tail(2) && tail(3)) 4 else 0
            else -> 0
        }
    }
}
