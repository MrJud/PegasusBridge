package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import java.io.File

object RomScanner {

    /**
     * The extensions a scan recognises without being told otherwise.
     *
     * A default and not a definition. A Pegasus collection declares its own
     * `extensions:` line, and the two disagree more often than is comfortable —
     * on the library this was written against every collection declares `jud`,
     * which is not here. That happens to be harmless there, because those files
     * are zero-byte placeholders; on a library that used a non-standard
     * extension for real dumps, the whole platform would scan as zero files and
     * nothing anywhere would say why.
     *
     * So [scan] takes a resolver, and both shells point it at the collection's
     * own declaration.
     *
     * The home-computer formats are here because leaving them out was not
     * harmless: `amiga`, `amstradcpc` and `apple2` each hold one real dump and
     * each scanned as **zero files**, so no hash was taken and no lookup was
     * ever made — the failure this comment already predicted, found on a real
     * device rather than reasoned about.
     */
    val ROM_EXTENSIONS = setOf(
        "bin", "iso", "gba", "gbc", "gb", "nes", "sfc", "smc",
        "md", "gen", "smd", "n64", "z64", "v64", "nds", "3ds",
        "psp", "a26", "a78", "lnx", "pce", "sgx", "ws", "wsc",
        "32x", "gg", "sms", "sg", "col", "ngp", "ngc", "vb",
        "fig", "swc", "zip", "7z", "chd", "cso", "pbp", "cue",
        "m3u", "gdi", "cdi", "rvz", "gcm", "mdf", "img", "wad", "wbfs",
        // Home computers: Amiga, Amstrad CPC, Apple II, C64, Spectrum, MSX
        "adf", "adz", "ipf", "hdf", "hdz", "dms", "lha",
        "dsk", "cpr", "cdt", "sna", "voc",
        "do", "po", "nib", "woz", "2mg",
        "d64", "t64", "tap", "prg", "crt",
        "cv", "rom"
    )

    /**
     * Every ROM under [dirs].
     *
     * [extensionsFor] is asked once per directory and answers which extensions
     * count there — the built-in set by default, and the collection's own
     * declaration when a caller can supply one. Answering with the union rather
     * than a replacement is deliberate: a collection that forgets to list `zip`
     * should not lose its archives.
     *
     * Each file is returned once, under the path it was first reached by, however
     * many of [dirs] reach it. The theme does not hand over one root per
     * collection: it hands over the folder of every game it knows, so `psx/<game>`
     * arrives beside `psx`, and `switch` beside a folder nested inside it.
     * Without this a tree reached twice was hashed twice and counted twice.
     */
    fun scan(
        dirs: List<String>,
        extensionsFor: (File) -> Set<String> = { ROM_EXTENSIONS }
    ): List<File> {
        val results = mutableListOf<File>()
        // One answer per directory, cached: the resolver may read a metadata file
        // from disk, and a library of several thousand ROMs would otherwise ask
        // for the same collection's extensions once per file.
        val perDir = HashMap<String, Set<String>>()
        // By canonical path, so a repeated root, a root inside another (in either
        // order) and a symlinked alias are all the same directory, entered once.
        // It is also what stops a symlink pointing back up the tree: `walkTopDown`
        // follows links, and went round until the path held too many of them to
        // resolve — 41 copies of the one ROM in the test that measures it.
        //
        // Directories alone are not enough — a symlinked *file* is a second name
        // for the same ROM inside a directory entered only once — hence the second
        // set. Exact paths, never prefixes: `Final Fantasy` and `Final Fantasy IX`
        // are two games.
        val enteredDirs = HashSet<String>()
        val keptFiles = HashSet<String>()

        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                // Never the root itself: a user whose game directory is literally
                // named `media` would otherwise have their whole library skipped.
                // Checked before the directory is marked entered, so a skipped
                // `media` that is later given as a root of its own is still walked.
                .onEnter { (it == dir || it.name.lowercase() !in SKIPPED_DIRS) && enteredDirs.add(canonical(it)) }
                .filter { it.isFile }
                .forEach { f ->
                    val parent = f.parentFile ?: dir
                    val allowed = perDir.getOrPut(parent.path) {
                        runCatching { extensionsFor(parent) }.getOrDefault(ROM_EXTENSIONS)
                    }
                    if (f.extension.lowercase() in allowed && keptFiles.add(canonical(f))) results.add(f)
                }
        }
        BridgeLog.d(TAG, "scanned ${dirs.size} root(s) for ${results.size} files")
        return results
    }

    /** The pipeline's spelling of a path too, so the scanner and the ledger agree. */
    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /**
     * Directories that never hold ROMs, and cost real time to walk.
     *
     * `media` is the one that matters: it holds one picture per game and often
     * several, so on a large library it is the biggest directory in the tree and
     * every file in it would be tested against the extension set for nothing.
     */
    private val SKIPPED_DIRS = setOf("media", ".media", "skraper", "images", "videos", "manuals")

    private const val TAG = "RomScanner"
}
