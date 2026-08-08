package com.pegasus.bridge.scrapers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ScreenScraper does not answer a bad login with JSON and an error field — it answers
 * with a line of plain text, in French. So the parser has to treat "this is not JSON"
 * as a *credential* verdict rather than as a broken response, and the message it
 * produces is the only thing that will tell anyone which of the two password columns
 * they copied into the wrong box.
 */
class ScreenScraperParsingTest {

    @Test fun `a good answer yields the quota`() {
        val body = """
            { "header": { "success": "true" },
              "response": { "ssuser": {
                  "id": "MrJud",
                  "maxthreads": "1",
                  "requeststoday": "42",
                  "maxrequestsperday": "20000",
                  "maxrequestspermin": "400"
              } } }
        """.trimIndent()
        val q = ScreenScraperClient.parseUserInfo(body).getOrThrow()
        assertEquals("MrJud", q.user)
        assertEquals(42, q.requestsToday)
        assertEquals(20000, q.maxRequestsPerDay)
        assertEquals(1, q.maxThreads)
    }

    @Test fun `missing counters fall back rather than throwing`() {
        // The API omits fields for some account levels; an absent quota is not a
        // failure, and treating it as one would report working credentials as broken.
        val body = """{ "response": { "ssuser": { "id": "MrJud" } } }"""
        val q = ScreenScraperClient.parseUserInfo(body).getOrThrow()
        assertEquals("MrJud", q.user)
        assertEquals(0, q.requestsToday)
        assertEquals(1, q.maxThreads, "one thread is the floor, not zero")
    }

    @Test fun `a plain-text login refusal is reported as a credential problem`() {
        val r = ScreenScraperClient.parseUserInfo("Erreur de login : Vérifier vos identifiants !")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()?.message.orEmpty()
        // The one sentence that matters: which pair to look at. The developer password
        // and the member password come from two different places on the site, and
        // swapping them is the likeliest mistake by a wide margin.
        assertTrue(msg.contains("developer pair"), msg)
    }

    @Test fun `a softname refusal names the softname`() {
        val r = ScreenScraperClient.parseUserInfo("Erreur : softname non reconnu")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("softname"),
                   r.exceptionOrNull()?.message.orEmpty())
    }

    @Test fun `a closed API is not blamed on the user`() {
        val r = ScreenScraperClient.parseUserInfo("API totalement fermé")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("closed the API"), msg)
        assertTrue(msg.contains("not a credential problem"), msg)
    }

    @Test fun `valid JSON without an ssuser block is still a refusal`() {
        // A shape that parses but says nothing about the account must not read as
        // success with an empty username — that would be a green tick for a login that
        // does not work.
        val r = ScreenScraperClient.parseUserInfo("""{ "header": { "success": "false" } }""")
        assertTrue(r.isFailure)
    }

    @Test fun `an empty body says so instead of pretending`() {
        val r = ScreenScraperClient.parseUserInfo("")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("returned nothing"))
    }

    // ── The distinction the whole source hangs on ────────────
    //
    // A ROM the database does not have is an *answer*: it is true, it stays true, and a
    // rerun need not spend a request rediscovering it. Everything else on this list is
    // the API declining to answer, and remembering one of those as "not found" is
    // exactly what happened to the RetroAchievements scan — 913 files, 68 identified,
    // and an incremental rescan that never asked again.

    @Test fun `a missing ROM is an answer and may be remembered`() {
        val r = ScreenScraperClient.refusalFrom("Erreur : Rom/Iso/Dossier non trouvée !")
        assertEquals(ScreenScraperClient.Refusal.NOT_FOUND, r.refusal)
        assertTrue(r.refusal.isAnswer)
        assertTrue(!r.refusal.isFatalToARun, "one unknown game must not stop a library")
    }

    @Test fun `a thread refusal is not an answer`() {
        val r = ScreenScraperClient.refusalFrom(
            "Le quota de requetes threads est depasse : trop de threads simultanés")
        assertEquals(ScreenScraperClient.Refusal.THREADS, r.refusal)
        assertTrue(!r.refusal.isAnswer, "it must be asked again, not cached as a miss")
        // Recoverable by waiting, so it slows a run down rather than ending it.
        assertTrue(!r.refusal.isFatalToARun)
    }

    @Test fun `the daily quota stops the run`() {
        val r = ScreenScraperClient.refusalFrom("Quota de scrape journalier depassé !")
        assertEquals(ScreenScraperClient.Refusal.DAILY_QUOTA, r.refusal)
        assertTrue(!r.refusal.isAnswer)
        assertTrue(r.refusal.isFatalToARun, "nothing else will succeed today")
    }

    @Test fun `too many unrecognised ROMs is read before the other quota messages`() {
        // All three quota refusals contain the word "quota", and this is the one that
        // has to win: it is triggered by scanning a library the database does not know
        // — which is precisely what the placeholder-filled test libraries are — and
        // carrying on gets the account throttled rather than eventually succeeding.
        val r = ScreenScraperClient.refusalFrom(
            "Le quota de roms non reconnues est atteint pour aujourd'hui")
        assertEquals(ScreenScraperClient.Refusal.UNKNOWN_ROMS, r.refusal)
        assertTrue(r.refusal.isFatalToARun)
    }

    @Test fun `a login refusal is fatal, because every later request has the same fate`() {
        val r = ScreenScraperClient.refusalFrom("Erreur de login : Vérifier vos identifiants !")
        assertEquals(ScreenScraperClient.Refusal.LOGIN, r.refusal)
        assertTrue(r.refusal.isFatalToARun)
    }

    // ── jeuInfos ─────────────────────────────────────────────

    private val duckTales = """
        { "response": { "jeu": {
            "id": "1234",
            "noms": [ { "region": "wor", "text": "DuckTales" },
                      { "region": "jp",  "text": "Wanpaku Duck Yume Bouken" } ],
            "editeur": { "text": "Capcom" },
            "developpeur": { "text": "Capcom" },
            "joueurs": { "text": "1" },
            "note": { "text": "18" },
            "dates": [ { "region": "us", "text": "1989-09-01" },
                       { "region": "jp", "text": "1989-01-27" } ],
            "genres": [ { "nomcourt": "Plateforme",
                          "noms": [ { "langue": "en", "text": "Platform" },
                                    { "langue": "fr", "text": "Plateforme" } ] } ],
            "synopsis": [ { "langue": "fr", "text": "Un jeu de plateforme." },
                          { "langue": "en", "text": "A platform game." } ],
            "medias": [
                { "type": "box-2D", "region": "us",  "format": "png", "url": "https://x/us.png" },
                { "type": "box-2D", "region": "jp",  "format": "png", "url": "https://x/jp.png" },
                { "type": "wheel",  "region": "wor", "format": "png", "url": "https://x/w.png" },
                { "type": "ss",     "region": "wor", "format": "png", "url": "https://x/ss.png" }
            ]
        } } }
    """.trimIndent()

    @Test fun `a game is identified with no title matching involved`() {
        val g = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales (USA).nes", "en").getOrThrow()
        assertEquals("1234", g.id)
        assertEquals("Capcom", g.developer)
        assertEquals("1", g.players)
        assertEquals("1989", g.releaseYear, "the year, not the whole date")
        assertEquals(listOf("Platform"), g.genres)
        assertEquals("A platform game.", g.description)
    }

    @Test fun `the ROM's own region decides which variant comes back`() {
        // The file says which release it is, and a European box is not an American one.
        val us = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales (USA).nes", "en").getOrThrow()
        assertEquals("1989-09-01".take(4), us.releaseYear)
        val cover = ScreenScraperClient.pickMedia(us.media, "cover", "DuckTales (USA).nes")
        assertEquals("https://x/us.png", cover?.url)

        val jp = ScreenScraperClient.pickMedia(us.media, "cover", "DuckTales (Japan).nes")
        assertEquals("https://x/jp.png", jp?.url, "the Japanese release has Japanese art")
    }

    @Test fun `a file with no region tag still gets a cover`() {
        val g = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales.nes", "en").getOrThrow()
        // Neither candidate is `wor`, so the fixed order decides — and it must pick one
        // rather than refusing, because most files say nothing about their region.
        assertEquals("https://x/us.png",
                     ScreenScraperClient.pickMedia(g.media, "cover", "DuckTales.nes")?.url)
    }

    @Test fun `the language chooses the description and the genre names`() {
        val g = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales (USA).nes", "fr").getOrThrow()
        assertEquals("Un jeu de plateforme.", g.description)
        assertEquals(listOf("Plateforme"), g.genres)
    }

    @Test fun `an unsupported language falls back to English rather than to nothing`() {
        val g = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales (USA).nes", "it").getOrThrow()
        assertEquals("A platform game.", g.description)
    }

    @Test fun `a kind the game has no art for is absent, not an error`() {
        val g = ScreenScraperClient.parseJeuInfos(duckTales, "DuckTales (USA).nes", "en").getOrThrow()
        assertEquals(null, ScreenScraperClient.pickMedia(g.media, "video", "DuckTales (USA).nes"))
        // The screenshot slot is served by `ss`, which this game does have.
        assertEquals("https://x/ss.png",
                     ScreenScraperClient.pickMedia(g.media, "screenshot", "DuckTales (USA).nes")?.url)
    }

    @Test fun `valid JSON with no jeu block is a refusal, not an empty game`() {
        val r = ScreenScraperClient.parseJeuInfos("""{ "header": { "success": "false" } }""", "x", "en")
        assertTrue(r.isFailure)
    }

    @Test fun `the score is converted off ScreenScraper's twenty-point scale`() {
        // 18/20 is 90%. Handed over untouched the theme reads it as 18/100, because its
        // rule is "over ten means out of a hundred" — so one of the best games on the
        // system would display as 18%.
        assertEquals("90/100", ScreenScraperClient.scoreOutOf20("18"))
        assertEquals("50/100", ScreenScraperClient.scoreOutOf20("10"))
        assertEquals("", ScreenScraperClient.scoreOutOf20(""), "no score is not a zero score")
        assertEquals("", ScreenScraperClient.scoreOutOf20("-"))
    }
}
