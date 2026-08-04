package com.pegasus.bridge.core

// Port esatto di RAFuzzyMatch.js — le firme e l'output devono essere byte-identici.
// Non modificare la logica senza aggiornare anche RAFuzzyMatch.js e rieseguire FuzzyMatchParityTest.
object FuzzyMatch {

    private val PLATFORM_ALIASES: Map<String, String> = mapOf(
        // Nintendo
        "supernintendo" to "snes", "supernes" to "snes", "superfamicom" to "snes",
        "snesfamicom" to "snes", "snessuperfamicom" to "snes",
        "nintendo64" to "n64", "n64" to "n64",
        "nintendods" to "nds", "nds" to "nds", "ds" to "nds",
        "nintendo3ds" to "3ds", "3ds" to "3ds",
        "nintendoentertainmentsystem" to "nes", "famicom" to "nes",
        "gameboyadvance" to "gba", "gba" to "gba",
        "gameboycolor" to "gbc", "gbc" to "gbc",
        "gameboy" to "gb", "gb" to "gb",
        "virtualboy" to "virtualboy",
        "gamecube" to "gc", "gc" to "gc", "ngc" to "gc",
        "wii" to "wii", "wiiu" to "wiiu",
        "switch" to "switch", "nintendoswitch" to "switch",
        // Sega
        "megadrive" to "genesis", "segagenesis" to "genesis", "genesis" to "genesis",
        "segamegadrive" to "genesis", "megadrivegenesis" to "genesis",
        "mastersystem" to "mastersystem", "segamastersystem" to "mastersystem", "sms" to "mastersystem",
        "gamegear" to "gamegear", "gg" to "gamegear", "segagamegear" to "gamegear",
        "segacd" to "segacd", "megacd" to "segacd",
        "sega32x" to "sega32x", "32x" to "sega32x",
        "saturn" to "saturn", "segasaturn" to "saturn",
        "dreamcast" to "dreamcast", "segadreamcast" to "dreamcast", "dc" to "dreamcast",
        "sg1000" to "sg1000",
        // Sony
        "playstation" to "psx", "psx" to "psx", "ps1" to "psx", "psone" to "psx",
        "playstation2" to "ps2", "ps2" to "ps2",
        "playstationportable" to "psp", "psp" to "psp",
        // Atari
        "atari2600" to "atari2600", "atari7800" to "atari7800",
        "atarilynx" to "lynx", "lynx" to "lynx",
        "atarijaguar" to "jaguar", "jaguar" to "jaguar",
        // NEC
        "pcengine" to "pcengine", "turbografx16" to "pcengine", "tg16" to "pcengine",
        "pcenginecd" to "pcenginecd", "turbografxcd" to "pcenginecd",
        "pcfx" to "pcfx",
        // SNK
        "neogeo" to "neogeo", "neogeopocket" to "ngp", "ngp" to "ngp",
        "neogeopocketcolor" to "ngpc", "ngpc" to "ngpc",
        // Other
        "arcade" to "arcade", "mame" to "arcade", "fbneo" to "arcade", "fba" to "arcade",
        "wonderswan" to "wonderswan", "ws" to "wonderswan",
        "wonderswancolor" to "wonderswancolor", "wsc" to "wonderswancolor",
        "colecovision" to "colecovision", "intellivision" to "intellivision",
        "vectrex" to "vectrex", "msx" to "msx", "msx2" to "msx2",
        "amstradcpc" to "amstradcpc", "cpc" to "amstradcpc",
        "zxspectrum" to "zxspectrum", "spectrum" to "zxspectrum",
        "commodore64" to "c64", "c64" to "c64",
        "amiga" to "amiga", "3do" to "3do",
        "pokemini" to "pokemini", "watara" to "supervision"
    )

    // Mirrors RAFuzzyMatch.js:normalize()
    fun normalize(title: String): String {
        if (title.isEmpty()) return ""
        var s = title.lowercase()
        s = s.replace(Regex("""\s*[\(\[][^\)\]]*[\)\]]\s*"""), " ")
        s = s.replace(Regex(",\\s*(the|a|an|le|la|les|el|los|das|der|die)\\s*$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(the|a|an|le|la|les|el|los|das|der|die)\\s+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[^a-z0-9]"), "")
        return s
    }

    // Mirrors RAFuzzyMatch.js:normalizePlatform()
    fun normalizePlatform(raw: String): String {
        if (raw.isEmpty()) return ""
        val key = raw.lowercase().replace(Regex("[^a-z0-9]"), "")
        return PLATFORM_ALIASES[key] ?: key
    }

    // Mirrors RAFuzzyMatch.js:makeCacheKey() — MUST stay in sync
    fun makeCacheKey(gameTitle: String, platformShortName: String): String {
        val t = gameTitle
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
        val p = normalizePlatform(platformShortName)
        return "$t|$p"
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (Math.abs(a.length - b.length) > Math.max(a.length, b.length) * 0.5)
            return Math.max(a.length, b.length)
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                val next = minOf(row[j] + 1, prev + 1, row[j - 1] + cost)
                row[j - 1] = prev
                prev = next
            }
            row[b.length] = prev
        }
        return row[b.length]
    }

    fun similarity(titleA: String, titleB: String): Double {
        val a = normalize(titleA)
        val b = normalize(titleB)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        var containsScore = 0.0
        if (a.contains(b)) containsScore = b.length.toDouble() / a.length
        else if (b.contains(a)) containsScore = a.length.toDouble() / b.length
        val maxLen = maxOf(a.length, b.length)
        val levScore = 1.0 - (levenshtein(a, b).toDouble() / maxLen)
        val minLen = minOf(a.length, b.length)
        var prefixLen = 0
        for (i in 0 until minLen) {
            if (a[i] == b[i]) prefixLen++ else break
        }
        val prefixScore = prefixLen.toDouble() / maxLen
        return maxOf(containsScore * 0.95, levScore * 0.85 + prefixScore * 0.15)
    }

    // ── ROM filename parsing ────────────────────────────────────────────────
    //
    // Ported from the theme's RAFuzzyMatch.js so this knowledge lives on the
    // Bridge side only. A theme should not have to know how No-Intro names files
    // to ask which RetroAchievements game it owns.

    /**
     * A clean title from a ROM path.
     *
     * "Super Mario World (USA) (Rev 1).sfc"            -> "Super Mario World"
     * "/roms/Legend of Zelda, The (USA) (En,Fr).z64"   -> "Legend of Zelda"
     */
    fun extractTitleFromFilename(filePath: String?): String {
        if (filePath.isNullOrEmpty()) return ""
        var name = filePath.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot > 0) name = name.substring(0, dot)
        name = name.replace(Regex("""\s*[\(\[][^\)\]]*[\)\]]"""), "")
        name = name.replace(
            Regex(",\\s*(The|A|An|Le|La|Les|El|Los|Das|Der|Die)\\s*$", RegexOption.IGNORE_CASE), "")
        return name.replace(Regex("""\s+"""), " ").trim()
    }

    /** The parenthesised tags of a ROM filename: "(USA) (Rev 1)" -> [USA, Rev 1]. */
    fun extractTagsFromFilename(filePath: String?): List<String> {
        if (filePath.isNullOrEmpty()) return emptyList()
        val name = filePath.substringAfterLast('/').substringAfterLast('\\')
        return Regex("""[\(\[]([^\)\]]+)[\)\]]""").findAll(name).map { it.groupValues[1] }.toList()
    }

    data class Match<T>(val value: T, val score: Double, val method: String)

    /** Best candidate by title similarity, or null when none reaches [minScore]. */
    fun <T> findBestMatch(searchTitle: String, candidates: List<T>, minScore: Double = 0.6,
                          titleOf: (T) -> String): Match<T>? {
        var best: T? = null
        var bestScore = 0.0
        for (c in candidates) {
            val score = similarity(searchTitle, titleOf(c))
            if (score > bestScore) { bestScore = score; best = c }
            if (score >= 1.0) break
        }
        return if (best != null && bestScore >= minScore) Match(best, bestScore, "title") else null
    }

    /**
     * Matches a Pegasus game to a candidate list, trying the ROM filename before
     * the library title.
     *
     * The filename is usually the better key — it carries the original release
     * name, where a Pegasus title may have been edited — so a strong filename
     * match wins outright; otherwise the title is tried, and only then the
     * filename again at a lower bar.
     */
    fun <T> multiMatchSearch(pegasusTitle: String, romFilePath: String?, candidates: List<T>,
                             minScore: Double = 0.60, titleOf: (T) -> String): Match<T>? {
        if (candidates.isEmpty()) return null

        val romTitle = extractTitleFromFilename(romFilePath)
        if (romTitle.isNotEmpty()) {
            val r = findBestMatch(romTitle, candidates, minScore, titleOf)
            if (r != null && r.score >= 0.85) return r.copy(method = "rom_filename")
        }

        findBestMatch(pegasusTitle, candidates, minScore, titleOf)
            ?.let { return it.copy(method = "pegasus_title") }

        if (romTitle.isNotEmpty()) {
            findBestMatch(romTitle, candidates, 0.50, titleOf)
                ?.let { return it.copy(method = "rom_filename_loose") }
        }
        return null
    }

}
