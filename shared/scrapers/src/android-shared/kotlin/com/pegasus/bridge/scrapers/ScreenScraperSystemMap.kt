package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.FuzzyMatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pegasus collection short name → ScreenScraper `systemeid`.
 *
 * ── Why this is not a hand-written table ──────────────────────
 *
 * A wrong id does not fail loudly. `systemeid=7` for the NES when the NES is 3 produces
 * a perfectly well-formed request that finds nothing, and "not found" is also what a
 * ROM the database genuinely lacks produces — so a table written from memory reads as
 * poor coverage and can sit there for months. [RaConsoleMap][com.pegasus.bridge.ra.RaConsoleMap]
 * gets away with a literal table because it was generated from the theme's own map; here
 * there is nothing to generate from.
 *
 * So the ids come from `systemesListe.php` — the API's own list — cached on disk and
 * turned into an index by [index]. Every id used is therefore one ScreenScraper
 * published, not one anybody remembered. [ALIASES] carries only the handful of names
 * where Pegasus and ScreenScraper disagree about spelling, and each entry is a claim
 * about a *name*, which a test can check against the same dump.
 */
object ScreenScraperSystemMap {

    /**
     * Systems ScreenScraper matches by romset name rather than by content.
     *
     * These hold short-name zips — `pacman.zip`, `mslug.zip` — whose contents are a
     * pile of separately-dumped chips, so hashing the archive describes nothing the
     * database knows and hashing the largest entry inside describes one chip of many.
     * The identity is the file name, and `romnom` is how it is sent.
     *
     * This is exactly the case the libretro thumbnails cannot serve: their MAME and
     * Neo Geo repositories are keyed by MAME *descriptions* ("Pac-Man (Midway)"), which
     * no ROM set carries — measured, not assumed. So arcade is the one family where this
     * source is not merely better but the only one that works at all.
     *
     * Entries are in [FuzzyMatch.normalizePlatform] form, which is what the lookup
     * compares against — that function already folds `mame`, `fbneo` and `fba` onto
     * `arcade`, so listing those spellings here as well would be three entries that can
     * never match and would read as coverage that is not there.
     */
    private val NAME_MATCHED = setOf(
        "arcade", "neogeo", "neogeomvs",
        "cps1", "cps2", "cps3", "naomi", "atomiswave", "model2", "model3", "daphne"
    )

    fun matchedByName(shortName: String): Boolean =
        NAME_MATCHED.contains(FuzzyMatch.normalizePlatform(shortName))

    /**
     * Pegasus short name → the spelling ScreenScraper's own alias list uses.
     *
     * Deliberately tiny, and it should stay that way: [FuzzyMatch.normalizePlatform]
     * already reconciles most of the disagreements (`megadrive`/`genesis`,
     * `mame`/`arcade`, `megacd`/`segacd`), so an entry here is only warranted when the
     * *normalised* names still differ. Every one of them is a place where the code
     * asserts something about the API instead of asking it.
     */
    private val ALIASES: Map<String, String> = mapOf(
        // The ES-DE folder spelling. `normalizePlatform` knows `3ds` but not `n3ds`.
        "n3ds" to "3ds"
    )

    /**
     * Aliases so many systems publish that they identify none of them.
     *
     * Measured against the live table on 2026-08-08, and it is not a small effect:
     * **64 of the 250 systems** publish every one of these strings. ScreenScraper does
     * not model "arcade" as a system at all — it models the boards, one entry each for
     * Capcom Play System, Cave, Taito Classics, Sega Model 2 and sixty more, and every
     * one of them also answers to `arcade`, `mame`, `fba` and `mess`.
     *
     * Indexing them therefore does not produce "the arcade system", it produces
     * whichever board happens to be numbered lowest — Capcom Play System 1 — which is a
     * wrong answer for a Namco set, and a wrong answer here fails as a plain "not
     * found". Excluding them costs nothing: no system loses its identity, because every
     * one of the 64 also publishes its own board name.
     *
     * Arcade does not need an id anyway, which is the other half of this: it is matched
     * by romset name, and a MAME short name is unique across boards.
     */
    private val GENERIC_ALIASES = setOf(
        "arcade", "mame", "fba", "mess",
        "mameadvmame", "mamelibretro", "mamemame4all", "fbalibretro"
    )

    /**
     * Turns a `systemesListe.php` payload into `normalized name → id`.
     *
     * Every alias a system publishes is indexed — bar the generic ones above — because
     * the Pegasus short name turns up in a different field per system: `nom_recalbox`
     * says `megadrive` where `nom_eu` says `Mega Drive`, and normalising both to one
     * word is what makes the lookup work without a hand-written table.
     *
     * The lowest id wins a collision. That is right where the collision is a real
     * family: `snes` is claimed by four systems and the base console is 4, `gb` by three
     * and the base is 9, `genesis` by two and the base is 1 — the variants are numbered
     * later, so the first entry is the general one.
     */
    fun index(systems: List<ScreenScraperClient.SsSystem>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (s in systems) {
            for (n in s.names) {
                val key = FuzzyMatch.normalizePlatform(n)
                if (key.isEmpty() || GENERIC_ALIASES.contains(key)) continue
                val existing = out[key]
                if (existing == null || s.id < existing) out[key] = s.id
            }
        }
        return out
    }

    /**
     * The id to send for the MAME/FBA family, and why a constant is the honest answer.
     *
     * Two measurements against the live API on 2026-08-08, in this order:
     *
     * 1. A `romnom` lookup with no id is **refused**: *"Champ systemeid obligatoire si
     *    aucun CRC"*. So arcade does need one after all — the first reading, that the
     *    romset name could stand alone, was wrong and the API said so plainly.
     * 2. `pacman.zip` and `mslug.zip` were then asked with **75, 6, 142 and 47**, and
     *    all four returned *the same game*. The field is required, but it is not a
     *    filter: ScreenScraper resolves a romset name across the whole family and the id
     *    is a formality.
     *
     * Which is why the sixty-odd boards are still kept out of the index — letting
     * `arcade` resolve to whichever is numbered lowest would be an accident that happens
     * to work — and why this is one deliberate constant instead. 75 is what Batocera and
     * Skraper send, so the Bridge behaves like every other client of this API rather
     * than in a way only it relies on.
     */
    private const val ARCADE_UMBRELLA = 75

    /** 0 when the system is unknown, which is usable: a hash lookup needs no
     *  `systemeid`. A `romnom` lookup does, and always gets one. */
    fun systemeId(shortName: String, index: Map<String, Int>): Int {
        val norm = FuzzyMatch.normalizePlatform(shortName)
        if (norm.isEmpty()) return 0
        index[norm]?.let { return it }
        ALIASES[norm]?.let { alias ->
            index[FuzzyMatch.normalizePlatform(alias)]?.let { return it }
        }
        // Neo Geo and the named boards resolve above, on their own names. What is left
        // here is the umbrella — `arcade`, `mame`, `fbneo` — which by design has no
        // single system behind it.
        if (NAME_MATCHED.contains(norm)) return ARCADE_UMBRELLA
        return 0
    }

    // ── Persistence of the dump ──────────────────────────────────────────────

    fun toJson(systems: List<ScreenScraperClient.SsSystem>): String {
        val arr = JSONArray()
        for (s in systems) {
            arr.put(JSONObject()
                .put("id", s.id)
                .put("names", JSONArray(s.names))
                .put("extensions", JSONArray(s.extensions)))
        }
        return JSONObject()
            // Inlined rather than borrowed from BridgePaths: that class is the desktop
            // daemon's, and this file is compiled by the Android shell too, where the
            // data root is `Paths` instead. One clock reference is not worth a seam.
            .put("fetchedAt", System.currentTimeMillis() / 1000L)
            .put("systemes", arr)
            .toString()
    }

    fun fromJson(text: String): List<ScreenScraperClient.SsSystem> = try {
        val arr = JSONObject(text).optJSONArray("systemes") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id", 0)
            if (id <= 0) return@mapNotNull null
            ScreenScraperClient.SsSystem(
                id = id,
                names = o.optJSONArray("names")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                } ?: emptyList(),
                extensions = o.optJSONArray("extensions")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                } ?: emptyList()
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
