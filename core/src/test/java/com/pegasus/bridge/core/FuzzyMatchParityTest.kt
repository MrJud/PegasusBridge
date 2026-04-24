package com.pegasus.bridge.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class FuzzyMatchParityTest {

    @Test
    fun `all fixtures produce expected cache keys`() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("fuzzy_parity.json")!!
            .bufferedReader().readText()
        val fixtures = JSONArray(json)
        val failures = mutableListOf<String>()
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val title    = f.getString("title")
            val platform = f.getString("platform")
            val expected = f.getString("expectedKey")
            val actual   = FuzzyMatch.makeCacheKey(title, platform)
            if (actual != expected) {
                failures += "[$i] \"$title\" / \"$platform\"\n  expected: $expected\n  actual:   $actual"
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("FuzzyMatch parity failures:\n${failures.joinToString("\n")}")
        }
    }

    @Test
    fun `normalize strips articles and brackets`() {
        assertEquals("supermarioworld", FuzzyMatch.normalize("Super Mario World"))
        assertEquals("legendofzelda",   FuzzyMatch.normalize("The Legend of Zelda"))
        assertEquals("finalfantasyvii", FuzzyMatch.normalize("Final Fantasy VII (USA)"))
    }

    @Test
    fun `normalizePlatform resolves aliases`() {
        assertEquals("snes",    FuzzyMatch.normalizePlatform("Super Nintendo"))
        assertEquals("genesis", FuzzyMatch.normalizePlatform("Mega Drive"))
        assertEquals("psx",     FuzzyMatch.normalizePlatform("PlayStation"))
        assertEquals("psx",     FuzzyMatch.normalizePlatform("PS1"))
        assertEquals("arcade",  FuzzyMatch.normalizePlatform("MAME"))
    }
}
