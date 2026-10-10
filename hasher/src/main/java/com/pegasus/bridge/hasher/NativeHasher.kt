package com.pegasus.bridge.hasher

/**
 * rcheevos on Android, as the [RomHasher] the shared scan pipeline takes.
 *
 * What is Android's own is the loading: librahasher.so is in the APK, and is
 * loaded the first time this object is touched. The functions it exports are
 * declared in [RcheevosNative], which the desktop compiles too, so the name
 * of this class is no longer part of any symbol.
 */
object NativeHasher : RomHasher {

    init {
        System.loadLibrary("rahasher")
    }

    /** As the console its extension suggests, which is console 0 of [hashForConsole]. */
    override fun hash(path: String): HashResult? = (hashForConsole(path, 0) as? HashOutcome.Ok)?.result

    override fun hashForConsole(path: String, consoleId: Int): HashOutcome =
        RcheevosNative.hash(path, consoleId)

    override val engine: String get() = RcheevosNative.version()

    /**
     * One call into the library, for a scan to make before it starts.
     *
     * A library that loaded but does not export the functions [RcheevosNative]
     * declares throws UnsatisfiedLinkError at every call. Nothing here catches
     * it, and the pipeline takes whatever a hasher throws as that one file's
     * failure, so a whole library would end as files that could not be hashed.
     * Here the error is let out once, where it can end the scan under its own
     * name.
     *
     * The call asks the library which rcheevos it is, and an answer other
     * than [RcheevosNative.EXPECTED_VERSION] ends the scan as well: that is a
     * library of another build than this code, whose hashes are not the ones
     * this build's tests saw.
     */
    fun verifyLinked() {
        val version = RcheevosNative.version()
        check(version == RcheevosNative.EXPECTED_VERSION) {
            "librahasher.so is rcheevos $version, and this build was made for " +
            RcheevosNative.EXPECTED_VERSION
        }
    }
}
