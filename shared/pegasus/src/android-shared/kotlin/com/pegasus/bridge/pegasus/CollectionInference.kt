package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.FuzzyMatch
import java.io.File

/**
 * What a directory full of ROMs would be, if somebody declared it.
 *
 * Pegasus finds games through metadata files. A directory holding four hundred
 * megabytes of cartridges and no metafile is, to Pegasus, empty — and writing
 * that metafile is the manual step this exists to remove. Measured on the tablet
 * this was built against: `gba` (395 MB), `n64` (306 MB) and `n3ds` (44 MB) were
 * all invisible, three of the six collections that hold real ROMs.
 *
 * ── Three sources, and why there are three ────────────────
 *
 * 1. **ES-DE's `systeminfo.txt`**, if the library happens to have one. Best
 *    when present, absent by definition on a library ES-DE never touched, so it
 *    can never be the only source. See [EsDeSystemInfo].
 * 2. **The ScreenScraper system table** — 250 systems, each with its aliases and
 *    its extensions, which the Bridge already downloads and caches for scraping.
 *    This is the answer that works with no ES-DE anywhere, and it is passed in
 *    rather than read here so that neither shell's copy of this module has to
 *    depend on the scrapers.
 * 3. **The directory itself** — its name, and the extensions of the files in it.
 *    Always available, least trustworthy, and the only source that can be fooled
 *    by a save file sitting next to a cartridge.
 *
 * Every field records which of the three it came from, and the proposal says
 * plainly when a person has to look. That is not decoration: measured across the
 * twenty populated directories on the tablet, the system table alone resolved
 * seventeen, failed to recognise `n3ds` at all, and answered `switch` and
 * `windows` with no usable extension list.
 *
 * ── It proposes. It never writes ──────────────────────────
 *
 * Same rule as the rest of this module, and here it matters more than anywhere:
 * a wrong `extensions:` line does not fail loudly, it makes games quietly
 * disappear. So this returns proposals, a person accepts one, and what gets
 * written is the same deletable overlay everything else writes.
 */
object CollectionInference {

    /** Where a field's value came from, in descending order of authority. */
    enum class Source {
        /** ES-DE's own definition for the platform. */
        SYSTEMINFO,
        /** ScreenScraper's system table. */
        SYSTEM_TABLE,
        /** The extensions of the files actually present. */
        OBSERVED,
        /** The directory's own name, tidied up. */
        DIRECTORY_NAME,
        /** Nothing could supply it. */
        NONE
    }

    data class Field<out T>(val value: T, val source: Source)

    /** What a system table can say about one platform. */
    data class SystemFacts(val displayName: String, val extensions: List<String>)

    /** A system table, keyed however the caller likes; it is asked by short name. */
    fun interface SystemLookup {
        fun find(shortName: String): SystemFacts?
    }

    data class Proposal(
        val directory: File,
        val name: Field<String>,
        val shortName: Field<String>,
        val extensions: Field<List<String>>,
        /** Files that would become games under [extensions]. */
        val matchedFiles: Int,
        /** Every file in the directory that is not obviously not a ROM. */
        val candidateFiles: Int,
        val bytes: Long,
        /**
         * Why this directory has no games in Pegasus today.
         *
         * Said out loud because the two causes want different explanations: a
         * directory with no metafile at all is the ordinary case, and one whose
         * declaration exists but matches nothing is a stale file somebody should
         * probably delete rather than a gap to fill.
         */
        val because: String,
        /**
         * What a person has to decide before this can be accepted. Empty means
         * every field came from a source that knew the answer.
         */
        val review: List<String>
    ) {
        val confident: Boolean get() = review.isEmpty()
    }

    /**
     * Directories under [roots] that hold files and produce no games.
     *
     * Deliberately not "directories with no metafile". `gba` on the tablet has a
     * declaration — a Logiqx `.dat` — and Pegasus rejects it, because every
     * `rom` element in it names a `.jud` placeholder from a different machine.
     * A scan looking for missing metafiles would walk straight past the largest
     * broken collection in the library. What matters is whether any games come
     * out, so that is what is asked.
     */
    fun undeclaredUnder(roots: List<File>, systems: SystemLookup): List<Proposal> {
        val out = mutableListOf<Proposal>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            val candidates = listOf(root) + (root.listFiles { f -> f.isDirectory }?.toList() ?: emptyList())
            for (dir in candidates) {
                if (dir.name.lowercase() in SKIP_DIRS) continue
                if (!seen.add(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))) continue
                proposalFor(dir, systems)?.let { out += it }
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    /**
     * A proposal for one directory, or null when it needs none.
     *
     * Null covers both halves of "needs none": a directory with no game files in
     * it, and one whose existing collection already picks its games up.
     */
    fun proposalFor(dir: File, systems: SystemLookup): Proposal? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile }?.toList().orEmpty()
        val candidates = files.filter { isCandidate(it) }
        if (candidates.isEmpty()) return null

        val existing = MetadataFile.readCollection(dir)
        val because = when {
            existing == null -> "no Pegasus metadata file declares this directory"
            existing.extensions.isEmpty() ->
                "${existing.file.name} declares '${existing.name}' but lists no extensions"
            candidates.none { it.extension.lowercase() in existing.extensions.map(String::lowercase) } ->
                "${existing.file.name} declares '${existing.name}' with extensions " +
                "${existing.extensions.joinToString(", ")}, and no file here has one"
            // It already works. Nothing to propose.
            else -> return null
        }

        val review = mutableListOf<String>()
        val esde = EsDeSystemInfo.read(dir)

        // ── short name ──────────────────────────────────────────────────────
        val shortName = when {
            !esde?.systemName.isNullOrEmpty() -> Field(esde!!.systemName, Source.SYSTEMINFO)
            else -> Field(FuzzyMatch.normalizePlatform(dir.name).ifEmpty { dir.name },
                          Source.DIRECTORY_NAME)
        }

        val facts = lookUp(systems, shortName.value, dir.name)

        // ── display name ────────────────────────────────────────────────────
        val name = when {
            !esde?.fullName.isNullOrEmpty() -> Field(esde!!.fullName, Source.SYSTEMINFO)
            facts != null && facts.displayName.isNotEmpty() ->
                Field(facts.displayName, Source.SYSTEM_TABLE)
            else -> {
                review += "no system table entry matched '${shortName.value}', so the collection " +
                          "would be named after its directory"
                Field(titleCase(dir.name), Source.DIRECTORY_NAME)
            }
        }

        // ── extensions ──────────────────────────────────────────────────────
        // The order is authority, not convenience: a declared list describes the
        // platform, an observed one describes one person's directory on one day.
        val observed = candidates.map { it.extension.lowercase() }.distinct().sorted()
        var extensions: Field<List<String>> = when {
            !esde?.extensions.isNullOrEmpty() -> Field(esde!!.extensions, Source.SYSTEMINFO)
            !facts?.extensions.isNullOrEmpty() -> Field(facts!!.extensions, Source.SYSTEM_TABLE)
            else -> {
                review += "nothing declares the extensions for '${shortName.value}', so they " +
                          "were read off the files present: ${observed.joinToString(", ")}"
                Field(observed, Source.OBSERVED)
            }
        }

        // A declared list that matches nothing here is worse than no answer: it
        // would be written, look authoritative, and hide every game.
        //
        // So when it matches *nothing*, the observed extensions are added to it
        // rather than the proposal being left broken for somebody to repair by
        // hand. Found on the tablet: ScreenScraper gives the 3DS `3ds` alone,
        // and the cartridge sitting in `n3ds/` is a `.cci` — a real 3DS format
        // its list omits. The union declares both and the game appears.
        //
        // Only when it matches nothing, deliberately. A list that matches *some*
        // files is describing the platform correctly and the rest are probably
        // not games — a `.cue` collection with `.bin` tracks beside it is the
        // ordinary case, and unioning there would declare every track a game.
        var declared = extensions.value.map(String::lowercase).toSet()
        var matched = candidates.count { it.extension.lowercase() in declared }
        if (matched == 0 && observed.isNotEmpty() && extensions.source != Source.OBSERVED) {
            val union = (extensions.value + observed).distinct()
            review += "nothing here matched the declared extensions " +
                      "(${extensions.value.joinToString(", ")}), so the files' own " +
                      "(${observed.joinToString(", ")}) were added — check they are all games"
            extensions = Field(union, Source.OBSERVED)
            declared = union.map(String::lowercase).toSet()
            matched = candidates.count { it.extension.lowercase() in declared }
        } else if (matched == 0) {
            review += "none of the ${candidates.size} file(s) here match the extensions " +
                      "${extensions.value.joinToString(", ")} — the files are " +
                      "${observed.joinToString(", ")}"
        } else if (matched < candidates.size) {
            review += "${candidates.size - matched} of ${candidates.size} file(s) would be left " +
                      "out; they are ${observed.filter { it !in declared }.joinToString(", ")}"
        }

        dir.listFiles { f -> f.isFile && f.extension.equals("dat", true) }?.firstOrNull()?.let {
            review += "${it.name} is a Logiqx datfile Pegasus also reads; if it names files that " +
                      "are not here it will keep reporting an empty collection until it is removed"
        }

        return Proposal(
            directory = dir,
            name = name,
            shortName = shortName,
            extensions = extensions,
            matchedFiles = matched,
            candidateFiles = candidates.size,
            bytes = candidates.sumOf { it.length() },
            because = because,
            review = review
        )
    }

    /**
     * Asks the system table under every name this directory might go by.
     *
     * Kept out of `FuzzyMatch.normalizePlatform` on purpose. That table is
     * mirrored in the theme's `RAFuzzyMatch.js` and its output is part of the
     * RetroAchievements cache key, so adding an entry on one side and not the
     * other is exactly the drift its comment warns about — and what a *folder*
     * happens to be called is a different question from what a platform is
     * named. Measured need: ScreenScraper knows `3ds` and `Nintendo 3DS` and has
     * never heard of `n3ds`, which is what ES-DE calls the directory.
     */
    private fun lookUp(systems: SystemLookup, vararg names: String): SystemFacts? {
        for (n in names) {
            if (n.isEmpty()) continue
            systems.find(n)?.let { return it }
            DIRECTORY_ALIASES[n.lowercase()]?.let { alias -> systems.find(alias)?.let { return it } }
        }
        return null
    }

    /**
     * Folder names no system table recognises, and what they mean.
     *
     * Grows only from libraries actually seen, never from guessing: an alias
     * that maps a folder onto the wrong system writes a confident, wrong
     * `extensions:` line, and that hides games rather than failing loudly.
     */
    private val DIRECTORY_ALIASES = mapOf(
        "n3ds" to "3ds",        // ES-DE's folder name for the 3DS
        "nds" to "nintendo ds", // ScreenScraper's first match for `nds` is the DSi
        "psvita" to "ps vita",
        "gc" to "gamecube",
        "sfc" to "super nintendo",
        "md" to "megadrive"
    )

    /** `n3ds` -> `N3ds` is poor, but it is honest about being a directory name. */
    private fun titleCase(s: String): String = s.split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /**
     * Whether a file could be a game.
     *
     * The suffix check is not decoration: `standAside` leaves a
     * `metadata.pegasus.txt.pegasusbridge-backup` next to the file it stood
     * aside from, and its "extension" is a word no blocklist would think to
     * carry. On `megadrive` — whose own ROMs were being dropped by the bug
     * below — that backup was the only file left, matched nothing, and the
     * collection was reported broken by the very act of repairing it.
     */
    private fun isCandidate(f: File): Boolean {
        if (f.name.endsWith(MetadataFile.BACKUP_SUFFIX)) return false
        if (f.name.startsWith(".")) return false
        val ext = f.extension.lowercase()
        return ext.isNotEmpty() && ext !in NOT_A_ROM
    }

    private val SKIP_DIRS = setOf("media", ".media", "skraper", "images", "downloaded_media")

    /**
     * Extensions that are never a game.
     *
     * Save states, saves, artwork, and the bookkeeping files a scraper leaves
     * behind. Deliberately a blocklist and not an allowlist: an allowlist would
     * have to know every platform's cartridge extension to be complete, and the
     * one it did not know would be the one somebody's library used. Getting this
     * wrong in the other direction merely adds a line to [Proposal.review].
     *
     * `frz` is on it because seven of them were sitting in the tablet's `snes`
     * directory next to the cartridges.
     */
    private val NOT_A_ROM = setOf(
        "srm", "sav", "save", "state", "frz", "dsv", "rtc", "mcr", "mcd", "vmu", "vms",
        "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9",
        "ss0", "ss1", "ss2", "ss3", "ss4", "ss5", "ss6", "ss7", "ss8", "ss9",
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "mp4", "webm", "avi", "mkv",
        "txt", "dat", "xml", "json", "cfg", "ini", "log", "nfo", "db",
        // `md` is deliberately absent. It is Markdown, and it is also every
        // Mega Drive cartridge — fifteen of them in this library, all dropped
        // when it was on this list, which reported the collection as holding
        // nothing but its own backup file. A stray README in a ROM folder is a
        // far smaller problem than a console that vanishes.
        "cht", "opt", "bak", "tmp", "part", "nomedia", "url", "lnk", "desktop"
    )
}
