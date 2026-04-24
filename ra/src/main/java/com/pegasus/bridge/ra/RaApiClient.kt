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

    fun searchGames(consoleId: Int, filter: String, user: String, apiKey: String): JSONArray {
        val url = BASE + "API_GetGameList.php" +
            "?z=${enc(user)}&y=${enc(apiKey)}&i=$consoleId&f=${enc(filter.take(40))}&h=1"
        return getArr(url)
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
