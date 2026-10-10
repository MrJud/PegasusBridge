package com.pegasus.bridge.hasher

/**
 * The files a disc descriptor names, and which entries of an archive they
 * are.
 *
 * A disc is kept as a sheet of a few lines and the tracks the sheet names,
 * and rcheevos, handed the sheet, opens the tracks from the folder the sheet
 * is in, under the names written in it. So a sheet taken out of an archive
 * by itself cannot be hashed, and to take its tracks out with it somebody
 * has to read the names first. This does, and says where each one is.
 *
 * It is pure: the bytes of the sheet and the listing of the archive come in,
 * and no file is opened. The names go out as the sheet writes them, because
 * that is what rcheevos will ask for, letter for letter, and on most systems
 * a file called `Track.bin` is not found as `track.BIN`.
 */
object DescriptorSet {

    /**
     * The most of a sheet that is read. A real one is a few hundred bytes,
     * and one with a hundred tracks a few thousand; a file of megabytes
     * under a sheet's name is something else.
     */
    const val SHEET_LIMIT = 1024 * 1024

    /**
     * The longest name a sheet can give a file, in the bytes it is written
     * with. No system this runs on has a file of a longer name, and rcheevos
     * keeps a name in 256 bytes or does not keep it (cdreader.c).
     */
    const val NAME_LIMIT = 255

    /** One file a sheet names: [name] as the sheet writes it, and the entry that is it. */
    data class Track(val name: String, val entry: ArchiveSelector.Entry)

    sealed interface Match {
        /** Every name is one entry. In the order the sheet names them. */
        data class Found(val tracks: List<Track>) : Match

        /** The set cannot be put together. [reason] is a sentence for a person. */
        data class Refused(val reason: String) : Match
    }

    /**
     * The file names in a descriptor called [name] whose content is [bytes]:
     * every `FILE` of a `.cue`, the file column of a `.gdi`, and the first
     * entry of an `.m3u`, which is all of a playlist that is hashed. Each
     * name once, in the order met, and as written: one that leads out of
     * the folder is still in the list, for [match] to refuse.
     *
     * Read as rcheevos reads them (cdreader.c), so that the names found
     * here are the names it will open. Nothing for any other extension: a
     * `.ccd` and a `.toc` name their tracks by rules rcheevos has no reader
     * for.
     */
    fun references(name: String, bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.UTF_8)
        val names = when (name.substringAfterLast('.', "").lowercase()) {
            "cue" -> cueFiles(text)
            "gdi" -> gdiFiles(text)
            "m3u" -> listOfNotNull(PlaylistReader.firstLine(text))
            else -> emptyList()
        }
        return names.distinct()
    }

    /**
     * `FILE "Some Game (Track 1).bin" BINARY`, or the name bare when it has
     * no space in it. The keyword in any case, and indented or not.
     */
    private fun cueFiles(text: String): List<String> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trim(' ', '\t', '\r')
            if (line.length < 5 || !line.startsWith("FILE", ignoreCase = true) || !isGap(line[4])) null
            else quotedOrBare(line.substring(5).trimStart(' ', '\t'))
        }.toList()

    /**
     * The first line of a `.gdi` is the number of tracks. Every other is
     * `track lba type sectorsize file offset`, and the file is the fifth
     * field, in quotes when it has a space in it.
     */
    private fun gdiFiles(text: String): List<String> =
        text.lineSequence().drop(1).mapNotNull { raw ->
            var rest = raw.trim()
            repeat(4) {
                val end = rest.indexOfFirst { isGap(it) }
                if (end < 0) return@mapNotNull null
                rest = rest.substring(end).trimStart()
            }
            if (rest.isEmpty()) null else quotedOrBare(rest)
        }.toList()

    /**
     * What is between the quotes when [field] opens with one, to the end of
     * the line when the quote is never closed, and up to the first blank
     * when there is none.
     */
    private fun quotedOrBare(field: String): String =
        if (field.startsWith('"')) field.substring(1).substringBefore('"')
        else field.takeWhile { !isGap(it) }

    private fun isGap(c: Char): Boolean = c == ' ' || c == '\t'

    /**
     * Each of [references] among [entries], by the last part of the entry's
     * name and whatever the case of either: a sheet written on Windows says
     * `GAME.BIN` of a file that is `Game.bin`, and nobody there noticed.
     *
     * A name is one file in the sheet's own folder or it is refused. The
     * tracks are written out under these names, beside the sheet, so a name
     * with a folder in it, one that climbs out with `..`, an absolute one
     * and one with a NUL in it would each be a file written where the
     * archive says and not where this program chose. Refused too: a name
     * that is no entry, since a disc with a track missing has no hash; one
     * that two entries answer to, or that answers for two names, since
     * taking either is a guess; one that was not UTF-8 in the sheet, which
     * rcheevos would ask for by other bytes than a file written here could
     * be given; and one longer than any file's ([NAME_LIMIT]), which is
     * found out here and not when the tracks before it have been written.
     */
    fun match(references: List<String>, entries: List<ArchiveSelector.Entry>): Match {
        if (references.isEmpty()) return Match.Refused("it names no file")
        val files = entries.filter { !it.isDirectory }
        val tracks = mutableListOf<Track>()
        for (name in references) {
            val shown = name.replace('\u0000', '?')
            when {
                name.isEmpty() -> return Match.Refused("it names a file with no name")
                '\u0000' in name || '�' in name ->
                    return Match.Refused("it names '$shown', which is not a name a file can have")
                '/' in name || '\\' in name || name == "." || name == ".." || hasDrive(name) ->
                    return Match.Refused("it names '$shown', which is not a file beside it")
            }
            val length = name.toByteArray(Charsets.UTF_8).size
            if (length > NAME_LIMIT)
                return Match.Refused("it names a file by $length bytes, and no file has a name of more than $NAME_LIMIT")
            val found = files.filter { it.baseName.equals(name, ignoreCase = true) }
            when {
                found.isEmpty() -> return Match.Refused("it names '$shown', which is not in the archive")
                found.size > 1 ->
                    return Match.Refused("it names '$shown', and the archive holds ${found.size} files of that name")
            }
            val other = tracks.firstOrNull { it.entry === found[0] }
            if (other != null)
                return Match.Refused("it names one file twice, as '${other.name}' and as '$shown'")
            tracks += Track(name, found[0])
        }
        return Match.Found(tracks)
    }

    /** `C:name`, which Windows reads as a file on another drive. */
    private fun hasDrive(name: String): Boolean = name.length >= 2 && name[1] == ':' && name[0].isLetter()
}
