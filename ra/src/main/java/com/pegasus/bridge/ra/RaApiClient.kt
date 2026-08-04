package com.pegasus.bridge.ra

import com.pegasus.bridge.core.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object RaApiClient {

    private const val BASE = "https://retroachievements.org/API/"

    fun fetchUserSummary(user: String, apiKey: String): JSONObject {
        val url = BASE + "API_GetUserSummary.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&g=0&a=50"
        return getObj(url)
    }

    fun fetchCompletion(user: String, apiKey: String): JSONObject {
        val url = BASE + "API_GetUserCompletionProgress.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&c=500&o=0"
        return getObj(url)
    }

    fun fetchRecent(user: String, apiKey: String, count: Int = 50): JSONArray {
        val url = BASE + "API_GetUserRecentlyPlayedGames.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&c=$count&o=0"
        return getArr(url)
    }

    fun fetchGameDetail(gameId: Int, user: String, apiKey: String): JSONObject {
        val url = BASE + "API_GetGameInfoAndUserProgress.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&g=$gameId"
        return getObj(url)
    }

    /**
     * Games on a console whose title contains [filter].
     *
     * `f` is a numeric flag — 0 for all games, 1 for only those with achievements
     * — and NOT a text filter. Passing the search term there, as this used to,
     * made RA coerce it and return the whole console catalogue no matter what the
     * user typed. The API has no text-search parameter, so the filtering is done
     * here, case-insensitively.
     */
    fun searchGames(consoleId: Int, filter: String, user: String, apiKey: String,
                    limit: Int = 200, onlyWithAchievements: Boolean = false): JSONArray {
        val all = fetchGameList(consoleId, user, apiKey, onlyWithAchievements)

        val needle = filter.trim().lowercase()

        val matches = mutableListOf<JSONObject>()
        for (i in 0 until all.length()) {
            val g = all.optJSONObject(i) ?: continue
            if (needle.isEmpty() || g.optString("Title").lowercase().contains(needle))
                matches += g
        }

        // RA marks unofficial entries with a "~Hack~"/"~Demo~"/"~Prototype~"
        // prefix, and they outnumber the real releases for popular series — a
        // search for "castlevania" otherwise leads with ROM hacks. Official
        // titles first, original order preserved within each group.
        val out = JSONArray()
        for (g in matches.sortedBy { if (it.optString("Title").startsWith("~")) 1 else 0 }) {
            out.put(g)
            if (out.length() >= limit) break
        }
        return out
    }

    /** The console catalogue, unfiltered — what the matcher scores against. */
    fun fetchGameList(consoleId: Int, user: String, apiKey: String,
                      onlyWithAchievements: Boolean = false): JSONArray =
        getArr(BASE + "API_GetGameList.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&i=$consoleId" +
            "&f=${if (onlyWithAchievements) 1 else 0}&h=1")

    private fun trimTo(arr: JSONArray, limit: Int): JSONArray {
        if (arr.length() <= limit) return arr
        val out = JSONArray()
        for (i in 0 until limit) out.put(arr.get(i))
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getObj(url: String): JSONObject =
        HttpClient.get(url).fold(
            onSuccess = { body -> try { JSONObject(body) } catch (e: Exception) { JSONObject() } },
            onFailure = { JSONObject() }
        )

    private fun getArr(url: String): JSONArray =
        HttpClient.get(url).fold(
            onSuccess = { body -> try { JSONArray(body) } catch (e: Exception) { JSONArray() } },
            onFailure = { JSONArray() }
        )
}
