package com.pegasus.bridge.ra

import com.pegasus.bridge.core.FuzzyMatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Which RetroAchievements game is this?", answered from data the caller has
 * already read.
 *
 * Nothing here touches the filesystem or the network, which is the point: the
 * desktop daemon and the Android service reach their JSON very differently, but
 * the answer they give a theme has to be the same one. This used to live in the
 * theme as QML, in two Kotlin copies besides, and the three drifted.
 */
object RaMatcher {

    data class Match(
        val gameId: Int,
        val title: String,
        val consoleName: String,
        val score: Double,
        val method: String,
        val onDevice: Boolean
    )

    /**
     * An exact answer from the ROM scan index, or null if the scan has not seen
     * this game. [index] is the parsed `metadata/_index.json`.
     */
    fun fromIndex(index: JSONObject?, title: String, platformShortName: String,
                  romPath: String?): Match? {
        val byKey = index?.optJSONObject("byKey") ?: return null

        // The hasher keys on the ROM filename, so try that before the library title.
        val keys = listOfNotNull(
            romPath?.let {
                FuzzyMatch.makeCacheKey(FuzzyMatch.extractTitleFromFilename(it), platformShortName)
            },
            FuzzyMatch.makeCacheKey(title, platformShortName)
        )

        for (k in keys) entry(byKey.optJSONObject(k))?.let { return it }

        // The platform half of a key comes from the folder the ROM sits in, and
        // users do not always name that folder the way the library does — a
        // title-only hit still identifies the game.
        for (k in keys) {
            val titlePart = k.substringBefore('|')
            if (titlePart.isEmpty()) continue
            for (other in byKey.keys()) {
                if (other.substringBefore('|') != titlePart) continue
                entry(byKey.optJSONObject(other))?.let { return it }
            }
        }
        return null
    }

    /** A scan entry only counts when the hasher actually identified the ROM. */
    private fun entry(e: JSONObject?): Match? {
        if (e == null) return null
        val id = e.optInt("gameId")
        if (id <= 0 || e.optString("title").isEmpty()) return null
        return Match(
            gameId = id,
            title = e.optString("title"),
            consoleName = RaConsoleMap.consoleName(e.optString("platform")),
            score = 1.0,
            method = "hash_index",
            onDevice = true
        )
    }

    /**
     * A best guess from the console catalogue, for a game the scan has not seen
     * — the ROM filename is preferred over the library title, because a user can
     * rename a library entry but rarely renames the file.
     */
    fun fromCatalogue(catalogue: JSONArray, title: String, platformShortName: String,
                      romPath: String?, minScore: Double = 0.60): Match? {
        if (catalogue.length() == 0) return null
        val candidates = (0 until catalogue.length()).mapNotNull { catalogue.optJSONObject(it) }
        val m = FuzzyMatch.multiMatchSearch(title, romPath, candidates, minScore) {
            it.optString("Title")
        } ?: return null

        return Match(
            gameId = m.value.optInt("ID").takeIf { it > 0 } ?: m.value.optInt("GameID"),
            title = m.value.optString("Title"),
            consoleName = RaConsoleMap.consoleName(platformShortName),
            score = m.score,
            method = m.method,
            onDevice = false
        )
    }

    /** The response body both shells return for `/ra/match`. */
    fun toJson(m: Match?): JSONObject = if (m == null) {
        JSONObject().put("status", "no_match").put("gameId", 0)
    } else {
        JSONObject()
            .put("status", "ok")
            .put("gameId", m.gameId)
            .put("title", m.title)
            .put("consoleName", m.consoleName)
            .put("score", m.score)
            .put("method", m.method)
            .put("onDevice", m.onDevice)
    }
}
