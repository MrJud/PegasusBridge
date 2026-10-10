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
    const val EXPECTED_VERSION = "12.3.0+pb6"

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

    /** What the library wrote, up to the NUL that ends it. */
    private fun text(reason: ByteArray): String {
        val end = reason.indexOf(0).let { if (it < 0) reason.size else it }
        return String(reason, 0, end, Charsets.UTF_8)
    }
}
