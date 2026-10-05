package com.pegasus.bridge.hasher

import android.util.Log

/**
 * rcheevos on Android, as the [RomHasher] the shared scan pipeline takes.
 *
 * The class name, the package and [hashFile] stay as they are: librahasher.so
 * exports `Java_com_pegasus_bridge_hasher_NativeHasher_hashFile`, and the
 * runtime finds the function by that name alone.
 */
object NativeHasher : RomHasher {

    private const val TAG = "NativeHasher"

    /** Names no file, and says in the library's own log line who asked. */
    private const val LINK_PROBE = "/NativeHasher.verifyLinked/no-such-file.gb"

    init {
        System.loadLibrary("rahasher")
    }

    override fun hash(path: String): HashResult? {
        return try {
            val raw = hashFile(path) ?: return null
            val parts = raw.split("|", limit = 2)
            // "|3" is no hash. rcheevos does not answer so when it succeeds, and the
            // desktop's NativeRomHasher refuses it all the same: the two have to
            // agree on what the pipeline is handed.
            if (parts.size == 2 && parts[0].isNotEmpty())
                HashResult(hash = parts[0], consoleId = parts[1].toIntOrNull() ?: 0)
            else null
        } catch (e: Exception) {
            Log.e(TAG, "Hash failed for $path", e)
            null
        }
    }

    /**
     * One call into the library, for a scan to make before it starts.
     *
     * A library that loaded but does not export [hashFile] under this class's
     * name throws UnsatisfiedLinkError at every call. [hash] does not catch it,
     * and the pipeline takes whatever a hasher throws as that one file's
     * failure, so a whole library would end as files that could not be hashed.
     * Here the error is let out once, where it can end the scan under its own
     * name. rcheevos finds nothing to open and answers null, which nobody
     * looks at.
     */
    fun verifyLinked() {
        hashFile(LINK_PROBE)
    }

    private external fun hashFile(path: String): String?
}
