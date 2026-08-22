package com.pegasus.bridge.pegasus

import java.io.File

/**
 * One game's scraped metadata, in the shape a Pegasus metafile wants.
 *
 * The other half of the export. `.media/` gives Pegasus the pictures; without
 * this it still has no description, no genre, no developer, no year and no
 * rating, and derives every title from a filename — which on a No-Intro library
 * means the games are called `Contra (USA)`.
 *
 * ── What a second file may and may not say ─────────────────
 *
 * Read out of `PegasusMetadata.cpp` rather than assumed, because it decides
 * whether an overlay can carry these at all. `game:` runs
 * `ps.cur_game = sctx.create_game()` — a **new** object every time, with no
 * get-or-create by title. Then `apply_game_entry` resolves each `file:` through
 * `game_by_filepath`, and a path already claimed by a different game object is
 * refused with *"This file already belongs to a different game"* and skipped.
 *
 * So two files declaring the same ROM do not merge: the second loses its file
 * and becomes a game with nothing in it. An overlay is therefore safe exactly
 * when nothing else declares that ROM — which is what [claimedElsewhere] is for.
 *
 * Games the overlay does *not* declare are unaffected: the collection's
 * `extensions:` filter still picks them up, so an export covering half a library
 * leaves the other half exactly as it was.
 */
data class GameEntry(
    val title: String,
    /** The ROM's name, relative to the directory the metafile sits in. */
    val fileName: String,
    val developer: String = "",
    val publisher: String = "",
    val genres: List<String> = emptyList(),
    val description: String = "",
    /** Pegasus accepts a single number or a range, which is what sources give. */
    val players: String = "",
    /** `YYYY`, `YYYY-MM` or `YYYY-MM-DD`. */
    val release: String = "",
    /** 0–100, written with a percent sign. */
    val rating: Int = 0,
    /**
     * This game's own launch command, overriding its collection's.
     *
     * Pegasus supports it — `GameAttrib::LAUNCH_CMD` calls `setLaunchCmd` on the
     * game — and it is what makes "choose an emulator for this one title"
     * expressible at all. Empty means the collection's applies.
     */
    val launch: String = "",
    /** Where this came from, for a comment above the entry. */
    val source: String = ""
) {

    /**
     * The entry, or an empty string when there is nothing worth writing.
     *
     * A title and a file alone are not worth an entry: Pegasus already derives
     * both from the filename, and writing them would claim the ROM — locking out
     * any later, better source for no gain. So an entry is produced only when it
     * carries something the frontend does not already have.
     */
    fun render(): String {
        if (!hasSomethingToSay()) return ""
        return buildString {
            if (source.isNotEmpty()) appendLine("# $source")
            appendLine("game: ${oneLine(title)}")
            appendLine("file: ${oneLine(fileName)}")
            if (developer.isNotEmpty()) appendLine("developer: ${oneLine(developer)}")
            if (publisher.isNotEmpty()) appendLine("publisher: ${oneLine(publisher)}")
            if (genres.isNotEmpty()) appendLine("genres: ${genres.joinToString(", ") { oneLine(it) }}")
            if (players.isNotEmpty()) appendLine("players: ${oneLine(players)}")
            if (release.isNotEmpty()) appendLine("release: ${oneLine(release)}")
            if (rating in 1..100) appendLine("rating: $rating%")
            // After the metadata and before the description, so a long synopsis
            // cannot come between the game and the command that runs it.
            if (launch.isNotEmpty()) appendLine("launch: ${oneLine(launch)}")
            if (description.isNotEmpty()) {
                appendLine("description: ${descriptionBody()}")
            }
        }
    }

    private fun hasSomethingToSay(): Boolean =
        title.isNotEmpty() && fileName.isNotEmpty() && (
            developer.isNotEmpty() || publisher.isNotEmpty() || genres.isNotEmpty() ||
            description.isNotEmpty() || players.isNotEmpty() || release.isNotEmpty() ||
            rating in 1..100 ||
            // A chosen emulator is reason enough on its own: it is a decision
            // somebody made, and it has nowhere else to be expressed.
            launch.isNotEmpty())

    /**
     * A description across several lines, indented so the field does not end.
     *
     * A blank line would terminate the entry, and a line of text at column zero
     * would be read as the next field — so a synopsis containing a paragraph
     * break has to be escaped rather than passed through. Pegasus spells a
     * paragraph break as a line holding a single dot.
     */
    private fun descriptionBody(): String {
        val paragraphs = description.trim()
            .replace("\r\n", "\n")
            .split(Regex("\n[ \t]*\n"))
            .map { it.replace('\n', ' ').replace(Regex(" +"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return ""
        return buildString {
            append(paragraphs.first())
            for (p in paragraphs.drop(1)) {
                append("\n  .")
                append("\n  ").append(p)
            }
        }
    }

    /** Collapses anything that would break out of a single-line field. */
    private fun oneLine(v: String) = v.replace(Regex("[\r\n]+"), " ").trim()

    companion object {

        /**
         * Every ROM already claimed by a `file:`/`files:` entry under [dir].
         *
         * The check that keeps an overlay from producing a game with no files.
         * Deliberately textual: reproducing Pegasus' whole parser to answer one
         * question would be a second implementation to keep in step, and a
         * `file:` line is unambiguous enough to read directly.
         *
         * [ignore] is the Bridge's own overlay, which must not be counted as a
         * competitor to itself when an export is run twice.
         */
        fun claimedElsewhere(dir: File, ignore: File? = null): Set<String> {
            val out = HashSet<String>()
            for (f in MetadataFile.allIn(dir)) {
                if (ignore != null && f.absolutePath == ignore.absolutePath) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                var inFiles = false
                for (raw in text.lineSequence()) {
                    val line = raw.trimEnd()
                    if (line.isBlank() || line.trimStart().startsWith("#")) continue
                    val indented = line.first().isWhitespace()
                    if (indented) {
                        if (inFiles) out += line.trim()
                        continue
                    }
                    val key = line.substringBefore(':', "").trim().lowercase()
                    inFiles = key == "file" || key == "files"
                    if (inFiles) line.substringAfter(':').trim()
                        .takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
            return out
        }

        /** A year, or a full date, from whatever a source called a release. */
        fun normaliseRelease(raw: String): String {
            val t = raw.trim()
            Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(t)?.let { return it.value }
            Regex("^(\\d{4})-(\\d{2})").find(t)?.let { return it.value }
            Regex("(\\d{4})").find(t)?.let { return it.groupValues[1] }
            return ""
        }

        /**
         * A rating out of 100, from the several scales the sources use.
         *
         * ScreenScraper is handed over as `"80/100"` — already converted from its
         * own twenty-point scale — while IGDB and IGN give a bare number out of
         * 100 and some give a fraction. Anything unreadable is no rating rather
         * than a guess: a wrong score displayed confidently is worse than none.
         */
        fun normaliseRating(raw: String): Int {
            val t = raw.trim().removeSuffix("%")
            if (t.isEmpty()) return 0
            t.substringBefore('/').trim().toDoubleOrNull()?.let { n ->
                val outOf = t.substringAfter('/', "").trim().toDoubleOrNull()
                return when {
                    outOf != null && outOf > 0 -> Math.round(n / outOf * 100).toInt()
                    n <= 1.0 && t.contains('.') -> Math.round(n * 100).toInt()
                    else -> Math.round(n).toInt()
                }.coerceIn(0, 100)
            }
            return 0
        }
    }
}
