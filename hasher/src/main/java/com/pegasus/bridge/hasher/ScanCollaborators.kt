package com.pegasus.bridge.hasher

/**
 * The two things a scan is made of that this shell has to supply: the hasher
 * and the RetroAchievements lookup.
 *
 * The desktop daemon builds both and hands them to its router. Nothing builds
 * [HasherService]: Android does, with no arguments, and starts it with an
 * Intent that carries strings. So the service asks here, once for every scan,
 * and each answer is a function that can be replaced, the way
 * [RomScanExtensions] takes its resolver. What replaces them is a run of the
 * service off a device, on a JVM that has no librahasher.so to load and must
 * not reach retroachievements.org.
 *
 * Mutable and one for the whole process, hence `internal`, and nothing in the
 * app sets either. Whoever replaces one puts the default back when done.
 */
internal object ScanCollaborators {

    /**
     * The native hasher, with one call made into it before it is handed over.
     *
     * A library that is not there fails when [NativeHasher] is first touched,
     * and one that loaded without the function at the call
     * [NativeHasher.verifyLinked] makes. Both are thrown from here, before any
     * file is read, and end the scan as the error they are. Met file by file
     * they would not be an error at all: the pipeline takes what a hasher
     * throws as the failure of that one file, and the scan would finish, every
     * file in the library counted as one that could not be hashed.
     */
    @Volatile
    var hasher: () -> RomHasher = { NativeHasher.also { it.verifyLinked() } }

    /**
     * A lookup for one scan, and another for the next.
     *
     * [RaApiHashLookup] never takes back a refused key, and its count of
     * failures in a row goes back to 0 only on an answer. The pipeline looks at
     * both after every result. Kept from one scan to the next, the object a
     * scan was stopped on would stop the next at its first result: the scan
     * made with the corrected key, or once RetroAchievements is back.
     */
    @Volatile
    var lookup: (raUser: String, raApiKey: String) -> RaHashLookup =
        { raUser, raApiKey -> RaApiHashLookup(raUser, raApiKey) }
}
