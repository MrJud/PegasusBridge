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
     * What a theme is told. Three things and not two: "this game has no
     * achievements" and "nobody could say" were one answer, and a theme
     * showed a game as having none whenever RetroAchievements was down.
     */
    sealed interface Answer {
        data class Found(val match: Match) : Answer

        /** No game. [reason] is one of the constants below, and empty when none is known. */
        data class None(val reason: String = "") : Answer

        /** The question could not be answered. [code] is for a program, [message] for a person. */
        data class Failed(val code: String, val message: String) : Answer
    }

    /** The scan asked RetroAchievements about this file and it knows no such dump. */
    const val NOT_IN_DATABASE = "not_in_database"
    /** A console RetroAchievements does not have, or one nobody can hash for. */
    const val UNSUPPORTED = "unsupported"
    /** The file stands for a game the library does not hold. */
    const val PLACEHOLDER = "placeholder"
    /** The platform has no console at RetroAchievements. */
    const val NO_CONSOLE = "no_console"
    /** The console's list was read and no title in it is close enough. */
    const val NO_NAME_MATCH = "no_name_match"

    /**
     * An exact answer from the ROM scan index, or null if the scan has not seen
     * this game. [index] is the parsed `metadata/_index.json`.
     *
     * By the two keys of [platformShortName] and no other. A key with the
     * same title half under another platform used to answer as well, for a
     * folder named unlike its collection, and what it answered with was any
     * game of that name on any console: a Game Boy Advance file was given
     * the achievements of the Super Nintendo game it is a port of. A file is
     * found by its path now ([RaGameFinder]), whatever its folder is called.
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

    /**
     * The response body both shells return for `/ra/match`.
     *
     * A match has the keys it always had and no others. A theme written
     * before the other two answers said why reads `status` and `gameId`
     * alone, and takes both for no match, as it took every failure.
     */
    fun toJson(a: Answer): JSONObject = when (a) {
        is Answer.Found -> JSONObject()
            .put("status", "ok")
            .put("gameId", a.match.gameId)
            .put("title", a.match.title)
            .put("consoleName", a.match.consoleName)
            .put("score", a.match.score)
            .put("method", a.match.method)
            .put("onDevice", a.match.onDevice)
        is Answer.None -> JSONObject().put("status", "no_match").put("gameId", 0)
            .also { if (a.reason.isNotEmpty()) it.put("reason", a.reason) }
        is Answer.Failed -> JSONObject().put("status", "error").put("gameId", 0)
            .put("code", a.code).put("error", a.message)
    }
}
