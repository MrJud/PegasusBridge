package com.pegasus.bridge.hasher

/**
 * The functions of librahasher, which is rcheevos and one file of JNI
 * (`hasher/src/main/cpp/rahasher_jni.c`), for both shells.
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
     * The library this code was written against: the rcheevos release, and
     * how many local patches it carries (`pb_patchlevel.h`). Whoever loads a
     * library holds [version] against this before any file is hashed. One
     * that answers otherwise is another build's, and what it would say of a
     * file is not what the tests of this build saw.
     */
    const val EXPECTED_VERSION = "12.5.0+pb6"

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
     * `<md5>|<console>`, or null with the reason in [errorOut] as UTF-8 that
     * ends with a NUL, cut short between two characters where it does not
     * fit. There is always a reason: where rcheevos gave none the library
     * says that.
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

        val md5 = raw.substringBefore('|')
        val console = raw.substringAfter('|', "").toIntOrNull()
        return when {
            md5.isEmpty() || console == null ->
                HashOutcome.Failed("the library answered '$raw', which is no hash and console")
            md5 == MD5_OF_NOTHING ->
                HashOutcome.Failed("nothing in the file is hashed for console $console: " +
                                   "the answer was the MD5 of no bytes")
            else -> HashOutcome.Ok(HashResult(md5, console))
        }
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

    /** How rcheevos begins every message of something it could not open. */
    private const val COULD_NOT_OPEN = "Could not open "

    /** What the library wrote, up to the NUL that ends it. */
    private fun text(reason: ByteArray): String {
        val end = reason.indexOf(0).let { if (it < 0) reason.size else it }
        return String(reason, 0, end, Charsets.UTF_8)
    }
}
