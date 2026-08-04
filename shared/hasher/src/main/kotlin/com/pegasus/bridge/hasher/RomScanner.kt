package com.pegasus.bridge.hasher

import java.io.File

object RomScanner {

    private val ROM_EXTENSIONS = setOf(
        "bin", "iso", "gba", "gbc", "gb", "nes", "sfc", "smc",
        "md", "gen", "smd", "n64", "z64", "v64", "nds", "3ds",
        "psp", "a26", "a78", "lnx", "pce", "sgx", "ws", "wsc",
        "32x", "gg", "sms", "sg", "col", "ngp", "ngc", "vb",
        "fig", "swc", "zip", "7z", "chd", "cso", "pbp", "cue",
        "m3u", "gdi", "cdi", "rvz", "gcm", "mdf", "img", "wad", "wbfs"
    )

    fun scan(dirs: List<String>): List<File> {
        val results = mutableListOf<File>()
        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in ROM_EXTENSIONS }
                .forEach { results.add(it) }
        }
        return results
    }
}

data class GameMetadata(
    val gameId: Int = 0,
    val title: String = "",
    val consoleName: String = "",
    val imageIcon: String = "",
    val numAchievements: Int = 0
)
