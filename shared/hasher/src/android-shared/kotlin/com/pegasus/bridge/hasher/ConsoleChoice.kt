package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import java.util.Locale

/**
 * What to do with one file, from what its collection is and what it is
 * called, before a byte of it is read.
 *
 * Left to itself rcheevos picks a console from the extension, tries each in
 * turn until one gives a hash, and falls back to the MD5 of the whole file. A
 * hash always comes out. For a compressed disc image, for a file of a console
 * nobody can hash, for a readme, it is a hash of nothing, and it is asked
 * about and recorded as a game the database lacks. So the question "can this
 * be hashed, and as what" is answered here, where the answer may be no.
 *
 * Two packed formats are read by readers of our own that the library has
 * beside rcheevos ([HashRecipe.READERS]): a `.wbfs` as a Wii disc, and a
 * `.chd` as a disc of one of ten consoles ([CHD_CONSOLES]).
 *
 * It is a pure function and takes no file: the row of the collection (see
 * [RcConsoles.resolve]), the extension, the size, and whether the file came
 * out of an archive. The first rule that applies decides.
 */
object ConsoleChoice {

    /**
     * Containers and images rcheevos has no reader for, in any build:
     * compressed and converted discs, encrypted or packed cartridges of
     * consoles it does not cover, and the descriptors and side files of
     * formats it does not parse. Handed one, it hashes the bytes of the
     * container.
     */
    val DENIED_ALWAYS: Set<String> = setOf(
        "rvz", "wia", "gcz", "ciso", "cso", "zso", "isz", "ecm", "cdi", "nrg",
        "nsp", "xci", "nsz", "xcz", "ccd", "toc", "mds", "sub"
    )

    /**
     * Formats that could be read and are not by this build. There is none
     * today: `wbfs` and then `chd` were here, and each was taken out by the
     * build that gained its reader and counted in [HashRecipe.READERS],
     * which redoes the verdicts on the files of that extension and on no
     * other. Kept apart from the list above, and kept though it is empty,
     * for the next format a reader is still to be written for.
     */
    val DENIED_THIS_BUILD: Set<String> = emptySet()

    /**
     * The consoles a `.chd` is read as a disc of: PlayStation, PlayStation
     * 2, PSP, Saturn, Sega CD, Dreamcast, PC Engine CD, 3DO, Neo Geo CD
     * and PC-FX. For the first nine the same disc has been seen to hash
     * alike from a `.chd` and from a cue sheet, a `.gdi` or an `.iso`. The
     * PC-FX asks for a disc's largest track, and for that the reader
     * answers as RetroAchievements' own hasher does, which is the hash of
     * the cue sheet for a disc with its data in the second track or the
     * last, and not for every disc (`pb_chd.c`, `choose`). One more
     * console would open a `.chd` and is left out, the Jaguar CD: no disc
     * made here is hashed from one by either program, so there is nothing
     * to hold its answer to. The PlayStation 3 is on the list rcheevos
     * tries a `.chd` as when it is left to guess and is held back
     * ([RcConsoles.HELD_BACK]). Any other console would hash the bytes of
     * the container.
     */
    val CHD_CONSOLES: Set<Int> = setOf(9, 12, 21, 39, 40, 41, 43, 49, 56, 76)

    /**
     * Files that are no game whatever folder they lie in: what an archive
     * never has for its ROM, and four more a scan meets loose. `elf` is here
     * although rcheevos has a console for it, the 3DS, which this build does
     * not hash; everywhere else it is a homebrew loader or a tool.
     */
    val NOT_A_ROM: Set<String> = ArchiveSelector.NEVER_THE_ROM + setOf("bml", "sbi", "lst", "elf")

    /** What is opened as an archive: the reader's own list, and no second one to fall behind it. */
    private val ARCHIVES = ArchiveReader.ARCHIVE_EXTENSIONS

    /** RC_CONSOLE_ARCADE. */
    internal const val ARCADE = 27

    /** RC_CONSOLE_WII. */
    internal const val WII = 19

    /** A Wii disc in a container, which the library has a reader of its own for. */
    private const val WII_CONTAINER = "wbfs"

    /** A disc packed hunk by hunk, which the library has a reader of its own for. */
    private const val PACKED_DISC = "chd"

    /** The one file of an arcade collection that is hashed by its bytes. */
    private const val NEO_GEO_CARTRIDGE = "neo"

    sealed interface Plan {
        /** The collection is of a console nobody can hash for. [reason] is the row's. */
        data class Unsupported(val reason: String) : Plan

        /** The collection can be hashed and this file cannot. */
        data class UnsupportedFormat(val reason: String) : Plan

        /** An arcade set: hashed by the name of the archive, which is not opened. */
        data object ArcadeSet : Plan

        /** An archive to open, for the one entry in it that is the game. */
        data object OpenArchive : Plan

        /** A playlist: its first entry is the file to plan for. */
        data object ResolvePlaylist : Plan

        /**
         * Hash as [console]. [alternates] are tried once each, in order, when
         * that console refuses the file. [foreign] says the extension belongs
         * to a console outside the collection's family: the file is hashed as
         * what its extension says it is, and is in the wrong folder.
         */
        data class Hash(
            val console: Int,
            val alternates: List<Int> = emptyList(),
            val foreign: Boolean = false
        ) : Plan

        /** Nothing is known of the collection: rcheevos guesses, as it always did. */
        data object Guess : Plan
    }

    /**
     * The plan for a file with [extension] (in any case, without the dot) and
     * of [size] bytes, in a collection whose row is [row], or null when the
     * collection has none. [insideArchive] is true for an entry taken out of
     * an archive.
     */
    fun choose(row: RcConsoles.Row?, extension: String, size: Long, insideArchive: Boolean): Plan {
        val ext = extension.lowercase(Locale.ROOT)

        // 1. A collection nobody can hash for: no file of it is worth a look,
        //    whatever it is called.
        val known: RcConsoles.Hashable? = when (row) {
            is RcConsoles.NotOnRa -> return Plan.Unsupported(row.reason)
            is RcConsoles.NoAlgorithm -> return Plan.Unsupported(row.reason)
            is RcConsoles.Hashable -> row
            null -> null
        }

        // 2. A file that cannot be hashed in any collection, an unknown one
        //    included: guessing would only give the MD5 of a container.
        if (ext in NOT_A_ROM) return Plan.UnsupportedFormat("a .$ext file is not a game")
        if (ext in DENIED_ALWAYS) return Plan.UnsupportedFormat(".$ext is a format rcheevos does not read")
        if (ext in DENIED_THIS_BUILD) return Plan.UnsupportedFormat(HashRecipe.noReaderFor(ext))

        // 3. An arcade set is its archive's name. Anything else in such a
        //    collection is a chip, a sample or a disk image of a set, and has
        //    no hash of its own. Neither has a set that came out of another
        //    archive: its copy has a name made up for it. But for a .neo,
        //    which is a Neo Geo cartridge in one file, with its ROMs in it:
        //    rcheevos hashes that by what it holds and not by what it is
        //    called, as the same console.
        if (known != null && known.arcade) {
            return if (ext in ARCHIVES && !insideArchive) Plan.ArcadeSet
            else if (ext == NEO_GEO_CARTRIDGE) Plan.Hash(ARCADE)
            else Plan.UnsupportedFormat(
                if (ext in ARCHIVES) "an arcade set inside an archive has lost its name"
                else "an arcade set is a .zip or a .7z, and this is a .$ext")
        }

        // 4. Any other archive is opened, once.
        if (ext in ARCHIVES) {
            return if (insideArchive) Plan.UnsupportedFormat("an archive inside an archive is not opened")
            else Plan.OpenArchive
        }

        // 5. A playlist is not a game, it names one, for every console:
        //    handed the playlist, most of rcheevos hashes its text.
        if (ext == "m3u") return Plan.ResolvePlaylist

        // 6. A .wbfs holds a Wii disc wherever it lies, in a folder nothing
        //    is known of as well: left to guess, rcheevos has no console
        //    for the extension and would take the file for a Game Boy
        //    cartridge. Not one that came out of an archive, though. The
        //    entry is several GiB to copy out, and a disc that is split has
        //    left its other files behind: the reader would ask for them at
        //    every scan, and the archive be unpacked again each time.
        if (ext == WII_CONTAINER) {
            return if (insideArchive) Plan.UnsupportedFormat("a .wbfs inside an archive is not unpacked")
            else Plan.Hash(WII, foreign = known != null && WII !in known.family)
        }

        // 7. Nothing known of the collection.
        if (known == null) return Plan.Guess

        // 8. A known collection. What the row says of the extension or the
        //    size comes first; then the extension's own console, when rcheevos
        //    gives it one: in the family it is simply taken, outside it the
        //    file is a stray, hashed as what it is when that can be hashed.
        //    Everything else is the collection's console.
        //
        //    A .chd is a disc, and where the row says nothing of it, it is
        //    the disc the row's cue sheet is: under `pcengine` a .cue is a
        //    PC Engine CD game, and a .chd is no HuCard either.
        var foreign = false
        val single = RomHashIO.RC_SINGLE[ext]
        val console = known.overridesByExtension[ext]
            ?: known.overridesByExtension["cue"].takeIf { ext == PACKED_DISC }
            ?: known.overridesBySize.firstOrNull { ext in it.extensions && size > it.above }?.console
            ?: when {
                single == null -> known.console
                single in known.family -> single
                RcConsoles.canHash(single) -> { foreign = true; single }
                else -> known.console
            }

        // Five cases where that console would give a hash of the wrong thing.
        // A .md outside the Mega Drive's family is Markdown.
        if (ext == "md" && 1 !in known.family)
            return Plan.UnsupportedFormat("a .md file in this collection is not a Mega Drive cartridge")
        // rcheevos reads a .pbp as a PSP game and as nothing else, a
        // PlayStation one packed for the PSP included.
        if (ext == "pbp" && known.console != 41)
            return Plan.UnsupportedFormat("rcheevos reads a .pbp only as a PSP game")
        // A descriptor is a few lines of text, and a console that hashes the
        // whole file hashes those.
        if ((ext == "cue" || ext == "gdi") && RcConsoles.console(console)?.algorithm != RcConsoles.Algorithm.DISC)
            return Plan.UnsupportedFormat("a .$ext describes a disc, and console $console is not hashed as one")
        // A .chd is read as a disc, by a reader that has been held to ten
        // consoles. As a console that hashes the whole file it would be the
        // hash of the container, and as one of the other disc consoles a
        // hash nothing here has seen to be the disc's.
        if (ext == PACKED_DISC && console !in CHD_CONSOLES)
            return Plan.UnsupportedFormat("a .chd is read as a disc of ten consoles, and console $console is not one of them")
        // A PC Engine CD game is hashed from its descriptor or from a .chd:
        // for any other file rcheevos has no case, and says so only after
        // reading it.
        if (console == 76 && ext != "cue" && ext != PACKED_DISC)
            return Plan.UnsupportedFormat("a PC Engine CD game is hashed from its .cue or its .chd, and this is a .$ext")

        return Plan.Hash(console, known.alternates[ext].orEmpty().filter { it != console }, foreign)
    }

    /**
     * What rcheevos answered for a file it was left to guess the console of
     * ([Plan.Guess]), as it is to be kept.
     *
     * The guess is rcheevos' own: for each extension a list of consoles,
     * tried in turn up to the first that gives a hash. That list knows
     * nothing of [RcConsoles.HELD_BACK], and for an `.iso` it has the
     * PlayStation 3 on it. A hash under a console that is held back is one
     * nobody is to ask about, so it is a failure. It is not one to try
     * again: the file was read, and will be taken for the same at the next
     * scan.
     *
     * For a `.chd` every other console on the list is one a `.chd` is
     * read as ([CHD_CONSOLES]), the PC-FX among them, which is tried last.
     * A hash under a console outside them is turned away here all the
     * same, as lastingly as a known collection of such a console is
     * turned away before the file is read: the list is rcheevos' own, and
     * what it comes to have on it is not to be kept unseen. [extension]
     * is the file's, in any case and without the dot.
     */
    fun guessed(outcome: HashOutcome, extension: String = ""): HashOutcome {
        val console = (outcome as? HashOutcome.Ok)?.result?.consoleId ?: return outcome
        if (console in RcConsoles.HELD_BACK) {
            val name = RcConsoles.console(console)?.constant ?: "a console"
            return HashOutcome.Failed("rcheevos takes it for a file of $name (id $console), which is held back",
                                      retryable = false)
        }
        if (extension.lowercase(Locale.ROOT) == PACKED_DISC && console !in CHD_CONSOLES)
            return HashOutcome.Failed("rcheevos takes this .chd for console $console, " +
                                      "which is not one of the ten a .chd is read as", retryable = false)
        return outcome
    }
}
