package com.pegasus.bridge.hasher

import java.util.Locale

/**
 * The functions of librahasher, which is rcheevos, one file of JNI
 * (`hasher/src/main/cpp/rahasher_jni.c`), a reader of `.wbfs` containers
 * (`pb_wbfs.c`) and a reader of `.chd` discs over libchdr (`pb_chd.c`), for
 * both shells.
 *
 * A native function is found by the name of the class that declares it, so
 * the class is this one, here where the tablet and the desktop both compile
 * it, and the library exports the same two names whichever it was built for.
 * Each shell used to declare a `hashFile` of its own in a class of its own,
 * with a file of C to match. What is still each shell's is the loading:
 * `NativeHasher` on Android, `NativeRomHasher` on the desktop. Nothing here
 * loads anything, and a call made before one of them has is an
 * UnsatisfiedLinkError.
 *
 * The object is internal. Its members are public all the same, and static,
 * because the name the library exports is made of theirs: an internal member
 * is compiled under another.
 */
internal object RcheevosNative {

    /**
     * The library this code was written against: the rcheevos release, how
     * many local patches it carries (`pb_patchlevel.h`), and after the slash
     * the readers of our own that are in it, each with the turn it is at
     * (`pb_wbfs.h`, `pb_chd.h`). Whoever loads a library holds [version] against this
     * before any file is hashed. One that answers otherwise is another
     * build's, and what it would say of a file is not what the tests of this
     * build saw.
     */
    const val EXPECTED_VERSION = "12.5.0+pb10/wbfs1,chd1"

    /**
     * How the note on a `.wbfs` begins when half or more of what was hashed
     * came from blocks the container does not hold. See [wbfsCaveat].
     */
    const val SCRUBBED = "scrubbed copy"

    /**
     * The MD5 of no bytes at all. rcheevos answers it for an empty file, and
     * for one that is a copier's header and nothing after it, with the
     * console the name suggests: a hash like any other to look at, and every
     * empty file's alike.
     */
    const val MD5_OF_NOTHING = "d41d8cd98f00b204e9800998ecf8427e"

    /**
     * Room for what rcheevos says of a file it gives no hash for. Its
     * messages are a line each, and the first of them say the most.
     */
    private const val REASON_BYTES = 512

    /**
     * `<md5>|<console>`, and for a `.wbfs` two numbers more,
     * `<md5>|19|<bytes read>|<bytes not held>`; or null with the reason in
     * [errorOut] as UTF-8 that ends with a NUL, cut short between two
     * characters where it does not fit. There is always a reason: where
     * rcheevos gave none the library says that.
     *
     * [pathUtf8] and not a String: what JNI makes of a String is its own
     * variant of UTF-8, which spells a character outside the first 65536
     * unlike the file system does, so the file would not be found.
     *
     * [consoleId] 0 asks every console rcheevos takes the extension for, up
     * to the first that answers. Above 0 it is that console and no other.
     */
    @JvmStatic
    external fun hashForConsole(pathUtf8: ByteArray, consoleId: Int, errorOut: ByteArray): String?

    /** The library's own account of itself, in the form of [EXPECTED_VERSION]. */
    @JvmStatic
    external fun version(): String

    /**
     * rcheevos and its patches, which is [version] up to the slash: what
     * every file's verdict is kept under. What follows the slash is the
     * readers, and a reader counts for the files it reads and for no other
     * ([HashRecipe]).
     */
    fun engine(): String = version().substringBefore('/')

    /**
     * Hashes the file at [path] as [consoleId], or as whatever its extension
     * suggests when that is 0, and says why when there is no hash.
     *
     * [MD5_OF_NOTHING] is a failure here whatever the console: nothing was
     * hashed, and looked up it would be one answer for every such file.
     */
    fun hash(path: String, consoleId: Int): HashOutcome {
        val reason = ByteArray(REASON_BYTES)
        val raw = hashForConsole(path.toByteArray(Charsets.UTF_8), consoleId, reason)
            ?: return HashOutcome.Failed(text(reason))

        return parsed(raw)
    }

    /**
     * What an answer of the library says: two fields, or the four of a
     * `.wbfs`. Anything else is not an answer this code was written for, and
     * is a failure and not a hash with the rest dropped. Internal, for the
     * test that hands it answers no library gives.
     */
    internal fun parsed(raw: String): HashOutcome {
        val fields = raw.split('|')
        val md5 = fields[0]
        val console = fields.getOrNull(1)?.toIntOrNull()
        val counts = fields.drop(2).map { it.toLongOrNull()?.takeIf { n -> n >= 0 } }
        return when {
            md5.isEmpty() || console == null || (counts.size != 0 && counts.size != 2) || null in counts ->
                HashOutcome.Failed("the library answered '$raw', which is no hash and console")
            md5 == MD5_OF_NOTHING ->
                HashOutcome.Failed("nothing in the file is hashed for console $console: " +
                                   "the answer was the MD5 of no bytes")
            counts.isEmpty() -> HashOutcome.Ok(HashResult(md5, console))
            else -> HashOutcome.Ok(HashResult(md5, console, caveat = wbfsCaveat(counts[0]!!, counts[1]!!)))
        }
    }

    /**
     * What to say of the hash of a `.wbfs` of which [read] bytes were hashed,
     * [unheld] of them from blocks the container does not hold. Such blocks
     * are read as Dolphin reads them, so the hash is the one Dolphin sends
     * for the file; it is not the hash of the disc, which is what
     * RetroAchievements holds unless somebody's copy made the same way was
     * added to it.
     *
     * Nothing when no such byte was read. That is not a full copy shown: a
     * tool can also blank what a disc does not use inside blocks that are
     * held, and that leaves no trace here. Any such byte at all changes the
     * hash, so there is a note from the first; it begins with [SCRUBBED]
     * when they are half or more of what was hashed, which is a copy with
     * the game's own data cut down and not one with a block or two missing.
     */
    fun wbfsCaveat(read: Long, unheld: Long): String {
        if (unheld <= 0) return ""
        // To one decimal, and never as none: one cluster of a missing block is some thirty
        // kilobytes, which would be written 0.0 of bytes that are there and changed the hash.
        fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
            .let { if (it == "0.0") "under 0.1" else it }
        val counted = "${mib(unheld)} of ${mib(read)} MiB hashed are blocks the .wbfs does not hold"
        // Compared by taking the half of the larger: twice the smaller could pass what a Long holds.
        return if (unheld >= read - read / 2) "$SCRUBBED: $counted; RetroAchievements knows a disc by a full copy"
               else "$counted: not the hash of a full copy"
    }

    /**
     * Whether a file that gave no hash for [reason] may give one tomorrow
     * with nothing about it changed: it could not be opened, which a card
     * taken out or a copy still running also look like. [reason] is what
     * the library said, with no words of ours around it.
     *
     * Three of rcheevos' messages say so. "Could not open file" and "Could
     * not open playlist" are a file that is not there or not to be read,
     * and "Could not open <path>" is a track that a `.cue` or a `.gdi` names
     * and that is missing. "Could not open track" on its own is not one of
     * them, though it reads like one: it is what a disc console answers for
     * an image it can make no disc of, a `.bin` whose size fits no sector
     * format, and it will answer so at every scan. After a missing track's
     * path rcheevos says it too, and there the path has already settled it.
     *
     * A reason of no words at all is a hasher that had nothing to say, one
     * standing in for the library in a test or a library of another build.
     * Nothing is known against asking again, so that is what is done.
     */
    fun isTransient(reason: String): Boolean {
        if (reason.isEmpty()) return true
        var at = reason.indexOf(COULD_NOT_OPEN)
        while (at >= 0) {
            val what = reason.substring(at + COULD_NOT_OPEN.length)
            if (what != "track" && !what.startsWith("track; ")) return true
            at = reason.indexOf(COULD_NOT_OPEN, at + 1)
        }
        return false
    }

    /**
     * How rcheevos begins every message of something it could not open, the
     * path of a track after it where it is a track. Internal, for the hasher
     * that takes a disc out of an archive and has to know such a path again.
     */
    internal const val COULD_NOT_OPEN = "Could not open "

    /** What the library wrote, up to the NUL that ends it. */
    private fun text(reason: ByteArray): String {
        val end = reason.indexOf(0).let { if (it < 0) reason.size else it }
        return String(reason, 0, end, Charsets.UTF_8)
    }
}
