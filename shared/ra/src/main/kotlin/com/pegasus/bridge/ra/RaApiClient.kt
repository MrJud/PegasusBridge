package com.pegasus.bridge.ra

import com.pegasus.bridge.core.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * RetroAchievements Web API.
 *
 * Note on User-Agent: this client relies on the one OkHttp sends by default.
 * RA's `dorequest.php` rejects requests that carry no UA at all, and IGN's edge
 * rejects curl's. Neither affects us, but it does mean a curl reproduction of
 * these calls is not equivalent to what ships.
 */
object RaApiClient {

    // internal var, not const, so tests can point it at a MockWebServer.
    internal var BASE = "https://retroachievements.org/API/"

    fun fetchUserSummary(user: String, apiKey: String): JSONObject =
        getObj(BASE + "API_GetUserSummary.php?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&g=0&a=50")

    fun fetchCompletion(user: String, apiKey: String): JSONObject =
        getObj(BASE + "API_GetUserCompletionProgress.php?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&c=500&o=0")

    fun fetchRecent(user: String, apiKey: String, count: Int = 50): JSONArray =
        getArr(BASE + "API_GetUserRecentlyPlayedGames.php?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&c=$count&o=0")

    fun fetchGameDetail(gameId: Int, user: String, apiKey: String): JSONObject =
        getObj(BASE + "API_GetGameInfoAndUserProgress.php?z=${enc(user)}&y=${enc(apiKey)}&u=${enc(user)}&g=$gameId")

    /**
     * Every game on a console, optionally with its registered MD5 hashes.
     *
     * `f` is a numeric flag — 0 for all games, 1 for only those with achievements
     * — **not** a text filter. The old caller passed the user's search term here,
     * which RA silently coerced, so the picker received the entire catalogue no
     * matter what was typed. Text filtering happens in [searchGames] instead.
     */
    fun fetchGameList(consoleId: Int, onlyWithAchievements: Boolean = false,
                      withHashes: Boolean = true): JSONArray =
        getArr(BASE + "API_GetGameList.php?z=&y=&i=$consoleId" +
               "&f=${if (onlyWithAchievements) 1 else 0}&h=${if (withHashes) 1 else 0}")

    fun fetchGameList(consoleId: Int, user: String, apiKey: String,
                      onlyWithAchievements: Boolean = false, withHashes: Boolean = true): JSONArray =
        getArr(BASE + "API_GetGameList.php?z=${enc(user)}&y=${enc(apiKey)}&i=$consoleId" +
               "&f=${if (onlyWithAchievements) 1 else 0}&h=${if (withHashes) 1 else 0}")

    /**
     * Fetches the console catalogue and filters it here, because the API has no
     * text-search parameter. Matching is case-insensitive substring on the title;
     * an empty term returns everything.
     */
    fun searchGames(consoleId: Int, term: String, user: String, apiKey: String,
                    limit: Int = 200, onlyWithAchievements: Boolean = false): JSONArray {
        val all = fetchGameList(consoleId, user, apiKey, onlyWithAchievements)
        val needle = term.trim().lowercase()

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

    /**
     * Game metadata including the achievement count.
     *
     * Uses `API_GetGameExtended.php`: plain `API_GetGame.php` does not return
     * `NumAchievements` at all, so reading it there always yielded 0 and every
     * scanned game was recorded with zero achievements.
     */
    fun fetchGameExtended(gameId: Int, user: String, apiKey: String): JSONObject =
        getObj(BASE + "API_GetGameExtended.php?z=${enc(user)}&y=${enc(apiKey)}&i=$gameId")

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
