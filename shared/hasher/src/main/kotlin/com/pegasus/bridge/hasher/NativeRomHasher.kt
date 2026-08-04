package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import java.io.File

/**
 * [RomHasher] backed by the rcheevos native library.
 *
 * The same C sources build for Android arm64 and desktop x86_64 and produce
 * byte-identical hashes for the same ROM, so this class is the shared entry
 * point; only how the library is located differs.
 *
 * Loading is deliberately lazy and non-fatal: a daemon with no native library
 * should still serve scraping and RetroAchievements, and simply report that it
 * cannot scan.
 */
class NativeRomHasher private constructor() : RomHasher {

    override fun hash(path: String): HashResult? = try {
        val raw = hashFile(path) ?: return null
        // The JNI side returns "MD5|CONSOLE_ID".
        val parts = raw.split('|', limit = 2)
        if (parts.size == 2 && parts[0].isNotEmpty())
            HashResult(parts[0], parts[1].toIntOrNull() ?: 0)
        else null
    } catch (t: Throwable) {
        BridgeLog.e(TAG, "native hash failed for $path", t)
        null
    }

    private external fun hashFile(path: String): String?

    companion object {
        private const val TAG = "NativeRomHasher"
        private const val LIB_NAME = "rahasher"

        @Volatile private var instance: NativeRomHasher? = null
        @Volatile private var loadError: String? = null
        private val attempted = HashSet<String>()

        /**
         * Returns the hasher, or null when the native library is unavailable.
         *
         * [explicitPath] loads a specific file; otherwise the usual library
         * search path is used, which is what the Android build relies on.
         *
         * Failures are remembered **per path**, not globally: the daemon walks a
         * list of candidate locations, and a global "already failed" flag would
         * make the first miss suppress every later one.
         */
        @Synchronized
        fun tryLoad(explicitPath: File? = null): NativeRomHasher? {
            instance?.let { return it }
            val key = explicitPath?.absolutePath ?: "<library-path>"
            if (!attempted.add(key)) return null   // this location already failed

            return try {
                if (explicitPath != null) System.load(explicitPath.absolutePath)
                else System.loadLibrary(LIB_NAME)
                NativeRomHasher().also {
                    instance = it
                    BridgeLog.i(TAG, "native hasher loaded" +
                        (explicitPath?.let { p -> " from ${p.absolutePath}" } ?: ""))
                }
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                BridgeLog.w(TAG, "native hasher not at $key: $loadError")
                null
            }
        }

        /** Test seam: forget every attempt so a fresh load can be exercised. */
        @Synchronized
        internal fun resetForTests() {
            instance = null; loadError = null; attempted.clear()
        }

        /** Why the last load attempt failed, for reporting in /health. */
        fun lastError(): String? = loadError
    }
}
