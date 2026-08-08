package com.pegasus.bridge.scrapers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The short-name → `systemeid` table, which is the one part of this source that fails
 * *silently* when it is wrong.
 *
 * A bad id produces a well-formed request that finds nothing, and "found nothing" is
 * also what a ROM the database genuinely lacks produces — so a wrong entry does not
 * look like a bug, it looks like poor coverage, and it can sit there indefinitely. That
 * is why the ids are read out of the API's own `systemesListe.php` rather than written
 * from memory, and why this checks the reading rather than the values.
 */
class ScreenScraperSystemMapTest {

    /** The shape `systemesListe.php` answers with, trimmed to what is read. */
    private val listPayload = """
        { "response": { "systemes": [
            { "id": "1",  "noms": { "nom_eu": "Megadrive", "nom_us": "Genesis",
                                    "noms_commun": "Mega Drive,Genesis,megadrive",
                                    "nom_recalbox": "megadrive" },
                          "extensions": "bin,gen,md,smd,zip" },
            { "id": "3",  "noms": { "nom_eu": "NES", "noms_commun": "Nintendo Entertainment System,nes",
                                    "nom_recalbox": "nes" },
                          "extensions": "nes,fds,zip" },
            { "id": "6",  "noms": { "nom_eu": "Capcom Play System",
                                    "noms_commun": "arcade,fba,mame,mess,cps1" },
                          "extensions": "zip" },
            { "id": "56", "noms": { "nom_eu": "Sega Naomi",
                                    "noms_commun": "arcade,fba,mame,mess,Naomi" },
                          "extensions": "zip" },
            { "id": "142","noms": { "nom_eu": "SNK Neo Geo", "noms_commun": "neogeo,Neo-Geo" },
                          "extensions": "zip" },
            { "id": "300","noms": { "nom_eu": "Megadrive Japan", "noms_commun": "megadrive" },
                          "extensions": "bin" }
        ] } }
    """.trimIndent()

    private fun index() =
        ScreenScraperSystemMap.index(ScreenScraperClient.parseSystems(listPayload).getOrThrow())

    @Test fun `every alias a system publishes becomes a key`() {
        val idx = index()
        // The Pegasus short name turns up in a different field per system — `nom_eu`
        // here, `nom_recalbox` there — so indexing only one of them would work for half
        // the library and look like the other half is unsupported.
        //
        // The keys are in `FuzzyMatch.normalizePlatform` form, which is the point: that
        // function already folds `Megadrive`, `Mega Drive` and `Genesis` onto one word,
        // so the table needs no aliases of its own for any of them.
        assertEquals(1, idx["genesis"])
        assertEquals(3, idx["nes"])
        assertEquals(56, idx["naomi"], "a board keeps its own name")
        assertEquals(142, idx["neogeo"])
    }

    @Test fun `a duplicated name resolves to the general system, not a regional variant`() {
        // "Megadrive Japan" also lists plain `megadrive` among its aliases. The lower id
        // is the one every Mega Drive ROM should land on; letting the last writer win
        // would send a whole platform to a variant that holds almost nothing — and it
        // would fail as "not found", indistinguishable from a ROM nobody has dumped.
        assertEquals(1, index()["genesis"])
    }

    @Test fun `arcade resolves to the umbrella id, never to whichever board is lowest`() {
        // Two live measurements on 2026-08-08, and the first overturned the design.
        //
        // ScreenScraper has no arcade *system*: it has sixty-four boards, and every one
        // also answers to `arcade`, `mame`, `fba` and `mess`. Indexing those strings
        // would hand back whichever board is numbered lowest — Capcom Play System 1 —
        // which for a Namco set is simply the wrong system. So they are excluded.
        //
        // The first reading was that arcade therefore needs no id, since a romset name
        // is unique. The API refused that outright: "Champ systemeid obligatoire si
        // aucun CRC". Asked again with 75, 6, 142 and 47, `pacman.zip` and `mslug.zip`
        // each returned the *same* game every time — so the field is required and is
        // not a filter. Hence one deliberate constant, and 75 because that is what
        // Batocera and Skraper send.
        val idx = index()
        assertEquals(75, ScreenScraperSystemMap.systemeId("arcade", idx))
        assertEquals(75, ScreenScraperSystemMap.systemeId("mame", idx))
        assertEquals(75, ScreenScraperSystemMap.systemeId("fbneo", idx))
        // Not because 75 was indexed — it is the umbrella, and the boards that publish
        // those aliases are deliberately absent from the table.
        assertEquals(null, idx["arcade"])
        assertEquals(null, idx["mame"])
        // The boards themselves are still reachable by their own names.
        assertEquals(56, ScreenScraperSystemMap.systemeId("naomi", idx))
        // And Neo Geo is a real single system, so it answers with its own id, not the
        // umbrella — which matters, because it is the one arcade family that has one.
        assertEquals(142, ScreenScraperSystemMap.systemeId("neogeo", idx))
    }

    @Test fun `short names Pegasus and ScreenScraper spell differently still resolve`() {
        val idx = index()
        assertEquals(1, ScreenScraperSystemMap.systemeId("genesis", idx))
        assertEquals(1, ScreenScraperSystemMap.systemeId("megadrive", idx))
        assertEquals(3, ScreenScraperSystemMap.systemeId("nes", idx))
    }

    @Test fun `an unknown platform answers zero rather than a wrong id`() {
        // Zero only for platforms matched by *content*, where it is genuinely optional.
        // Zero is usable: a hash lookup does not need a system id at all, so an
        // unmapped platform still works for every cartridge and disc system. Guessing
        // one instead would turn a working lookup into a silent miss.
        assertEquals(0, ScreenScraperSystemMap.systemeId("thereisnosuchthing", index()))
        assertEquals(0, ScreenScraperSystemMap.systemeId("", index()))
    }

    @Test fun `arcade families are matched by romset name, cartridges by content`() {
        assertTrue(ScreenScraperSystemMap.matchedByName("arcade"))
        assertTrue(ScreenScraperSystemMap.matchedByName("mame"))
        assertTrue(ScreenScraperSystemMap.matchedByName("neogeo"))
        assertTrue(ScreenScraperSystemMap.matchedByName("fbneo"))
        // Everything No-Intro or Redump lists is keyed by the ROM's digest, and sending
        // a name for those would throw away the one advantage this source has.
        assertTrue(!ScreenScraperSystemMap.matchedByName("nes"))
        assertTrue(!ScreenScraperSystemMap.matchedByName("psx"))
        assertTrue(!ScreenScraperSystemMap.matchedByName(""))
    }

    @Test fun `the cached table survives a round trip`() {
        val systems = ScreenScraperClient.parseSystems(listPayload).getOrThrow()
        val reread = ScreenScraperSystemMap.fromJson(ScreenScraperSystemMap.toJson(systems))
        assertEquals(ScreenScraperSystemMap.index(systems), ScreenScraperSystemMap.index(reread))
    }

    @Test fun `a corrupt cache is empty rather than fatal`() {
        // It is a cache: losing it costs one request. Throwing here would take the
        // whole source down over a truncated file.
        assertEquals(emptyList(), ScreenScraperSystemMap.fromJson("not json at all"))
        assertEquals(emptyList(), ScreenScraperSystemMap.fromJson(""))
    }
}
