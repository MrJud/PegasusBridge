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
}
