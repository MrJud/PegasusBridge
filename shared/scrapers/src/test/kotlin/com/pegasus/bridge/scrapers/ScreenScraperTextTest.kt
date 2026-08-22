package com.pegasus.bridge.scrapers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two defects in ScreenScraper's live text, both found by exporting a real
 * library rather than by reading the API docs.
 */
class ScreenScraperTextTest {

    // Castlevania III's Italian synopsis, verbatim from the live API.
    @Test fun `html entities are decoded rather than shown literally`() {
        val raw = "la frusta ereditaria della sua famiglia, la &quot;Vampire Slayer&quot;"
        val out = ScreenScraperClient.cleanText(raw)
        assertEquals("la frusta ereditaria della sua famiglia, la \"Vampire Slayer\"", out)
        assertFalse(out.contains("&quot;"))
    }

    @Test fun `the other entities a scraper text carries are decoded too`() {
        assertEquals("Tom & Jerry", ScreenScraperClient.cleanText("Tom &amp; Jerry"))
        assertEquals("Marchio™", ScreenScraperClient.cleanText("Marchio&#8482;"))
        assertEquals("<tag>", ScreenScraperClient.cleanText("&lt;tag&gt;"))
        assertEquals("l'isola", ScreenScraperClient.cleanText("l&#39;isola"))
        assertEquals("é", ScreenScraperClient.cleanText("&#xe9;"))
    }

    // `&amp;` has to go last, or `&amp;quot;` decodes twice into a bare quote.
    @Test fun `a double-escaped entity is decoded once and no further`() {
        assertEquals("&quot;", ScreenScraperClient.cleanText("&amp;quot;"))
    }

    // Contra's synopsis contains the bytes `terroristi.\r\nnnCome Bill` — a
    // paragraph break that became a literal `nn` inside ScreenScraper's own data.
    @Test fun `their mangled paragraph break becomes a real one`() {
        val raw = "neutralizzare i terroristi.\r\nnnCome Bill (giocatore 1)"
        val out = ScreenScraperClient.cleanText(raw)
        assertFalse(out.contains("nnCome"), out)
        assertTrue(out.contains("terroristi.\n\nCome Bill"), out)
    }

    // The rule has to be narrow enough not to damage real text. No Italian or
    // English word begins `nn`, and the correction demands a capital after it.
    @Test fun `text that merely contains nn is left alone`() {
        for (s in listOf("anno\nnnn", "Gianni\nnon si arrende", "hanno\nnuovi nemici")) {
            assertEquals(s, ScreenScraperClient.cleanText(s), "changed: $s")
        }
    }

    @Test fun `text with nothing to fix comes back identical`() {
        val s = "Un testo del tutto normale, con accenti: perché, così, più."
        assertEquals(s, ScreenScraperClient.cleanText(s))
        assertEquals("", ScreenScraperClient.cleanText(""))
    }

    // The fix has to reach the parser, not just exist beside it.
    @Test fun `a parsed game carries cleaned text`() {
        val body = """
        {"response":{"jeu":{
          "id":"1","noms":[{"region":"wor","text":"Tom &amp; Jerry"}],
          "editeur":{"text":"Ocean &amp; Co"},
          "synopsis":[{"langue":"it","text":"Prima parte.\r\nnnSeconda parte con &quot;virgolette&quot;."}],
          "genres":[{"noms":[{"langue":"it","text":"Azione &amp; Avventura"}],"nomcourt":"action"}],
          "medias":[]
        }}}
        """.trimIndent()
        val g = ScreenScraperClient.parseJeuInfos(body, "Tom.nes", "it").getOrThrow()
        assertEquals("Tom & Jerry", g.title)
        assertEquals("Ocean & Co", g.publisher)
        assertEquals(listOf("Azione & Avventura"), g.genres)
        assertTrue(g.description.contains("\"virgolette\""), g.description)
        assertTrue(g.description.contains("Prima parte.\n\nSeconda parte"), g.description)
    }
}
