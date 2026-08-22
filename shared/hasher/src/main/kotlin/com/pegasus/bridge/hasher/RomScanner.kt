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
     * So [scan] takes a resolver, and the daemon points it at the collection's
     * own declaration.
     */
    val ROM_EXTENSIONS = setOf(
        "bin", "iso", "gba", "gbc", "gb", "nes", "sfc", "smc",
        "md", "gen", "smd", "n64", "z64", "v64", "nds", "3ds",
        "psp", "a26", "a78", "lnx", "pce", "sgx", "ws", "wsc",
        "32x", "gg", "sms", "sg", "col", "ngp", "ngc", "vb",
        "fig", "swc", "zip", "7z", "chd", "cso", "pbp", "cue",
        "m3u", "gdi", "cdi", "rvz", "gcm", "mdf", "img", "wad", "wbfs"
    )

    /**
     * Every ROM under [dirs].
     *
     * [extensionsFor] is asked once per directory and answers which extensions
     * count there — the built-in set by default, and the collection's own
     * declaration when a caller can supply one. Answering with the union rather
     * than a replacement is deliberate: a collection that forgets to list `zip`
     * should not lose its archives.
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

        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                // Never the root itself: a user whose game directory is literally
                // named `media` would otherwise have their whole library skipped.
                .onEnter { it == dir || it.name.lowercase() !in SKIPPED_DIRS }
                .filter { it.isFile }
                .forEach { f ->
                    val parent = f.parentFile ?: dir
                    val allowed = perDir.getOrPut(parent.path) {
                        runCatching { extensionsFor(parent) }.getOrDefault(ROM_EXTENSIONS)
                    }
                    if (f.extension.lowercase() in allowed) results.add(f)
                }
        }
        BridgeLog.d(TAG, "scanned ${dirs.size} root(s) for ${results.size} files")
        return results
    }

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

data class GameMetadata(
    val gameId: Int = 0,
    val title: String = "",
    val consoleName: String = "",
    val imageIcon: String = "",
    val numAchievements: Int = 0
)
