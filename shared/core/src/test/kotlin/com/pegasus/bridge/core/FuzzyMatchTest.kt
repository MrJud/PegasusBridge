package com.pegasus.bridge.core

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FuzzyMatch must stay byte-identical to the theme's RAFuzzyMatch.js: both sides
 * compute the cache keys that join a scanned ROM to its RetroAchievements entry,
 * so any drift silently breaks lookups. The fixture file is the shared contract.
 */
class FuzzyMatchTest {

    @Test fun `all fixtures produce the expected cache keys`() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("fuzzy_parity.json")!!
            .bufferedReader().readText()
        val fixtures = JSONArray(json)
        val failures = mutableListOf<String>()
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val actual = FuzzyMatch.makeCacheKey(f.getString("title"), f.getString("platform"))
            val expected = f.getString("expectedKey")
            if (actual != expected) {
                failures += "[$i] \"${f.getString("title")}\" / \"${f.getString("platform")}\"" +
                            "\n  expected: $expected\n  actual:   $actual"
            }
        }
        if (failures.isNotEmpty())
            throw AssertionError("FuzzyMatch parity failures:\n${failures.joinToString("\n")}")
    }

    @Test fun `normalize strips articles and bracketed tags`() {
        assertEquals("supermarioworld", FuzzyMatch.normalize("Super Mario World"))
        assertEquals("legendofzelda",   FuzzyMatch.normalize("The Legend of Zelda"))
        assertEquals("finalfantasyvii", FuzzyMatch.normalize("Final Fantasy VII (USA)"))
    }

    @Test fun `normalizePlatform resolves aliases`() {
        assertEquals("snes",    FuzzyMatch.normalizePlatform("Super Nintendo"))
        assertEquals("genesis", FuzzyMatch.normalizePlatform("Mega Drive"))
        assertEquals("psx",     FuzzyMatch.normalizePlatform("PlayStation"))
        assertEquals("psx",     FuzzyMatch.normalizePlatform("PS1"))
        assertEquals("arcade",  FuzzyMatch.normalizePlatform("MAME"))
    }

    // The real library on disk: these are the exact filenames that scan correctly
    // against the RA catalogue, so the keys they produce must stay stable.
    @Test fun `real ROM filenames map to stable keys`() {
        assertEquals("castlevaniaiiidraculascurse|nes",
            FuzzyMatch.makeCacheKey("Castlevania III - Dracula's Curse (USA)", "nes"))
        assertEquals("miketysonspunchout|nes",
            FuzzyMatch.makeCacheKey("Mike Tyson's Punch-Out!! (Japan, USA) (Rev A)", "nes"))
        assertEquals("legendofzeldathe|nes",
            FuzzyMatch.makeCacheKey("Legend of Zelda, The (USA) (Rev 1)", "nes"))
    }

    @Test fun `similarity is 1 for identical titles and 0 when either is empty`() {
        assertEquals(1.0, FuzzyMatch.similarity("Contra", "Contra"))
        assertEquals(0.0, FuzzyMatch.similarity("", "Contra"))
        assertEquals(0.0, FuzzyMatch.similarity("Contra", ""))
    }

    @Test fun `similarity ranks the right candidate highest`() {
        val query = "Castlevania III - Dracula's Curse"
        val right = FuzzyMatch.similarity(query, "Castlevania III: Dracula's Curse")
        val wrong = FuzzyMatch.similarity(query, "Castlevania II: Simon's Quest")
        assertTrue(right > wrong, "expected $right > $wrong")
        assertTrue(right > 0.8, "near-identical titles should score high, got $right")
    }

    @Test fun `levenshtein handles empty and identical inputs`() {
        assertEquals(0, FuzzyMatch.levenshtein("abc", "abc"))
        assertEquals(3, FuzzyMatch.levenshtein("", "abc"))
        assertEquals(3, FuzzyMatch.levenshtein("abc", ""))
        assertEquals(1, FuzzyMatch.levenshtein("abc", "abd"))
    }
}
