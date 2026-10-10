package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.FuzzyMatch

/**
 * Which file inside an archive is the ROM.
 *
 * The rule this replaces was "the largest entry", which is a heuristic wearing
 * the clothes of an identity rule. It is right for the common case — one ROM
 * plus a readme — and quietly wrong for the ones that matter:
 *
 * - a bonus disc, a soundtrack rip or an included patch that outweighs the game;
 * - a multi-disc package, where the largest track is one disc of several and the
 *   digest it produces identifies nothing;
 * - a `.cue`/`.bin` pair, where the largest entry is the data track and the
 *   *descriptor* is what a hasher has to be handed.
 *
 * And it is wrong silently. Whatever it picks gets hashed, the hash matches
 * nothing, and the file is recorded as a game the database does not have — which
 * is indistinguishable from a genuine miss and so never gets looked at again.
 *
 * So this filters by what the platform can actually run, prefers the entry named
 * after the archive, and when it still cannot tell says [Selection.Ambiguous]
 * rather than guessing. An ambiguous archive is a diagnostic, not a miss.
 *
 * MAME and Neo Geo are deliberately not served here: their archives hold a pile
 * of separately-dumped chips and their identity is the romset *name*, so there
 * is no entry to pick. `ScreenScraperSystemMap.matchedByName` decides that, and
 * a caller that reaches this for an arcade set has already gone wrong.
 */
object ArchiveSelector {

    data class Entry(val name: String, val size: Long, val isDirectory: Boolean = false) {
        val baseName: String get() = name.substringAfterLast('/').substringAfterLast('\\')
        val extension: String get() = baseName.substringAfterLast('.', "").lowercase()
        val stem: String get() = baseName.substringBeforeLast('.')
    }

    sealed interface Selection {
        /** Exactly one entry is the ROM. */
        data class One(val entry: Entry, val why: String) : Selection

        /**
         * Several plausible ROMs and nothing to choose between them.
         *
         * Carries the candidates so the scan's diagnostics can name them: the fix
         * is almost always for a person to look at the archive, and a count alone
         * does not tell them which one to open.
         */
        data class Ambiguous(val candidates: List<Entry>) : Selection

        /** Nothing inside the archive is playable on this platform. */
        data class NoPlayableEntry(val entries: List<Entry>) : Selection
    }

    /**
     * Descriptors: text files that name the real data tracks.
     *
     * When one is present it *is* the entry point, whatever its size — a `.cue`
     * is a few hundred bytes beside a 700 MB `.bin`, so "largest" picks exactly
     * the wrong one. Listed first in the selection order for that reason.
     */
    val DESCRIPTOR_EXTENSIONS = setOf("cue", "gdi", "m3u", "ccd", "toc")

    /**
     * Which descriptor is the entry point when an archive holds more than
     * one kind, the lower number first.
     *
     * A playlist names the sheets of a game's discs, so it stands above
     * them: a game of two discs packed with its playlist has three
     * descriptors, and only the playlist says which disc is the first. A
     * `.cue` or a `.gdi` is a sheet rcheevos reads. A `.ccd` and a `.toc`
     * are sheets it does not read, kept by some tools beside the `.cue` of
     * the same disc, and one chosen ahead of that `.cue` would be a disc
     * nobody can hash with a sheet for it lying there.
     */
    private fun descriptorRank(entry: Entry): Int = when (entry.extension) {
        "m3u" -> 0
        "cue", "gdi" -> 1
        else -> 2
    }

    /**
     * What each platform can run, by Pegasus short name.
     *
     * Compared after [FuzzyMatch.normalizePlatform], which already folds the
     * spellings that differ between Pegasus, ES-DE and libretro — so `genesis`
     * and `megadrive` are one key here rather than two entries that can drift.
     *
     * Deliberately generous within a platform and strict across them: including
     * an extension a system cannot run costs an ambiguity, while omitting one it
     * can costs a game.
     *
     * Every platform whose games come on discs lists `m3u`. A game of
     * several discs is packed with the playlist that orders them, and where
     * the list left it out the playlist was not looked at: the sheets of the
     * discs were left to compete, and nothing could choose between them.
     */
    private val PLATFORM_EXTENSIONS: Map<String, Set<String>> = mapOf(
        "nes"          to setOf("nes", "fds", "unf", "unif", "nsf", "qd"),
        "snes"         to setOf("sfc", "smc", "fig", "swc", "bs", "st"),
        "n64"          to setOf("n64", "z64", "v64", "ndd", "u1"),
        "gb"           to setOf("gb", "dmg", "gbs"),
        "gbc"          to setOf("gbc", "gb", "cgb"),
        "gba"          to setOf("gba", "agb"),
        "nds"          to setOf("nds", "dsi"),
        "3ds"          to setOf("3ds", "3dsx", "cci", "cxi", "app"),
        "gc"           to setOf("iso", "gcm", "gcz", "rvz", "ciso", "dol", "tgc"),
        "wii"          to setOf("iso", "wbfs", "rvz", "gcz", "ciso", "wad", "dol"),
        // `genesis`, not `megadrive`: normalizePlatform folds the two onto the
        // former, so an entry under the latter could never be reached. The test
        // asserts every key here is already in normalised form for that reason.
        "genesis"      to setOf("md", "gen", "smd", "bin", "mdx", "68k"),
        "mastersystem" to setOf("sms", "bms", "bin"),
        "gamegear"     to setOf("gg", "bin"),
        "sega32x"      to setOf("32x", "bin"),
        "segacd"       to setOf("cue", "bin", "iso", "chd", "ccd", "img", "m3u"),
        "saturn"       to setOf("cue", "bin", "iso", "chd", "ccd", "mds", "toc", "m3u"),
        "dreamcast"    to setOf("gdi", "cdi", "chd", "cue", "bin", "iso", "m3u"),
        "psx"          to setOf("cue", "bin", "img", "iso", "chd", "pbp", "ecm", "mdf", "mds", "ccd", "m3u"),
        "ps2"          to setOf("iso", "bin", "cue", "chd", "cso", "ciso", "img", "mdf", "isz", "m3u"),
        "psp"          to setOf("iso", "cso", "pbp", "prx", "elf", "m3u"),
        "pcengine"     to setOf("pce", "sgx", "cue", "chd", "ccd", "toc", "m3u"),
        "pcenginecd"   to setOf("cue", "chd", "ccd", "toc", "bin", "img", "iso", "m3u"),
        "atari2600"    to setOf("a26", "bin"),
        "atari7800"    to setOf("a78", "bin"),
        "lynx"         to setOf("lnx"),
        "jaguar"       to setOf("j64", "jag", "rom", "abs", "cof", "prg"),
        "wonderswan"   to setOf("ws"),
        "wonderswancolor" to setOf("wsc", "ws"),
        "ngp"          to setOf("ngp", "ngc", "npc"),
        "ngpc"         to setOf("ngpc", "ngc", "ngp", "npc"),
        "virtualboy"   to setOf("vb"),
        "colecovision" to setOf("col", "cv", "rom", "bin"),
        "msx"          to setOf("rom", "mx1", "mx2", "dsk", "cas", "sc"),
        "3do"          to setOf("iso", "cue", "bin", "chd", "m3u"),
        "c64"          to setOf("d64", "t64", "crt", "prg", "tap", "g64", "x64"),
        "amiga"        to setOf("adf", "ipf", "hdf", "lha", "adz")
    )

    /**
     * The union, used when the platform is unknown or unlisted.
     *
     * Weaker than a platform's own set — it lets a `.bin` and an `.iso` in one
     * archive both look playable — but it is honest about that, and the result
     * is an ambiguity to look at rather than a wrong hash to never notice.
     */
    private val ANY_ROM_EXTENSION: Set<String> =
        PLATFORM_EXTENSIONS.values.flatten().toSet() + DESCRIPTOR_EXTENSIONS +
        setOf("rom", "iso", "bin", "chd", "wad", "cso", "pbp", "rvz", "gcm", "wbfs")

    /**
     * Files that are never the ROM, whatever their size.
     *
     * `md` is deliberately **absent**: it is Markdown to most software and a Mega
     * Drive cartridge here, and excluding it would drop a whole platform's dumps
     * to tidy away a readme that the extension filter already refuses anyway.
     *
     * Internal, and not private, for [ConsoleChoice]: a file that is never
     * the ROM inside an archive is not one outside it either.
     */
    internal val NEVER_THE_ROM = setOf(
        "txt", "nfo", "diz", "html", "htm", "jpg", "jpeg", "png", "gif", "bmp",
        "pdf", "doc", "url", "sfv", "md5", "sha1", "dat", "xml", "json", "log",
        "ips", "bps", "ups", "xdelta", "ppf",     // patches, not games
        "sav", "srm", "state", "cfg", "ini"
    )

    fun extensionsFor(platform: String): Set<String> {
        val key = FuzzyMatch.normalizePlatform(platform)
        return PLATFORM_EXTENSIONS[key] ?: ANY_ROM_EXTENSION
    }

    /**
     * The lists above on one line, the same line for the same lists however
     * they were written down: what a verdict on an archive depends on, so
     * that a change to one of them can be told from none ([HashRecipe]).
     * Every set is sorted, and each descriptor has its rank beside it.
     */
    internal fun describe(): String = buildString {
        append("platforms[")
        append(PLATFORM_EXTENSIONS.toSortedMap().entries
            .joinToString(";") { "${it.key}:${it.value.sorted().joinToString(",")}" })
        append("] any[").append(ANY_ROM_EXTENSION.sorted().joinToString(","))
        append("] never[").append(NEVER_THE_ROM.sorted().joinToString(","))
        append("] descriptors[").append(DESCRIPTOR_EXTENSIONS.sorted()
            .joinToString(",") { "$it:${descriptorRank(Entry("disc.$it", 1))}" })
        append(']')
    }

    /**
     * Picks the ROM out of [entries], or explains why it cannot.
     *
     * [archiveName] is the container's own file name; the entry named after it is
     * strongly preferred, because that is the convention every ROM set follows and
     * it settles the overwhelming majority of multi-entry archives.
     */
    fun select(entries: List<Entry>, archiveName: String, platform: String): Selection {
        val real = entries.filter { !it.isDirectory && it.size > 0 && it.extension !in NEVER_THE_ROM }
        if (real.isEmpty()) return Selection.NoPlayableEntry(entries)

        val allowed = extensionsFor(platform)
        val playable = real.filter { it.extension in allowed }
        if (playable.isEmpty()) return Selection.NoPlayableEntry(real)
        if (playable.size == 1) return Selection.One(playable[0], "the only playable entry")

        val archiveStem = archiveName.substringAfterLast('/').substringBeforeLast('.')

        // A descriptor names the tracks, so it is the entry point whatever it
        // weighs, and where there are several the kind that stands above the
        // others ([descriptorRank]). The tracks are out of it from here on.
        // Among several sheets they used to compete for the archive's name
        // too: in `Game.zip` with a second disc beside the first, `Game.cue`
        // and `Game.bin` both bore the name, and neither was chosen.
        val descriptors = playable.filter { it.extension in DESCRIPTOR_EXTENSIONS }
        if (descriptors.isNotEmpty()) {
            val first = descriptors.minOf { descriptorRank(it) }
            val leading = descriptors.filter { descriptorRank(it) == first }
            if (leading.size == 1) return Selection.One(leading[0], "the disc descriptor")
            val named = leading.filter { it.stem.equals(archiveStem, ignoreCase = true) }
            if (named.size == 1) return Selection.One(named[0], "the descriptor named after the archive")
            return Selection.Ambiguous(leading.sortedByDescending { it.size })
        }

        // `Contra (USA).zip` holding `Contra (USA).nes` — the convention every ROM
        // set follows, and what makes a bonus file or an included patch lose.
        val named = playable.filter { it.stem.equals(archiveStem, ignoreCase = true) }
        if (named.size == 1) return Selection.One(named[0], "named after the archive")

        return Selection.Ambiguous(playable.sortedByDescending { it.size })
    }

    /**
     * The same for an archive in [collection], whose entries are held to one
     * list: that of the short name, or of the folder's name where the folder
     * says more of what it holds ([CollectionRef.hasherPlatform]). A folder
     * `gamegear` of a collection that calls itself `mastersystem` holds Game
     * Gear cartridges, and by the short name's list a zipped one has nothing
     * playable in it.
     */
    fun select(entries: List<Entry>, archiveName: String, collection: CollectionRef): Selection =
        select(entries, archiveName, collection.hasherPlatform)
}
