package com.pegasus.bridge.ra

import org.json.JSONObject

/**
 * Translates between Pegasus collection short names and RetroAchievements
 * consoles.
 *
 * Generated from the theme's RAConsoleMap.js so the two cannot drift: the theme
 * should be able to ask "which RA game is this?" without carrying a console
 * table of its own.
 */
object RaConsoleMap {

    /** Pegasus collection short name -> RA console id. */
    private val TO_CONSOLE_ID: Map<String, Int> = mapOf(
        "megadrive" to 1,
        "genesis" to 1,
        "n64" to 2,
        "snes" to 3,
        "gb" to 4,
        "gba" to 5,
        "gbc" to 6,
        "nes" to 7,
        "pcengine" to 8,
        "segacd" to 9,
        "sega32x" to 10,
        "mastersystem" to 11,
        "psx" to 12,
        "atari2600" to 25,
        "gc" to 16,
        "nds" to 18,
        "ps2" to 21,
        "wii" to 24,
        "psp" to 41,
        "3ds" to 76,
        "dreamcast" to 40,
        "saturn" to 39,
        "atari7800" to 51,
        "atarilynx" to 13,
        "neogeo" to 14,
        "wonderswan" to 53,
        "virtualboy" to 28,
        "sg1000" to 33,
        "gamegear" to 15,
        "arcade" to 27
    )

    /** RA console name -> Pegasus short name. */
    private val FROM_CONSOLE_NAME: Map<String, String> = mapOf(
        "Mega Drive" to "megadrive",
        "Mega Drive/Genesis" to "megadrive",
        "Genesis" to "megadrive",
        "Nintendo 64" to "n64",
        "SNES" to "snes",
        "SNES/Super Famicom" to "snes",
        "Super Nintendo" to "snes",
        "Game Boy" to "gb",
        "Game Boy Advance" to "gba",
        "Game Boy Color" to "gbc",
        "NES" to "nes",
        "NES/Famicom" to "nes",
        "PC Engine" to "pcengine",
        "PC Engine/TurboGrafx-16" to "pcengine",
        "Sega CD" to "segacd",
        "32X" to "sega32x",
        "Sega 32X" to "sega32x",
        "Master System" to "mastersystem",
        "PlayStation" to "psx",
        "Atari 2600" to "atari2600",
        "GameCube" to "gc",
        "Nintendo DS" to "nds",
        "PlayStation 2" to "ps2",
        "Wii" to "wii",
        "PSP" to "psp",
        "PlayStation Portable" to "psp",
        "Nintendo 3DS" to "3ds",
        "Dreamcast" to "dreamcast",
        "Saturn" to "saturn",
        "Sega Saturn" to "saturn",
        "Atari 7800" to "atari7800",
        "Atari Lynx" to "atarilynx",
        "Neo Geo" to "neogeo",
        "Neo Geo Pocket" to "neogeo",
        "WonderSwan" to "wonderswan",
        "Virtual Boy" to "virtualboy",
        "SG-1000" to "sg1000",
        "Game Gear" to "gamegear",
        "Arcade" to "arcade"
    )

    /** RA console name -> compact label for the UI. */
    private val SHORT_LABEL: Map<String, String> = mapOf(
        "Mega Drive" to "MD",
        "Mega Drive/Genesis" to "MD",
        "Genesis" to "MD",
        "Nintendo 64" to "N64",
        "SNES" to "SNES",
        "SNES/Super Famicom" to "SNES",
        "Super Nintendo" to "SNES",
        "Game Boy" to "GB",
        "Game Boy Advance" to "GBA",
        "Game Boy Color" to "GBC",
        "NES" to "NES",
        "NES/Famicom" to "NES",
        "PC Engine" to "PCE",
        "PC Engine/TurboGrafx-16" to "PCE",
        "Sega CD" to "SCD",
        "32X" to "32X",
        "Sega 32X" to "32X",
        "Master System" to "SMS",
        "PlayStation" to "PSX",
        "Atari 2600" to "2600",
        "GameCube" to "GC",
        "Nintendo DS" to "NDS",
        "PlayStation 2" to "PS2",
        "Wii" to "Wii",
        "PSP" to "PSP",
        "PlayStation Portable" to "PSP",
        "Nintendo 3DS" to "3DS",
        "Dreamcast" to "DC",
        "Saturn" to "SAT",
        "Sega Saturn" to "SAT",
        "Atari 7800" to "7800",
        "Atari Lynx" to "LYNX",
        "Neo Geo" to "NG",
        "WonderSwan" to "WS",
        "Virtual Boy" to "VB",
        "SG-1000" to "SG",
        "Game Gear" to "GG",
        "Arcade" to "ARC"
    )

    /** Pegasus short name -> RA console name. */
    private val TO_CONSOLE_NAME: Map<String, String> = mapOf(
        "megadrive" to "Mega Drive",
        "genesis" to "Mega Drive",
        "n64" to "Nintendo 64",
        "snes" to "SNES",
        "gb" to "Game Boy",
        "gba" to "Game Boy Advance",
        "gbc" to "Game Boy Color",
        "nes" to "NES",
        "pcengine" to "PC Engine",
        "segacd" to "Sega CD",
        "sega32x" to "32X",
        "mastersystem" to "Master System",
        "psx" to "PlayStation",
        "atari2600" to "Atari 2600",
        "gc" to "GameCube",
        "nds" to "Nintendo DS",
        "ps2" to "PlayStation 2",
        "wii" to "Wii",
        "psp" to "PSP",
        "3ds" to "Nintendo 3DS",
        "dreamcast" to "Dreamcast",
        "saturn" to "Saturn",
        "atari7800" to "Atari 7800",
        "atarilynx" to "Atari Lynx",
        "neogeo" to "Neo Geo",
        "wonderswan" to "WonderSwan",
        "virtualboy" to "Virtual Boy",
        "sg1000" to "SG-1000",
        "gamegear" to "Game Gear",
        "arcade" to "Arcade"
    )

    /** 0 when the platform has no RetroAchievements equivalent. */
    fun consoleId(pegasusShortName: String?): Int =
        TO_CONSOLE_ID[pegasusShortName?.lowercase().orEmpty()] ?: 0

    fun pegasusShortName(raConsoleName: String?): String {
        val n = raConsoleName.orEmpty()
        return FROM_CONSOLE_NAME[n] ?: n.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun shortLabel(raConsoleName: String?): String {
        val n = raConsoleName.orEmpty()
        return SHORT_LABEL[n] ?: n
    }

    fun consoleName(pegasusShortName: String?): String {
        val n = pegasusShortName.orEmpty()
        return TO_CONSOLE_NAME[n.lowercase()] ?: n
    }

    /** Every RA console id this map knows, for callers that scan broadly. */
    fun knownConsoleIds(): Set<Int> = TO_CONSOLE_ID.values.toSet()

    /**
     * The whole table, for clients that cannot call back per lookup — a theme
     * fetches this once and labels consoles from it, instead of carrying its own
     * copy that then drifts out of step with this one.
     */
    fun asJson(): JSONObject = JSONObject()
        .put("consoleId",      JSONObject(TO_CONSOLE_ID as Map<*, *>))
        .put("pegasusShortName", JSONObject(FROM_CONSOLE_NAME as Map<*, *>))
        .put("shortLabel",     JSONObject(SHORT_LABEL as Map<*, *>))
        .put("consoleName",    JSONObject(TO_CONSOLE_NAME as Map<*, *>))
}
