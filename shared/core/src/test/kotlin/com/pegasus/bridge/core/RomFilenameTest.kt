package com.pegasus.bridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity with the theme's RAFuzzyMatch.js, which these replace.
 *
 * The cases come from the real library, because the whole point of moving this
 * to the Bridge is that a theme no longer has to know how ROM files are named.
 */
class RomFilenameTest {

    @Test fun `strips path, extension and region tags`() {
        assertEquals("Super Mario World",
            FuzzyMatch.extractTitleFromFilename("Super Mario World (USA) (Rev 1).sfc"))
        assertEquals("Castlevania III - Dracula's Curse",
            FuzzyMatch.extractTitleFromFilename("/roms/nes/Castlevania III - Dracula's Curse (USA).nes"))
        assertEquals("Metroid",
            FuzzyMatch.extractTitleFromFilename("/roms/nes/Metroid (Europe) (Virtual Console).nes"))
    }

    // No-Intro moves the article to the end; the matcher needs it back off.
    @Test fun `strips a trailing article`() {
        assertEquals("Legend of Zelda",
            FuzzyMatch.extractTitleFromFilename("/roms/Legend of Zelda, The (USA) (En,Fr).z64"))
        assertEquals("Legend of Zelda",
            FuzzyMatch.extractTitleFromFilename("Legend of Zelda, The (USA) (Rev 1).nes"))
    }

    @Test fun `handles windows separators and empty input`() {
        assertEquals("Contra", FuzzyMatch.extractTitleFromFilename("C:\\roms\\nes\\Contra (USA).nes"))
        assertEquals("", FuzzyMatch.extractTitleFromFilename(""))
        assertEquals("", FuzzyMatch.extractTitleFromFilename(null))
    }

    @Test fun `extracts the tags`() {
        assertEquals(listOf("Japan, USA", "Rev A"),
            FuzzyMatch.extractTagsFromFilename("Mike Tyson's Punch-Out!! (Japan, USA) (Rev A).nes"))
        assertEquals(emptyList(), FuzzyMatch.extractTagsFromFilename("Plain.nes"))
    }

    // ── matching ────────────────────────────────────────────────────────────

    private data class Cand(val title: String, val id: Int)
    private val catalogue = listOf(
        Cand("Castlevania III: Dracula's Curse", 2221),
        Cand("Castlevania II: Simon's Quest", 1449),
        Cand("Super Mario Bros.", 1446),
        Cand("Contra", 1447)
    )

    @Test fun `finds the right candidate and reports how`() {
        val m = FuzzyMatch.multiMatchSearch(
            "Castlevania III", "/roms/nes/Castlevania III - Dracula's Curse (USA).nes",
            catalogue) { it.title }
        assertNotNull(m)
        assertEquals(2221, m.value.id)
        assertEquals("rom_filename", m.method, "a strong filename match should win outright")
    }

    // The filename is the better key, so a Pegasus title edited by the user must
    // not drag the match away from it.
    @Test fun `a renamed library entry still matches through the filename`() {
        val m = FuzzyMatch.multiMatchSearch(
            "CV3 (my favourite)", "/roms/nes/Castlevania III - Dracula's Curse (USA).nes",
            catalogue) { it.title }
        assertNotNull(m)
        assertEquals(2221, m.value.id)
    }

    @Test fun `falls back to the library title when there is no file`() {
        val m = FuzzyMatch.multiMatchSearch("Super Mario Bros.", null, catalogue) { it.title }
        assertNotNull(m)
        assertEquals(1446, m.value.id)
        assertEquals("pegasus_title", m.method)
    }

    @Test fun `returns nothing rather than a bad guess`() {
        assertNull(FuzzyMatch.multiMatchSearch("Totally Unrelated Game", null, catalogue) { it.title })
        assertNull(FuzzyMatch.multiMatchSearch("Contra", null, emptyList<Cand>()) { it.title })
    }

    @Test fun `does not confuse two entries in the same series`() {
        val m = FuzzyMatch.multiMatchSearch("Castlevania II - Simon's Quest", null, catalogue) { it.title }
        assertNotNull(m)
        assertEquals(1449, m.value.id, "III must not win over II")
    }

    @Test fun `score is reported and within range`() {
        val m = FuzzyMatch.multiMatchSearch("Contra", null, catalogue) { it.title }
        assertNotNull(m)
        assertTrue(m.score in 0.0..1.0)
        assertEquals(1.0, m.score, "an exact title is a perfect score")
    }
}
