package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import java.io.File

/**
 * MD5, CRC32 and size of a ROM, with no rcheevos involved.
 *
 * [ArchiveAwareHasher] already produces these — but only as a passenger on the
 * RetroAchievements hash, which is native: `withPlainHashes` is never reached when
 * `delegate.hash()` returns null, and on desktop the native library is **optional**
 * (`BridgeDaemon` starts and serves everything else without it). Hanging ScreenScraper
 * off that path would make the whole source disappear on any machine missing a `.so`,
 * for a reason that has nothing to do with ScreenScraper.
 *
 * So this computes the same three values on its own. It is deliberately *not* a
 * [RomHasher]: that interface promises a console id and an RA-compatible hash, and this
 * has neither to give.
 *
 * ── What gets hashed ──────────────────────────────────────────
 *
 * The ROM **inside** the archive, never the container: a scraper asked about a zip's MD5
 * matches nothing, because the databases (No-Intro, Redump) list the ROM. Same rule and
 * same reason as [ArchiveAwareHasher], and the same fallback — a failed extraction
 * hashes the file as it lies, because an extension is a claim rather than a fact and a
 * plain ROM named `.7z` is common enough that refusing it loses real games.
 *
 * The **name** is the archive's, though, not the entry's: it is what MAME identifies a
 * romset by, and [FileHashes.name] is what a `romnom` lookup sends.
 */
object PlainRomHasher {

    /**
     * [name] is the file's own name — `pacman.zip`, not the entry inside it.
     * [fromArchive] records whether the digest describes an extracted entry, which is
     * the difference between "these hashes mean something" and "these hashes describe a
     * zip" for anyone reading a log.
     */
    data class FileHashes(
        val name: String,
        val md5: String,
        val crc32: String,
        val size: Long,
        val fromArchive: Boolean,
        /** Which entry the digests describe, or empty for a plain file. */
        val archiveEntry: String = "",
        /**
         * Several entries could each be the ROM, and none was picked.
         *
         * The hashes then describe the container, which matches nothing in a ROM
         * database — so a caller that sends them is asking a question whose answer
         * it cannot use. Better to know than to receive a confident miss.
         */
        val ambiguous: List<String> = emptyList()
    )

    /**
     * Null only when the file cannot be read at all.
     *
     * [platform] steers which entry of an archive is the ROM. Optional because a
     * caller that does not know the collection still gets the union of every ROM
     * extension, which settles most archives; naming the platform settles the rest.
     *
     * [tempDir] is no longer used: an entry streams straight into the digest, so
     * nothing is written. It stays so the callers on both shells need not change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hash(path: String, tempDir: File, platform: String = ""): FileHashes? {
        val file = File(path)
        if (!file.isFile) {
            BridgeLog.w(TAG, "no such file: $path")
            return null
        }
        if (!ArchiveReader.isArchive(file)) return digest(file, file.name, false)

        return ArchiveReader.open(file) { opened ->
            when (opened) {
                // An extension is a claim, not a fact, and a plain ROM named `.7z` is
                // common enough that refusing it loses real games.
                is ArchiveReader.Opened.Unreadable -> digest(file, file.name, false)
                is ArchiveReader.Opened.Entries ->
                    when (val pick = ArchiveSelector.select(opened.entries, file.name, platform)) {
                        is ArchiveSelector.Selection.One -> fromArchive(file, opened, pick.entry)
                            ?: digest(file, file.name, false)
                        is ArchiveSelector.Selection.Ambiguous ->
                            // The container's digest, plus the fact that it is one. A
                            // scraper can decline to spend a lookup on it.
                            digest(file, file.name, false)
                                ?.copy(ambiguous = pick.candidates.map { it.name })
                        is ArchiveSelector.Selection.NoPlayableEntry -> digest(file, file.name, false)
                    }
            }
        }
    }

    private fun fromArchive(
        file: File,
        opened: ArchiveReader.Opened.Entries,
        entry: ArchiveSelector.Entry
    ): FileHashes? = try {
        opened.read(entry) { RomHashIO.copyAndDigest(it) }
            .toFileHashes(file.name, fromArchive = true)
            .copy(archiveEntry = entry.name)
    } catch (t: Throwable) {
        // Throwable, not Exception: a missing optional codec arrives as
        // NoClassDefFoundError. The scan learned that one the hard way. A
        // cancellation is not a failed extraction, though, and must not go on to
        // digest the whole container instead.
        RomHashIO.rethrowIfCancelled(t)
        BridgeLog.w(TAG, "archive failed: ${file.name}: ${t.message}")
        null
    }

    private fun digest(target: File, name: String, fromArchive: Boolean): FileHashes? = try {
        RomHashIO.digest(target).toFileHashes(name, fromArchive)
    } catch (t: Throwable) {
        RomHashIO.rethrowIfCancelled(t)
        BridgeLog.w(TAG, "hash failed: ${target.name}: ${t.message}")
        null
    }

    private fun RomHashIO.Digests.toFileHashes(name: String, fromArchive: Boolean) =
        FileHashes(name = name, md5 = md5, crc32 = crc32, size = size, fromArchive = fromArchive)

    private const val TAG = "PlainRomHasher"
}
