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
}
