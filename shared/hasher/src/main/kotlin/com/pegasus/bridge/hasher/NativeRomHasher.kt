package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import java.io.File

/**
 * [RomHasher] backed by the rcheevos native library, on the desktop.
 *
 * The same C sources build for Android arm64 and desktop x86_64 and produce
 * byte-identical hashes for the same ROM, and both libraries are entered
 * through the one class [RcheevosNative]. What is left here is what only the
 * desktop does: loading the library, from a file the daemon names or from the
 * library search path. The Android build never compiles this class;
 * `NativeHasher`, in the Android module, is its counterpart there.
 *
 * Loading is deliberately lazy and non-fatal: a daemon with no native library
 * should still serve scraping and RetroAchievements, and simply report that it
 * cannot scan.
 */
class NativeRomHasher private constructor() : RomHasher {

    /** As the console its extension suggests, which is console 0 of [hashForConsole]. */
    override fun hash(path: String): HashResult? = (hashForConsole(path, 0) as? HashOutcome.Ok)?.result

    override fun hashForConsole(path: String, consoleId: Int): HashOutcome =
        RcheevosNative.hash(path, consoleId)

    override val engine: String get() = RcheevosNative.version()

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
         * search path is used. Only the desktop comes through here: on
         * Android the library is in the APK and `NativeHasher` loads it.
         *
         * A library that loads is asked which rcheevos it is before it is
         * handed out, and one that is not [RcheevosNative.EXPECTED_VERSION]
         * is a failure like a file that is not there. An older build beside
         * newer jars would otherwise hash a whole library by rules the jars
         * were not written for, or, lacking the two functions, fail at every
         * file and leave a scan of files that could not be hashed. The JVM
         * cannot let go of what it has loaded, though. A library of another
         * version stays the one the two functions are bound to, so a right
         * one tried after it in the same process is refused as well: it is
         * the first that answers for it.
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
                // Inside the try: a library from before the two functions
                // had these names throws here, and is a library not found.
                val version = RcheevosNative.version()
                check(version == RcheevosNative.EXPECTED_VERSION) {
                    "the library is rcheevos $version, and this build was made for " +
                    RcheevosNative.EXPECTED_VERSION
                }
                NativeRomHasher().also {
                    instance = it
                    BridgeLog.i(TAG, "native hasher loaded" +
                        (explicitPath?.let { p -> " from ${p.absolutePath}" } ?: "") +
                        " (rcheevos $version)")
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
