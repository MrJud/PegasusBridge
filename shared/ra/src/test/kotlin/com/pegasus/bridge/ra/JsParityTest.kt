package com.pegasus.bridge.ra

import com.pegasus.bridge.core.FuzzyMatch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares this Kotlin against the theme's JavaScript, output for output.
 *
 * The fixture is produced by running RAFuzzyMatch.js and RAConsoleMap.js in a Qt
 * QML engine — the same engine the theme runs in — and recording what they
 * return. Moving this logic to the Bridge is only safe if the answers are
 * identical, and "looks equivalent" is not the same as "is equivalent".
 *
 * The theme no longer has that JavaScript — this Kotlin is what replaced it —
 * so the fixture is now the record of the behaviour that shipped, and this test
 * is what stops the replacement drifting away from it. See the theme's
 * scripts/capture_js_parity.qml for how to reproduce the fixture.
 */
class JsParityTest {

    private fun fixture(): List<Triple<String, String, String>> {
        val f = javaClass.classLoader!!.getResourceAsStream("js_parity.txt")
            ?: error("js_parity.txt fixture missing")
        return f.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                // Tab-separated: a cache key contains '|', so that cannot separate.
                val parts = line.split("\t")
                require(parts.size == 3) { "malformed fixture line: $line" }
                Triple(parts[0], parts[1], parts[2])
            }
    }

    @Test fun `matches the javascript output for every recorded case`() {
        val cases = fixture()
        assertTrue(cases.size >= 30, "fixture looks truncated: ${cases.size} cases")

        val failures = mutableListOf<String>()
        for ((kind, input, expected) in cases) {
            val actual = when (kind) {
                "TITLE" -> FuzzyMatch.extractTitleFromFilename(input)
                "ID"    -> RaConsoleMap.consoleId(input).toString()
                "NAME"  -> RaConsoleMap.consoleName(input)
                "KEY"   -> FuzzyMatch.makeCacheKey(input, "nes")
                else    -> continue
            }
            if (actual != expected)
                failures += "$kind(\"$input\")\n     JS: $expected\n     KT: $actual"
        }
        if (failures.isNotEmpty())
            throw AssertionError("Kotlin diverges from the JavaScript it replaces:\n  " +
                                 failures.joinToString("\n  "))
    }
}
