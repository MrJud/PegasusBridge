package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.RcConsoles
import java.io.File

object RomScanner {

    /**
     * The extensions a scan recognises without being told otherwise.
     *
     * A default and not a definition. A Pegasus collection may declare its
     * own `extensions:` line, and the two need not agree. On the library this
     * was written against they never met: its collections declare no
     * extension at all and name each game with a `file:` line, a placeholder
     * called `.jud`, which a scan does not read and so does not pick up. That
     * is harmless there, because those files stand for games that are not on
     * the disk; on a library that used a non-standard extension for real
     * dumps, the whole platform would scan as zero files and nothing anywhere
     * would say why.
     *
     * So a scan adds what the file's collection declares
     * ([scanWithCollections]), for both shells alike.
     *
     * The home-computer formats are here because leaving them out was not
     * harmless: `amiga`, `amstradcpc` and `apple2` each hold one real dump and
     * each scanned as **zero files**, so no hash was taken and no lookup was
     * ever made — the failure this comment already predicted, found on a real
     * device rather than reasoned about.
     *
     * `fds` and `neo` are here because the console table has a rule for each
     * and no scan reached either: a Famicom disk under `nes` is hashed as the
     * Disk System's, and a Neo Geo cartridge kept as one `.neo` file is the
     * one file of an arcade collection that is hashed by what it holds. Left
     * out, both were found only where a collection listed them.
     *
     * `md` is not here, and it was. It is a Mega Drive cartridge and it is
     * Markdown, and a list cannot tell which: counted everywhere, it made a
     * game of the README.md of every tool, mod and BIOS pack kept under a
     * library, each read and asked about. Where it counts is a question for
     * the file's collection ([countsMarkdownAsCartridge]).
     */
    val ROM_EXTENSIONS = setOf(
        "bin", "iso", "gba", "gbc", "gb", "nes", "sfc", "smc",
        "gen", "smd", "n64", "z64", "v64", "nds", "3ds",
        "psp", "a26", "a78", "lnx", "pce", "sgx", "ws", "wsc",
        "32x", "gg", "sms", "sg", "col", "ngp", "ngc", "vb",
        "fig", "swc", "zip", "7z", "chd", "cso", "pbp", "cue",
        "m3u", "gdi", "cdi", "rvz", "gcm", "mdf", "img", "wad", "wbfs",
        // Home computers: Amiga, Amstrad CPC, Apple II, C64, Spectrum, MSX
        "adf", "adz", "ipf", "hdf", "hdz", "dms", "lha",
        "dsk", "cpr", "cdt", "sna", "voc",
        "do", "po", "nib", "woz", "2mg",
        "d64", "t64", "tap", "prg", "crt",
        "cv", "rom",
        // A Famicom Disk System disk, and a Neo Geo cartridge in one file.
        "fds", "neo"
    )

    /** A ROM a scan found, with the collection its folder is in. */
    data class ScannedFile(val file: File, val collection: CollectionRef)

    /**
     * Every ROM under [dirs], each with its collection.
     *
     * [resolver] is asked once for each folder that holds a file, here, while
     * one thread walks the tree, and the answer travels with the file: what
     * comes after asks nothing of a folder again, and cannot take a file for
     * another collection than the one that decided whether it is a ROM.
     *
     * What counts as a ROM in a folder is the built-in set and whatever the
     * folder's collection declares. A union and not a replacement: a
     * collection that forgets to list `zip` should not lose its archives.
     * The collection is the nearest one declared at or above the folder, so
     * an extension it declares counts in the folders under it as well. It
     * counted in the collection's own folder and nowhere below, when each
     * shell read the metafile of the one folder it was asked about.
     *
     * `md` is the one extension the collection has to answer for, since the
     * built-in set no longer has it: it counts where the collection lists it,
     * as any extension does, and where the collection is one that holds Mega
     * Drive cartridges, listed or not.
     *
     * [extensionsOverride], when given, answers for a folder in place of all
     * that. Nothing but a test gives one.
     *
     * Each file is returned once, under the path it was first reached by, however
     * many of [dirs] reach it. The theme does not hand over one root per
     * collection: it hands over the folder of every game it knows, so `psx/<game>`
     * arrives beside `psx`, and `switch` beside a folder nested inside it.
     * Without this a tree reached twice was hashed twice and counted twice.
     */
    fun scanWithCollections(
        dirs: List<String>,
        resolver: CollectionResolver,
        extensionsOverride: ((File) -> Set<String>)? = null
    ): List<ScannedFile> {
        class Folder(val collection: CollectionRef, val allowed: Set<String>)
        return walk(
            dirs,
            about = { dir ->
                // A folder nothing can be learnt of is the folder it is called,
                // as it was before anything was asked: one that cannot be
                // resolved must not end the scan of a library.
                val collection = runCatching { resolver.collectionOf(dir) }
                    .getOrElse { CollectionRef.inferred(dir.name) }
                val allowed = if (extensionsOverride != null)
                                  runCatching { extensionsOverride(dir) }.getOrDefault(ROM_EXTENSIONS)
                              else ROM_EXTENSIONS + collection.declaredExtensions +
                                   (if (countsMarkdownAsCartridge(collection)) MEGA_DRIVE_ONLY else emptySet())
                Folder(collection, allowed)
            },
            allowed = { it.allowed }
        ).map { (file, folder) -> ScannedFile(file, folder.collection) }
    }

    /**
     * Whether a `.md` in [collection] is a cartridge without the collection
     * saying so: when the console table knows the collection as one whose
     * files may be of the Mega Drive, console 1. That is `megadrive` and
     * `genesis` under either name, with a metafile or with none, and a folder
     * `sega32x` or `segacd` whose collection calls itself `megadrive`. A
     * collection the table knows by neither of its names is not one,
     * whatever it holds: it has to list the extension, as it has to list
     * any other the built-in set lacks.
     *
     * The row is the one a scan goes on to plan the file by
     * ([ConsoleChoice.choose]), which refuses a `.md` outside this same
     * family. So a file let in here is never one refused there for being
     * Markdown.
     */
    private fun countsMarkdownAsCartridge(collection: CollectionRef): Boolean {
        val row = RcConsoles.resolve(collection.shortName, collection.dirName)
        return row is RcConsoles.Hashable && MEGA_DRIVE in row.family
    }

    /** RC_CONSOLE_MEGA_DRIVE. */
    private const val MEGA_DRIVE = 1
    private val MEGA_DRIVE_ONLY = setOf("md")

    /**
     * Every ROM under [dirs], by a rule the caller gives and with no word of
     * collections: [extensionsFor] is asked once per directory and answers
     * which extensions count there. For the tests of the walk itself.
     */
    fun scan(
        dirs: List<String>,
        extensionsFor: (File) -> Set<String> = { ROM_EXTENSIONS }
    ): List<File> = walk(
        dirs,
        about = { dir -> runCatching { extensionsFor(dir) }.getOrDefault(ROM_EXTENSIONS) },
        allowed = { it }
    ).map { it.first }

    /**
     * The walk both of the above are: every file under [dirs] whose extension
     * is in what [allowed] makes of its folder's answer from [about], with
     * that answer.
     */
    private fun <T> walk(
        dirs: List<String>,
        about: (File) -> T,
        allowed: (T) -> Set<String>
    ): List<Pair<File, T>> {
        val results = mutableListOf<Pair<File, T>>()
        // One answer per directory, cached: it may take reading a metadata file
        // from disk, and a library of several thousand ROMs would otherwise ask
        // about the same folder once per file.
        val perDir = HashMap<String, T>()
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
                    val folder = perDir.getOrPut(parent.path) { about(parent) }
                    if (f.extension.lowercase() in allowed(folder) && keptFiles.add(canonical(f))) {
                        results.add(f to folder)
                    }
                }
        }
        BridgeLog.d(TAG, "scanned ${dirs.size} root(s) for ${results.size} files")
        return results
    }

    /**
     * The one spelling of a path that a file is known by: to this walk, which
     * keeps each file once however many roots and links reach it, to the
     * ledger, which keeps a verdict under it, and to an audit, which writes a
     * row for it. It was written out in each of the three, alike; alike is
     * what it has to stay.
     *
     * The real path, every link in it followed. Asked of the canonical path
     * alone, a link was followed on Linux and Android and not on Windows, where
     * the JDKs this builds on leave a link in a canonical path as it is
     * written: a folder reached through a link was a second folder there, its
     * files were scanned twice, and a link pointing back up the tree was walked
     * until the path grew too long to open. The real path also gives a folder
     * its long name where Windows wrote the short one. It can be had only of
     * a file that is there, so one that is not is known by its canonical path
     * as before, and by the absolute one where the system gives neither.
     */
    fun canonical(file: File): String =
        runCatching { file.toPath().toRealPath().toString() }
            .recoverCatching { file.canonicalPath }
            .getOrDefault(file.absolutePath)

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
