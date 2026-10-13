package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * What a verdict on a file was reached with, written down, and the number
 * the ledger keeps beside the verdict so that it can tell whether the same
 * would be reached today.
 *
 * That number was a constant somebody raised by hand whenever hashing or
 * the choice of an archive's entry changed what a file came to. It was
 * raised three times and should have been more often: a longer list of
 * what a platform runs, a console given to a collection, a library of
 * another release each change the answer for some files, and each left the
 * old answers standing for as long as they keep, a season for some. And
 * raised, it threw away every verdict of the library to redo the few that
 * the change was about.
 *
 * So the number is worked out from the things themselves. [global] names
 * what holds for every file: the library that hashes, and the tables a
 * file is judged by. [versionFor] adds what holds for one collection: its
 * names, its row in the console table, the extensions it declares. A table
 * that gains an entry changes the number by itself, for every collection or
 * for the one whose row it is, and nobody has to remember to.
 *
 * One thing holds for the files of one extension and no other: a reader of
 * our own in the library ([READERS]). It is in the number of the files it
 * reads, so that a build that gains one, or reads otherwise, has those
 * files looked at again and leaves the rest of the library as it stands.
 * A `.wbfs` and a `.chd` are read so.
 *
 * What cannot be read off a table is the code that uses them, and for that
 * a number is still raised by hand, [RULES].
 *
 * A match stands in the ledger under its number like any other verdict, and
 * is skipped by it. Past a new number it is taken back from its metadata
 * file, which describes the file it was matched from, with no read. So what
 * a new number costs is the files that did not match, read once more and
 * asked about once more where they give a hash, and with them a file that
 * shares its game's metadata file with another, which that file does not
 * describe.
 *
 * [engine] is the hasher's own name for itself ([RomHasher.engine]).
 */
class HashRecipe internal constructor(
    engine: String,
    /** A collection's row as one line. A test hands in a row of its own, as an edit to the table would be. */
    private val describeRow: (CollectionRef) -> String
) {

    constructor(engine: String) : this(engine, { RcConsoles.describe(RcConsoles.resolve(it.shortName, it.dirName)) })

    /**
     * What every verdict depends on, whatever the file's collection, on one
     * line for the log and for the first line of an audit's table. Each
     * table is in it as the CRC-32 of its content, so the line stays short
     * and says which of them moved between two builds:
     *
     * - `rules`: [RULES];
     * - `rc`: the engine;
     * - `ext`: the console rcheevos gives each extension ([RomHashIO.RC_SINGLE]);
     * - `con`: how each console is hashed, and the ones that are not compiled
     *   or are held back ([RcConsoles.CONSOLES], [RcConsoles.NOT_COMPILED],
     *   [RcConsoles.HELD_BACK]);
     * - `sel`: what each platform runs, what is never a ROM and which
     *   descriptor leads ([ArchiveSelector.describe]);
     * - `disc`: the sheets that are read and the ones that are not, how
     *   long a sheet and a track's name may be ([DescriptorSet]), and how
     *   far into a playlist its first entry is looked for
     *   ([PlaylistReader.READ_LIMIT]);
     * - `deny`: the three lists of files nobody hashes ([ConsoleChoice]),
     *   the second with the extensions of [READERS] still in it, as they
     *   were while this build had no reader for them: taken out, the line
     *   would be another, and every verdict of every library with it;
     * - `containers`: [CONTAINERS];
     * - `ph`: [PLACEHOLDERS], and what [PlaceholderRule] goes by.
     */
    val global: String = listOf(
        "rules=$RULES",
        "rc=$engine",
        "ext=" + crc(RomHashIO.RC_SINGLE.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value ?: "-"}" }),
        "con=" + crc(
            RcConsoles.CONSOLES.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.algorithm}" } +
            " not[" + RcConsoles.NOT_COMPILED.map { it.name }.sorted().joinToString(",") + "]" +
            " held[" + RcConsoles.HELD_BACK.sorted().joinToString(",") + "]"),
        "sel=" + crc(ArchiveSelector.describe()),
        "disc=" + crc(
            "read[" + ArchiveAwareHasher.READ_SHEETS.sorted().joinToString(",") + "]" +
            " unread[" + ArchiveAwareHasher.UNREAD_SHEETS.sorted().joinToString(",") + "]" +
            " sheet<=${DescriptorSet.SHEET_LIMIT} name<=${DescriptorSet.NAME_LIMIT}" +
            " playlist<=${PlaylistReader.READ_LIMIT}"),
        "deny=" + crc(
            "always[" + ConsoleChoice.DENIED_ALWAYS.sorted().joinToString(",") + "]" +
            " build[" + (ConsoleChoice.DENIED_THIS_BUILD + READERS.keys).sorted().joinToString(",") + "]" +
            " not[" + ConsoleChoice.NOT_A_ROM.sorted().joinToString(",") + "]"),
        "containers=$CONTAINERS",
        "ph=$PLACEHOLDERS,${PlaceholderRule.TEXT_LIMIT}," + crc(PlaceholderRule.TEXT_BY_NATURE.sorted().joinToString(","))
    ).joinToString(";")

    /** The number of [global] alone: what the ledger's own header carries. */
    private val ofNoCollection = fold(checksum(global))

    /**
     * Worked out once for a collection and what reads the file, which is no
     * reader of ours for nearly all of them: a scan asks for every file of
     * the collection, and for most of them twice.
     */
    private val known = ConcurrentHashMap<Pair<CollectionRef?, String>, Int>()

    /**
     * The number a verdict on a file of [collection] is kept under, or with
     * null the number of [global] by itself.
     *
     * The collection is in it by the two names a row is found by, by the
     * row those give ([RcConsoles.resolve], as [RcConsoles.describe] writes
     * it), and by what it declares. So a row that is edited redoes the
     * verdicts of the collections it is the row of and leaves every other
     * collection's alone; and a file whose folder is given to another
     * collection, or whose collection is given another short name or told
     * of another extension, is looked at again, since the verdict was on a
     * file of the collection it was in then. What a collection is called in
     * full is not in the number, and neither is where its folder lies.
     *
     * The folder's name is the name on the path the scan came by. A file
     * reached once through a link and once through what the link points at
     * can so have two numbers, and is then read again each time the way
     * changes.
     *
     * [extension] is the file's own, in any case and without the dot. For
     * one a reader of ours reads ([READERS]) the number has the reader in
     * it, with a collection and without one; for every other file, and for
     * a caller that names none, the number is what it was before there
     * were readers.
     */
    fun versionFor(collection: CollectionRef?, extension: String = ""): Int {
        val reader = readerOf(extension)
        if (collection == null && reader.isEmpty()) return ofNoCollection
        return known.computeIfAbsent(collection to reader) {
            val parts = if (collection == null) listOf(global)
                        else listOf(global, collection.shortName, collection.dirName, describeRow(collection),
                                    collection.declaredExtensions.sorted().joinToString(","))
            fold(checksum((parts + listOfNotNull(reader.takeIf { it.isNotEmpty() })).joinToString("|")))
        }
    }

    companion object {
        /**
         * The number still raised by hand: for a change to how a file is
         * judged that no table shows. The order in which [ConsoleChoice]
         * asks its questions is one, what [ArchiveSelector] does with the
         * entries it is left with another, and the way [DescriptorSet]
         * reads a sheet a third. An entry added to a table needs nothing
         * done here. The two below are raised by hand as well, each for
         * one corner, so that [global] says which corner moved.
         *
         * 5 came after the four numbers the ledger's version had while it
         * was a constant (their history is in [ScanLedger]).
         *
         * 6: the entries of an archive are held to what the collection's
         * row and its own metafile say it holds, beside the list of its
         * name ([ArchiveSelector.extensionsFor]). The lists themselves did
         * not move, so no checksum did, and an archive kept as one with
         * nothing playable in it would have stood for its month.
         */
        const val RULES = 6

        /**
         * Frozen as it stood when this build had no reader for either
         * packed format. A reader was to write its number here, and that
         * would have redone every verdict of every collection for the sake
         * of one extension: this text is in [global], of which a character
         * changed is another number for every file there is. Readers are
         * counted in [READERS] and nothing here moves again.
         */
        const val CONTAINERS = "wbfs0,chd0"

        /**
         * The readers of our own that the library has, by the extension
         * each reads, and the turn each is at: what the library's version
         * says after its slash ([RcheevosNative.EXPECTED_VERSION]). A file
         * of such an extension is kept under a number with the reader in
         * it ([versionFor]), so a reader that is new, or that comes to
         * answer otherwise and is counted up, redoes those files and no
         * other. Another reader is another entry, and moves nobody else's
         * number: `chd` came after `wbfs` and left the number of a .wbfs,
         * and of every other file, what it was.
         *
         * The number is that of a file called by the extension. A packed
         * disc is also reached through a file of another name, an archive
         * that holds one or a playlist that names one, and the verdict an
         * older build kept for such a file is under a number no reader is
         * in. What takes that one again is said at [ScanLedger.canSkip].
         */
        val READERS: Map<String, Int> = mapOf("wbfs" to 1, "chd" to 1)

        /**
         * The readers on one line, in the order they came, as the library
         * writes them after the slash of its version; for a log and for an
         * audit's table: `wbfs1,chd1`.
         */
        val readers: String = READERS.entries.joinToString(",") { "${it.key}${it.value}" }

        /**
         * How a verdict ended while a build had no reader for [extension],
         * which is in lower case and without its dot. [ConsoleChoice] says
         * it of whatever it still turns away for that, and the ledger knows
         * by it the verdicts a build without a reader left
         * ([ScanLedger.canSkip]).
         */
        fun noReaderFor(extension: String): String = ".$extension is a format this build has no reader for"

        /** What the number of a file with [extension] gains, or nothing when no reader of ours reads it. */
        private fun readerOf(extension: String): String {
            val ext = extension.lowercase(Locale.ROOT)
            return READERS[ext]?.let { "reader=$ext$it" }.orEmpty()
        }

        /**
         * Raised with a change to what [PlaceholderRule] takes for text. A
         * placeholder is a verdict that never runs out, so a new number is
         * all that has one looked at again.
         */
        const val PLACEHOLDERS = 1

        /**
         * The highest number the ledger's version had while a person chose
         * it. A derived number is never one of those, or a ledger written
         * under version 3 could meet a build whose number came out as 3 and
         * be believed.
         */
        internal const val LAST_BY_HAND = 4

        private fun checksum(text: String): Long =
            CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value

        private fun crc(text: String): String = checksum(text).toString(16).padStart(8, '0')

        /**
         * A CRC-32 as the ledger's number: its low 31 bits, so that it is
         * a positive Int wherever a ledger is read, and moved past
         * [LAST_BY_HAND] where it falls on one of the old numbers or on 0,
         * which is what a ledger entry with no version reads as.
         */
        internal fun fold(crc: Long): Int {
            val low = (crc and 0x7fffffffL).toInt()
            return if (low <= LAST_BY_HAND) low + LAST_BY_HAND + 1 else low
        }
    }
}
