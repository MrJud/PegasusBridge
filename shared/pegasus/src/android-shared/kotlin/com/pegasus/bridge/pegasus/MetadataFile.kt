package com.pegasus.bridge.pegasus

import java.io.File

/**
 * Reading and writing Pegasus' own metadata files.
 *
 * The format is `name: value` grouped into collections and games, with
 * continuation lines carrying leading whitespace. Entry names are
 * case-insensitive.
 *
 * ── Why the Bridge needs to *read* these ───────────────────
 *
 * Because the collection already answers two questions the Bridge was guessing
 * at:
 *
 * - **Which files are ROMs.** `RomScanner` carries a hardcoded extension list.
 *   Every collection in the library this was written against declares
 *   `extensions: bin, fds, nes, nsf, qd, rom, unf, unif, jud` — and `jud` is not
 *   in that list, so the scanner and the collection disagree about what a ROM
 *   is. On that library it happens not to matter, because the `.jud` files are
 *   zero-byte placeholders. On one that used a non-standard extension for real
 *   dumps, a whole platform would scan as zero files and say nothing about it.
 * - **What the collection is called.** `shortname: nes` is the platform, stated
 *   rather than inferred from a directory name.
 *
 * ── And why it must be careful about *writing* them ────────
 *
 * A `metadata.pegasus.txt` is hand-edited, and the launch command inside it is
 * the one field that can leave a library unplayable if it is wrong. So the
 * Bridge never edits one. It writes its own file, in Pegasus' `metafiles`
 * directory or under a name of its own, which Pegasus reads alongside and which
 * can be deleted without taking anything else with it.
 */
object MetadataFile {

    /** The names Pegasus accepts, in the order it looks for them. */
    val FILE_NAMES = listOf("metadata.pegasus.txt", "metadata.txt")

    data class Collection(
        val name: String,
        val shortName: String,
        val extensions: List<String>,
        val launch: String,
        val directory: File,
        /** The file the collection's identity came from. */
        val file: File,
        /**
         * The file the effective [launch] came from, which is often not [file].
         *
         * Once an overlay exists, the launch command lives there and the
         * collection's own file has it commented out. Reporting the launch from
         * `metadata.pegasus.txt` alone said "no launch command" for a collection
         * that had just been given a working one.
         */
        val launchFile: File? = null,
        /**
         * More than one file declares a launch for this collection.
         *
         * Pegasus keeps whichever it parses last and does not sort them, so this
         * is a coin toss rather than an override, and worth surfacing.
         */
        val ambiguousLaunch: Boolean = false,
        /** Fields kept verbatim, so a rewrite can preserve what is not understood. */
        val raw: Map<String, String> = emptyMap()
    )

    /** The metadata file governing [dir], or null. */
    fun findIn(dir: File): File? = FILE_NAMES
        .map { File(dir, it) }
        .firstOrNull { it.isFile }
        ?: overlaysIn(dir).firstOrNull()

    /**
     * Every metadata file in [dir], matched the way `is_metadata_file` does.
     *
     * All of them, because Pegasus reads all of them: an overlay beside a
     * collection's own file is a second source for the same collection, and
     * looking at only the first says nothing about what the frontend will
     * actually do.
     */
    fun allIn(dir: File): List<File> {
        val named = FILE_NAMES.map { File(dir, it) }.filter { it.isFile }
        return (named + overlaysIn(dir)).distinctBy { it.absolutePath }
    }

    private fun overlaysIn(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name !in FILE_NAMES &&
            (f.name.endsWith(".metadata.pegasus.txt") || f.name.endsWith(".metadata.txt"))
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * The collection header, if [dir] has a metadata file with one.
     *
     * Only the header is parsed. The game entries are Pegasus' business and the
     * Bridge has no reason to hold an opinion about them.
     */
    fun readCollection(dir: File): Collection? {
        val files = allIn(dir)
        if (files.isEmpty()) return null

        var identity: Pair<File, Map<String, String>>? = null
        val launches = mutableListOf<Pair<File, String>>()

        for (f in files) {
            val fields = runCatching { readHeaderFields(f.readText()) }.getOrNull() ?: continue
            if (fields["collection"] == null) continue
            if (identity == null) identity = f to fields
            val launch = (fields["launch"] ?: fields["command"].orEmpty())
            if (launch.isNotBlank()) launches += f to launch
        }

        val (file, fields) = identity ?: return null
        // The effective launch is whichever file declares one. When exactly one
        // does — which `standAside` is what guarantees — that is the whole
        // answer. When several do, Pegasus picks by filesystem order and the
        // honest thing is to say so rather than name a winner.
        val effective = launches.lastOrNull()
        return Collection(
            name = fields["collection"]!!,
            shortName = fields["shortname"].orEmpty()
                .ifEmpty { launches.firstNotNullOfOrNull { readHeaderFields(it.first.readText())?.get("shortname") }.orEmpty() },
            extensions = splitList(fields["extensions"] ?: fields["extension"].orEmpty()),
            launch = effective?.second.orEmpty(),
            directory = dir,
            file = file,
            launchFile = effective?.first,
            ambiguousLaunch = launches.size > 1,
            raw = fields
        )
    }

    /**
     * Fields up to the first `game:` entry.
     *
     * Continuation lines — anything indented — are folded into the previous
     * field with their newlines kept, because a launch command is written across
     * several lines and losing them turns it into one unrunnable string.
     */
    internal fun readHeaderFields(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        var current: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val key = current ?: return
            out[key] = buffer.toString().trim()
            buffer.setLength(0)
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            if (line.trimStart().startsWith("#")) continue

            val indented = line.first().isWhitespace()
            if (indented && current != null) {
                buffer.append('\n').append(line.trim())
                continue
            }

            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            // The header ends where the games begin.
            if (key == "game") { flush(); return out.takeIf { it.isNotEmpty() } }
            flush()
            current = key
            buffer.append(line.substring(colon + 1).trim())
        }
        flush()
        return out.takeIf { it.isNotEmpty() }
    }

    private fun splitList(value: String): List<String> =
        value.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * Every collection under [roots], found by walking for metadata files.
     *
     * Shallow on purpose — one level of subdirectory below each root, which is
     * how every library in this ecosystem is laid out, plus the root itself.
     * Walking the whole tree would descend into `media/` and into save
     * directories for no gain.
     */
    fun collectionsUnder(roots: List<File>): List<Collection> {
        val out = mutableListOf<Collection>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            val candidates = listOf(root) + (root.listFiles { f -> f.isDirectory }?.toList() ?: emptyList())
            for (dir in candidates) {
                if (dir.name.lowercase() in setOf("media", ".media", "skraper")) continue
                val c = readCollection(dir) ?: continue
                if (seen.add(c.directory.canonicalPath)) out += c
            }
        }
        return out
    }

    /**
     * A Bridge-owned overlay: a collection header and nothing else.
     *
     * Written as a separate file so that deleting it restores exactly the
     * previous behaviour. [preserve] carries across the fields the Bridge has no
     * opinion about — `extensions`, `regex`, `ignore-file` — because dropping
     * them would change which files the collection contains, and this file is
     * only supposed to change how they launch.
     */
    fun renderCollection(
        name: String,
        shortName: String,
        launch: String,
        preserve: Map<String, String> = emptyMap(),
        note: String = ""
    ): String = buildString {
        appendLine("# Written by PegasusBridge. Safe to delete: removing this file")
        appendLine("# restores whatever the collection did before it existed.")
        if (note.isNotEmpty()) note.lineSequence().forEach { appendLine("# $it") }
        appendLine()
        appendLine("collection: $name")
        if (shortName.isNotEmpty()) appendLine("shortname: $shortName")
        for ((k, v) in preserve) {
            if (k in OWNED_FIELDS || v.isBlank()) continue
            appendLine("$k: ${indentContinuations(v)}")
        }
        if (launch.isNotEmpty()) appendLine("launch: ${indentContinuations(launch)}")
    }

    /**
     * A collection overlay plus the game entries the Bridge has metadata for.
     *
     * One Bridge-owned file per collection, not two: a second file declaring the
     * same collection is another source Pegasus has to reconcile, and the launch
     * command already taught this project what that costs.
     *
     * The games are appended after the header, which is where Pegasus expects
     * them — the header ends at the first `game:` and every entry after it
     * belongs to the collections declared so far.
     */
    fun renderCollectionWithGames(
        name: String,
        shortName: String,
        launch: String,
        games: List<GameEntry>,
        preserve: Map<String, String> = emptyMap(),
        note: String = ""
    ): String = buildString {
        append(renderCollection(name, shortName, launch, preserve, note))
        val rendered = games.map { it.render() }.filter { it.isNotEmpty() }
        if (rendered.isEmpty()) return@buildString
        appendLine()
        appendLine("# ${rendered.size} game(s) below. Deleting this file removes them")
        appendLine("# and leaves the collection exactly as it was.")
        for (r in rendered) {
            appendLine()
            append(r)
        }
    }

    /** A multi-line value needs every line after the first indented, or it ends the field. */
    private fun indentContinuations(value: String): String =
        value.lineSequence().mapIndexed { i, l -> if (i == 0) l.trim() else "  ${l.trim()}" }
            .joinToString("\n")

    /** Fields this writer sets itself, so a preserved copy would be a duplicate. */
    private val OWNED_FIELDS = setOf("collection", "shortname", "launch", "command")

    /** What [commentOutLaunch] appends to the file it stood aside from. */
    const val BACKUP_SUFFIX = ".pegasusbridge-backup"

    /**
     * Comments out the `launch:` block of an existing metadata file.
     *
     * Needed because of how Pegasus resolves two files that declare the same
     * collection, which was read out of `PegasusMetadata.cpp` rather than
     * assumed: `get_or_create_collection` returns the *same* collection object
     * and `setCommonLaunchCmd` **overwrites**, so the last file parsed wins — and
     * `find_metafiles_in` uses a bare `QDirIterator` with no sort flag, so which
     * one that is depends on the filesystem.
     *
     * An overlay alongside a file that also sets `launch` is therefore a coin
     * toss, not an override. Leaving exactly one declaration is the only way to
     * get a defined answer.
     *
     * A backup is written first, and the original lines are kept as comments, so
     * this is legible and undoable by hand as well as by [restoreBackup].
     */
    fun commentOutLaunch(file: File, backup: File = File(file.path + BACKUP_SUFFIX)): Boolean {
        val text = file.readText()
        if (!LAUNCH_KEY.containsMatchIn(text)) return false
        if (!backup.exists()) file.copyTo(backup, overwrite = false)

        val out = StringBuilder()
        var inLaunch = false
        for (line in text.lines()) {
            val isKey = LAUNCH_KEY.matches(line)
            val isContinuation = inLaunch && line.isNotBlank() && line.first().isWhitespace()
            when {
                isKey -> {
                    inLaunch = true
                    out.appendLine("# commented out by PegasusBridge; the overlay sets this instead")
                    out.appendLine("# $line")
                }
                isContinuation -> out.appendLine("# $line")
                else -> { inLaunch = false; out.appendLine(line) }
            }
        }
        file.writeText(out.toString().trimEnd() + "\n")
        return true
    }

    /** Puts back what [commentOutLaunch] set aside. */
    fun restoreBackup(file: File, backup: File = File(file.path + BACKUP_SUFFIX)): Boolean {
        if (!backup.isFile) return false
        backup.copyTo(file, overwrite = true)
        backup.delete()
        return true
    }

    /** Whether [file] declares a launch command of its own. */
    fun declaresLaunch(file: File): Boolean =
        runCatching { LAUNCH_KEY.containsMatchIn(file.readText()) }.getOrDefault(false)

    private val LAUNCH_KEY = Regex("^(launch|command)\\s*:.*$", RegexOption.MULTILINE)
}
