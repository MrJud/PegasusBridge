package com.pegasus.bridge.ra

import com.pegasus.bridge.core.RcConsoles
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The table a theme is handed, against the one it was handed before the
 * table was generated.
 *
 * console_map_master.json is what [RaConsoleMap.asJson] gave when its four
 * maps were written out by hand, taken from the commit before they stopped
 * being. A theme reads those maps by exact key, so an entry that went
 * missing, or changed without anyone meaning it to, would show as a console
 * with no label or a search in the wrong catalogue, and as nothing else.
 * Everything that differs is listed here, with the reason it was meant.
 */
class RaConsoleMapSnapshotTest {

    private fun map(json: JSONObject, name: String): Map<String, String> =
        json.getJSONObject(name).let { o -> o.keySet().associateWith { o.get(it).toString() } }

    @Test fun `the generated maps differ from master only where listed`() {
        val text = javaClass.classLoader!!.getResourceAsStream("console_map_master.json")
            ?.bufferedReader()?.readText() ?: fail("console_map_master.json is missing")
        val master = JSONObject(text)
        val now = RaConsoleMap.asJson()
        assertEquals(setOf("consoleId", "pegasusShortName", "shortLabel", "consoleName"), master.keySet())
        assertEquals(master.keySet(), now.keySet())

        // What was wrong and is put right: old value to new.
        val meant = mapOf(
            // 24 is Pokémon Mini, 76 PC Engine CD, and 14 the Neo Geo Pocket,
            // where Neo Geo sets are arcade games.
            "consoleId" to mapOf("wii" to ("24" to "19"), "3ds" to ("76" to "62"), "neogeo" to ("14" to "27")),
            "pegasusShortName" to mapOf("Neo Geo Pocket" to ("neogeo" to "ngp")),
            "shortLabel" to emptyMap(),
            "consoleName" to emptyMap())

        val wrong = mutableListOf<String>()
        fun report() {
            if (wrong.isNotEmpty()) fail("the console table changed where nobody meant it to:\n  " + wrong.joinToString("\n  "))
        }
        val added = HashMap<String, Map<String, String>>()
        for (name in master.keySet()) {
            val before = map(master, name)
            val after = map(now, name)
            assertTrue(before.size >= 30, "$name in the snapshot looks cut short: ${before.size}")
            val changed = HashMap<String, Pair<String, String>>()
            for ((key, value) in before) {
                when (val value2 = after[key]) {
                    null -> wrong += "$name lost $key"
                    value -> {}
                    else -> changed[key] = value to value2
                }
            }
            if (changed != meant.getValue(name)) wrong += "$name changed $changed, and ${meant[name]} was meant"
            added[name] = after - before.keys
        }
        // Said now: what follows asks the new table for the old keys, and would stop at the first one lost.
        report()

        // What is new. An id or a name for a collection comes from its row;
        // a collection with no row, or one RetroAchievements has no console
        // for, gets nothing.
        for ((key, id) in added.getValue("consoleId")) {
            val expected = when (val row = RcConsoles.row(key)) {
                is RcConsoles.Hashable -> row.console
                is RcConsoles.NoAlgorithm -> row.id
                else -> null
            }
            if (id != expected?.toString()) wrong += "consoleId gained $key = $id, and its row says $expected"
        }
        val ids = map(now, "consoleId")
        val names = map(now, "consoleName")
        // neogeo is left out: it is shown under a name of its own, which is not its console's.
        val shownAs = (map(master, "consoleName") - "neogeo").entries.associate { (key, name) -> ids.getValue(key) to name } +
                      mapOf("43" to "3DO Interactive Multiplayer", "57" to "Fairchild Channel F", "71" to "Arduboy",
                            "14" to "Neo Geo Pocket")
        for ((key, name) in added.getValue("consoleName")) {
            if (key !in ids) wrong += "consoleName gained $key, which has no id"
            else if (name != shownAs[ids[key]]) wrong += "consoleName gained $key = $name, and its console is ${shownAs[ids[key]]}"
        }
        // The names RetroAchievements shows are written by hand, so these are exact.
        assertEquals(mapOf("3DO Interactive Multiplayer" to "3do", "Fairchild Channel F" to "channelf",
                           "Arduboy" to "arduboy"), added["pegasusShortName"])
        assertEquals(mapOf("3DO Interactive Multiplayer" to "3DO", "Fairchild Channel F" to "CHF",
                           "Arduboy" to "ARD"), added["shortLabel"])

        report()

        // And the keys are the rows': every name of every row with an id, and no other.
        val rows = RcConsoles.ROWS.filter { it !is RcConsoles.NotOnRa }.flatMap { listOf(it.key) + it.spellings }.toSet()
        assertEquals(rows, ids.keys)
        assertTrue(names.keys.all { it in ids }, "a name for a collection with no id: ${names.keys - ids.keys}")
    }
}
