package com.pegasus.bridge.ra

import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.RcConsoles
import org.json.JSONObject

/**
 * Translates between Pegasus collection short names and RetroAchievements
 * consoles, so that a theme can ask "which RA game is this?" without carrying
 * a console table of its own.
 *
 * The ids are not written here. They come from [RcConsoles], the table of
 * what each collection is to rcheevos, which a test holds to the vendored
 * rc_consoles.h.
 * This began as a copy of the theme's RAConsoleMap.js, written by hand, and
 * three of its ids were another console's: a Wii collection was matched
 * against the Pokémon Mini catalogue. One table for both uses cannot disagree
 * with itself.
 *
 * What is written here is what no header has: the names RetroAchievements
 * shows a console under, and the labels a theme shortens them to.
 */
object RaConsoleMap {

    /**
     * A console as it is shown: the [names] RetroAchievements writes it under,
     * the first being the one given back for a collection; the [label] a
     * theme shows where the name is too long; and [shortName], the collection
     * one of those names is sent back to.
     */
    private class Names(val console: Int, val shortName: String, val label: String?, vararg val names: String)

    /**
     * The entries the theme's script had, as it had them, short names
     * included: `megadrive` and `atarilynx`, which is how those collections
     * are spelt on disk, and not the keys of the rows. Then three names seen
     * in answers RetroAchievements gave since. A console that is not here
     * has an id and no name, and its collection is shown under its own.
     */
    private val NAMES: List<Names> = listOf(
        Names(1, "megadrive", "MD", "Mega Drive", "Mega Drive/Genesis", "Genesis"),
        Names(2, "n64", "N64", "Nintendo 64"),
        Names(3, "snes", "SNES", "SNES", "SNES/Super Famicom", "Super Nintendo"),
        Names(4, "gb", "GB", "Game Boy"),
        Names(5, "gba", "GBA", "Game Boy Advance"),
        Names(6, "gbc", "GBC", "Game Boy Color"),
        Names(7, "nes", "NES", "NES", "NES/Famicom"),
        Names(8, "pcengine", "PCE", "PC Engine", "PC Engine/TurboGrafx-16"),
        Names(9, "segacd", "SCD", "Sega CD"),
        Names(10, "sega32x", "32X", "32X", "Sega 32X"),
        Names(11, "mastersystem", "SMS", "Master System"),
        Names(12, "psx", "PSX", "PlayStation"),
        Names(13, "atarilynx", "LYNX", "Atari Lynx"),
        // The script sent this name to `neogeo`, and had no label for it.
        Names(14, "ngp", null, "Neo Geo Pocket"),
        Names(15, "gamegear", "GG", "Game Gear"),
        Names(16, "gc", "GC", "GameCube"),
        Names(18, "nds", "NDS", "Nintendo DS"),
        Names(19, "wii", "Wii", "Wii"),
        Names(21, "ps2", "PS2", "PlayStation 2"),
        Names(25, "atari2600", "2600", "Atari 2600"),
        Names(27, "arcade", "ARC", "Arcade"),
        Names(28, "virtualboy", "VB", "Virtual Boy"),
        Names(33, "sg1000", "SG", "SG-1000"),
        Names(39, "saturn", "SAT", "Saturn", "Sega Saturn"),
        Names(40, "dreamcast", "DC", "Dreamcast"),
        Names(41, "psp", "PSP", "PSP", "PlayStation Portable"),
        Names(43, "3do", "3DO", "3DO Interactive Multiplayer"),
        Names(51, "atari7800", "7800", "Atari 7800"),
        Names(53, "wonderswan", "WS", "WonderSwan"),
        Names(57, "channelf", "CHF", "Fairchild Channel F"),
        Names(62, "3ds", "3DS", "Nintendo 3DS"),
        Names(71, "arduboy", "ARD", "Arduboy")
    )

    /**
     * Rows shown under a name that is not their console's. Neo Geo sets are
     * hashed and listed as arcade games, and a collection of them is still
     * called Neo Geo.
     */
    private val ROW_NAMES: Map<String, Names> = mapOf(
        "neogeo" to Names(27, "neogeo", "NG", "Neo Geo")
    )

    private val NAMES_BY_CONSOLE: Map<Int, Names> = NAMES.associateBy { it.console }

    /** Every name a row goes by, the key first. */
    private fun namesOf(row: RcConsoles.Row): List<String> = listOf(row.key) + row.spellings

    /** A row that RetroAchievements has no console for has no id, and so no entry anywhere. */
    private fun idOf(row: RcConsoles.Row): Int? = when (row) {
        is RcConsoles.Hashable -> row.console
        is RcConsoles.NoAlgorithm -> row.id
        is RcConsoles.NotOnRa -> null
    }

    /**
     * Pegasus collection short name -> RA console id: every row that has one,
     * under its key and under each of its spellings. A console rcheevos cannot
     * hash for is here too. Its games are in the catalogue all the same, and
     * can be matched by title.
     */
    private val TO_CONSOLE_ID: Map<String, Int> = buildMap {
        for (row in RcConsoles.ROWS) {
            val id = idOf(row) ?: continue
            for (name in namesOf(row)) put(name, id)
        }
    }

    /** RA console name -> Pegasus short name. */
    private val FROM_CONSOLE_NAME: Map<String, String> = buildMap {
        for (entry in NAMES + ROW_NAMES.values) for (name in entry.names) put(name, entry.shortName)
    }

    /** RA console name -> compact label for the UI. */
    private val SHORT_LABEL: Map<String, String> = buildMap {
        for (entry in NAMES + ROW_NAMES.values) {
            val label = entry.label ?: continue
            for (name in entry.names) put(name, label)
        }
    }

    /** Pegasus short name -> RA console name, for the same names as the ids and wherever a name is known. */
    private val TO_CONSOLE_NAME: Map<String, String> = buildMap {
        for (row in RcConsoles.ROWS) {
            val id = idOf(row) ?: continue
            val shown = (ROW_NAMES[row.key] ?: NAMES_BY_CONSOLE[id])?.names?.firstOrNull() ?: continue
            for (name in namesOf(row)) put(name, shown)
        }
    }

    /**
     * 0 when the platform has no RetroAchievements equivalent.
     *
     * The name is tried as it is written, in lower case, and then as
     * [FuzzyMatch.normalizePlatform] folds it: a theme sends a collection's
     * short name, and the index of matched games holds the folded one.
     */
    fun consoleId(pegasusShortName: String?): Int {
        val name = pegasusShortName?.lowercase().orEmpty()
        return TO_CONSOLE_ID[name] ?: TO_CONSOLE_ID[FuzzyMatch.normalizePlatform(name)] ?: 0
    }

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
