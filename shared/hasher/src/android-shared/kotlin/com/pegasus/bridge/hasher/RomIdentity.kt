package com.pegasus.bridge.hasher

import java.io.File

/**
 * What a scraper was actually asked about.
 *
 * Two ROMs of the same game are not the same input. `Contra (USA).nes` and
 * `Contra (Japan).nes` resolve to one ScreenScraper game id and select
 * *different* box art, so anything keyed on the game id alone — a cache entry,
 * an artwork filename — makes the second file inherit the first one's picture.
 * That is the bug this type exists to make impossible: identity is the file,
 * and the game id is only one of its answers.
 *
 * [signature] is deliberately cheap: path, size and mtime, no digest. It is
 * what a cache key needs and it costs no I/O, so a repeated request for a ROM
 * already identified never re-reads 40 MB to discover it has not changed. The
 * digests are carried when a caller has already computed them, and are what an
 * artwork key mixes in when it can.
 */
data class RomIdentity(
    /** Pegasus short name, normalised. Part of identity: the same file under two
     *  collections is two questions, and gets two answers. */
    val platform: String,
    val canonicalPath: String,
    val fileSize: Long,
    val lastModified: Long,
    /** The file's own name — `pacman.zip`, not the entry inside it. */
    val romName: String,
    val md5: String = "",
    val crc32: String = "",
    /** True when the source identifies this family by romset name (MAME, Neo Geo). */
    val matchedByName: Boolean = false,
    /** Digests describe an extracted entry rather than the container. */
    val fromArchive: Boolean = false
) {

    /**
     * The cheap part — enough to notice a file has been replaced at the same path.
     *
     * Size and mtime together, because either alone misses a real case: a ROM
     * patched in place keeps its size, and a restored backup keeps its mtime.
     */
    val signature: String get() = "$canonicalPath|$fileSize|$lastModified"

    /**
     * Everything that distinguishes this ROM from another one of the same game.
     *
     * Used as the ROM half of an artwork key. The digests lead when present —
     * they survive a rename, which the signature does not — and the signature
     * follows so that a file the hasher could not read still gets a key of its
     * own rather than colliding with every other unreadable file.
     */
    fun fingerprint(): String = buildString {
        append(platform).append('|')
        if (md5.isNotEmpty()) append("md5:").append(md5).append('|')
        if (crc32.isNotEmpty()) append("crc:").append(crc32).append('|')
        append(signature)
        if (matchedByName) append("|byname:").append(romName)
    }

    companion object {
        /**
         * From a file on disk, with no hashing.
         *
         * [platform] is passed rather than derived: the parent directory is the
         * usual answer, but a caller that knows the collection knows better.
         */
        fun of(
            path: String,
            platform: String,
            matchedByName: Boolean = false
        ): RomIdentity {
            val f = File(path)
            // canonicalFile, not absoluteFile: two roots may reach one library
            // through different symlinks, and they must not be two identities.
            val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            return RomIdentity(
                platform = platform,
                canonicalPath = canonical,
                fileSize = f.length(),
                lastModified = f.lastModified(),
                romName = f.name,
                matchedByName = matchedByName
            )
        }
    }
}

/** Fills in the digests a [PlainRomHasher] run produced. */
fun RomIdentity.withHashes(h: PlainRomHasher.FileHashes): RomIdentity =
    copy(md5 = h.md5, crc32 = h.crc32, fromArchive = h.fromArchive)
