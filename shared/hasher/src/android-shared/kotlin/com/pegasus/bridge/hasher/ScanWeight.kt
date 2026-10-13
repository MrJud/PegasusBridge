package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import java.util.Locale

/**
 * How much of a scan one file is expected to be, worked out before the scan
 * reads anything, so that its progress can be a share of the work and not of
 * the files.
 *
 * Counted by files, a Wii disc that took three seconds to hash and a file
 * whose verdict stood, which took none, moved the bar by the same step.
 * Counted by size it would be as wrong the other way: a disc of four
 * gigabytes is hashed from a few of its sectors, and a file the ledger has
 * a verdict for is not read at all.
 *
 * So a file weighs one unit for being looked at, and one more for every
 * [UNIT] bytes the scan is expected to read of it. Integer division: a file
 * under 64 KiB that is not a disc's sheet, and every file that is not
 * read, weighs exactly 1, which leaves a library of small or of settled
 * files with the share it always had, to the last bit.
 *
 * The bounds are from scans that were measured, not from the formats' own
 * definitions:
 *
 * - a disc is hashed from what a parser finds in it. A `.chd` of 4.06 GB
 *   was hashed in 0.09 s, a few megabytes read whatever the image's size;
 * - a disc's sheet, a `.cue` or a `.gdi`, is a few lines that name the
 *   tracks, and it is of those that the parser reads: for a sheet of 71
 *   bytes, of a disc made up for the test, 29,582 bytes were read. So a
 *   sheet weighs as a disc and not by its own size;
 * - a Wii disc is the one that is not cheap: nineteen `.wbfs` and one
 *   `.chd` read 843 MiB between them, about 44 MiB a disc, and were the
 *   five slowest files of a first scan at 2.4 to 3.2 s each;
 * - a Nintendo DS or DSi cartridge is hashed from its header and two of
 *   its programs: 0.71 MB read of a file of 67 MB;
 * - rcheevos reads no more than 64 MiB of a file it hashes whole
 *   ([ArchiveAwareHasher.DIGEST_LIMIT]), and the second read for the plain digests
 *   is made only of a file up to that size;
 * - an archive's entry is unpacked whole, so an archive is its size.
 *
 * Over that first scan, 1951 files and 17.76 GB read in 196 s of hashing,
 * the reading is what the time went with. On a rescan of 969 settled files
 * nothing was read in 3.7 s, and every file weighs the same.
 *
 * An estimate and nothing a verdict rests on: a file that turns out to be
 * read otherwise moves the bar by more or less than it took, and no more.
 */
object ScanWeight {

    /** The reading that weighs as much as one file looked at. */
    const val UNIT = 65_536L

    /** What is read of a disc hashed by a parser, and of a cartridge hashed by one. */
    const val DISC_BYTES = 4L shl 20

    /** What is read of a Wii disc, a little over the 44 MiB measured. */
    const val WII_BYTES = 48L shl 20

    /** The most rcheevos reads of a file hashed whole: [ArchiveAwareHasher.DIGEST_LIMIT]. */
    const val WHOLE_BYTES = 64L shl 20

    /**
     * The two consoles that are not discs and are hashed from a small part
     * of the file all the same: the Nintendo DS and the DSi.
     */
    val PARSED_CARTRIDGES: Set<Int> = setOf(18, 78)

    /** The extensions rcheevos, left to guess, takes for a disc. */
    private val GUESSED_AS_DISC = setOf("chd", "iso", "cue", "gdi")

    /**
     * The two of them that are not the disc but a few lines naming its
     * tracks. What is read is of the tracks, so their own size says
     * nothing of it.
     */
    private val SHEETS = setOf("cue", "gdi")

    /**
     * The weight of a file of [size] bytes with [extension] (in any case,
     * without the dot) that the scan has [plan] for. [standing] says the
     * ledger holds a verdict the scan expects to find standing, which is a
     * file it does not read.
     */
    fun of(plan: ConsoleChoice.Plan, extension: String, size: Long, standing: Boolean): Long =
        1 + expectedRead(plan, extension, size.coerceAtLeast(0), standing) / UNIT

    private fun expectedRead(plan: ConsoleChoice.Plan, extension: String, size: Long, standing: Boolean): Long {
        if (standing) return 0
        val ext = extension.lowercase(Locale.ROOT)
        // What a parser reads of a disc: of the image up to its size, and
        // of what a sheet names whatever the sheet's.
        val ofADisc = if (ext in SHEETS) DISC_BYTES else minOf(size, DISC_BYTES)
        return when (plan) {
            is ConsoleChoice.Plan.Unsupported,
            is ConsoleChoice.Plan.UnsupportedFormat,
            ConsoleChoice.Plan.ArcadeSet -> 0
            ConsoleChoice.Plan.OpenArchive -> size
            is ConsoleChoice.Plan.Hash -> when {
                plan.console == ConsoleChoice.WII -> minOf(size, WII_BYTES)
                plan.console in PARSED_CARTRIDGES ||
                    RcConsoles.console(plan.console)?.algorithm == RcConsoles.Algorithm.DISC -> ofADisc
                else -> minOf(size, WHOLE_BYTES)
            }
            ConsoleChoice.Plan.Guess ->
                if (ext in GUESSED_AS_DISC) ofADisc else minOf(size, WHOLE_BYTES)
            // A playlist is a few lines; what it names is read as whatever
            // it is, which is not known until the playlist is.
            ConsoleChoice.Plan.ResolvePlaylist -> minOf(size, WHOLE_BYTES)
        }
    }
}
