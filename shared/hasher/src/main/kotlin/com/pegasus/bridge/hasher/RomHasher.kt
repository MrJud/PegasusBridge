package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import java.io.File

/**
 * [hash] is the RetroAchievements one, from rcheevos, which transforms the data
 * per console before hashing — NES skips the iNES header, SNES a copier header.
 * [fileMd5] and [fileCrc32] describe the file itself, which is what ROM databases
 * match on. The two coincide on consoles rcheevos hashes whole, and differ on
 * NES and SNES, so they are not interchangeable.
 */
data class HashResult(
    val hash: String,
    val consoleId: Int,
    val fileMd5: String = "",
    val fileCrc32: String = "",
    /** Which entry inside an archive these describe, or empty for a plain file. */
    val archiveEntry: String = "",
    /**
     * True when the digests describe the **container**, because it could not be
     * read as an archive at all.
     *
     * A caller that looks a hash up in a ROM database wants to know: a container
     * digest matches nothing there, so a miss on one says nothing about whether
     * the database has the game.
     */
    val containerFallback: Boolean = false
)

/**
 * What happened when a file was hashed — not just what came out.
 *
 * `null` used to carry every kind of failure at once: an unreadable file, a
 * missing native library, and an archive holding three plausible ROMs all
 * arrived at the pipeline as "no hash", were recorded identically, and were
 * therefore indistinguishable from a game RetroAchievements does not have. The
 * scan could not explain itself because it had not been told anything.
 */
sealed interface HashOutcome {
    data class Ok(val result: HashResult) : HashOutcome

    /**
     * Several entries could each be the ROM.
     *
     * Not a failure of the file and not a miss: the archive needs a person to
     * look at it. Hashing an arbitrary one instead is what the old rule did, and
     * it produced a wrong answer that looked exactly like a right one.
     */
    data class AmbiguousArchive(val candidates: List<String>) : HashOutcome

    /** The file could not be read, or the hasher could not process it. */
    data class Failed(val reason: String) : HashOutcome
}

/**
 * Computes a RetroAchievements-compatible hash for one ROM file.
 *
 * The implementation is native (rcheevos), and how it is reached differs per
 * platform: on Android the `.so` ships inside the APK and is loaded by the
 * classloader, on desktop it is a system library or a helper binary. Hence the
 * interface — the pipeline must not care.
 *
 * The native code itself is identical everywhere: the same ROM produces the same
 * hash from the x86_64 and the arm64 builds.
 */
interface RomHasher {
    fun hash(path: String): HashResult?

    /**
     * With the collection's short name, so an archive can be resolved by what
     * the platform actually runs rather than by which entry is biggest.
     *
     * Defaulted, so a hasher with no archive handling — every implementation of
     * the native layer — needs to know nothing about platforms.
     */
    fun hash(path: String, platform: String): HashResult? = hash(path)

    /**
     * The same, but able to say *why* there is no hash.
     *
     * Defaulted to the lossy answer so an implementation that cannot distinguish
     * the cases does not have to pretend it can.
     */
    fun hashDetailed(path: String, platform: String): HashOutcome =
        hash(path, platform)?.let { HashOutcome.Ok(it) }
            ?: HashOutcome.Failed("the hasher returned nothing")
}

/**
 * Wraps a [RomHasher] with archive support: zip and 7z are extracted to a temp
 * file first, because the native hasher works on plain files.
 *
 * Which entry is extracted is [ArchiveSelector]'s decision — by what the
 * platform can run, then by the entry named after the archive. The rule it
 * replaced, "the largest entry", is right for one ROM plus a readme and silently
 * wrong for a bonus disc, an included patch or a `.cue`/`.bin` pair, where the
 * biggest file is the data track and the descriptor is what has to be hashed.
 */
class ArchiveAwareHasher(
    private val delegate: RomHasher,
    private val tempDir: File
) : RomHasher {

    override fun hash(path: String): HashResult? = hash(path, "")

    override fun hash(path: String, platform: String): HashResult? =
        (hashDetailed(path, platform) as? HashOutcome.Ok)?.result

    override fun hashDetailed(path: String, platform: String): HashOutcome {
        val file = File(path)
        if (!file.isFile) return HashOutcome.Failed("no such file")

        if (!ArchiveReader.isArchive(file)) return plain(file)

        return when (val opened = ArchiveReader.list(file)) {
            // A file that cannot be read as the archive its extension claims is
            // hashed as it lies. An extension is a claim, not a fact, and a plain
            // ROM renamed `.7z` is common enough that refusing it loses real games;
            // the fallback cannot produce a wrong match, only a miss. It is marked,
            // so a scraper lookup can decline to trust a container digest.
            is ArchiveReader.Opened.Unreadable -> {
                BridgeLog.w(TAG, "${file.name} is not a readable archive (${opened.reason}); " +
                                 "hashing the file itself")
                plain(file, containerFallback = true)
            }
            is ArchiveReader.Opened.Entries ->
                when (val pick = ArchiveSelector.select(opened.entries, file.name, platform)) {
                    is ArchiveSelector.Selection.One -> extracted(file, pick.entry)
                    is ArchiveSelector.Selection.Ambiguous ->
                        HashOutcome.AmbiguousArchive(pick.candidates.map { it.name })
                    is ArchiveSelector.Selection.NoPlayableEntry ->
                        // Nothing inside is playable on this platform. The container
                        // itself is the last thing left to describe, and saying so is
                        // more useful than refusing outright.
                        plain(file, containerFallback = true)
                }
        }
    }

    private fun extracted(archive: File, entry: ArchiveSelector.Entry): HashOutcome = try {
        tempDir.mkdirs()
        val tmp = File.createTempFile("bridge_", ".bin", tempDir)
        try {
            if (!ArchiveReader.extract(archive, entry.name, tmp))
                HashOutcome.Failed("could not extract '${entry.name}'")
            else when (val r = plain(tmp)) {
                is HashOutcome.Ok -> HashOutcome.Ok(r.result.copy(archiveEntry = entry.name))
                else -> r
            }
        } finally {
            tmp.delete()
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.e(TAG, "archive failed: ${archive.name}", t)
        HashOutcome.Failed(t.message ?: t.javaClass.simpleName)
    }

    /** [romFile] is whatever the delegate is given, so the digests describe the ROM. */
    private fun plain(romFile: File, containerFallback: Boolean = false): HashOutcome {
        val result = delegate.hash(romFile.absolutePath)
            ?: return HashOutcome.Failed("the hasher could not read ${romFile.name}")
        return HashOutcome.Ok(withPlainHashes(result, romFile).copy(containerFallback = containerFallback))
    }

    private fun withPlainHashes(result: HashResult, romFile: File): HashResult = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        val crc = java.util.zip.CRC32()
        romFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                crc.update(buf, 0, n)
            }
        }
        result.copy(
            fileMd5 = md.digest().joinToString("") { "%02x".format(it) },
            fileCrc32 = "%08x".format(crc.value)
        )
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        // Costs a scraper lookup, never the RA match the scan exists for.
        BridgeLog.w(TAG, "plain hash failed: ${romFile.name}: ${t.message}")
        result
    }

    private companion object {
        const val TAG = "ArchiveAwareHasher"
    }
}
