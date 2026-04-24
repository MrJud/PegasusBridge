package com.pegasus.bridge.hasher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RAApiClient(private val raUser: String, private val raApiKey: String) {

    companion object {
        private const val TAG         = "RAApiClient"
        private const val BASE        = "https://retroachievements.org"
        private const val USER_AGENT  = "PegasusBridge/1.0"
        private const val MAX_PARALLEL = 8
        private const val MAX_RETRIES  = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    suspend fun lookupHash(hash: String): GameMetadata? = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            try {
                val gameId = fetchGameId(hash) ?: return@withContext null
                if (gameId == 0) return@withContext GameMetadata(gameId = 0)
                fetchMetadata(gameId)
            } catch (e: Exception) {
                Log.e(TAG, "lookupHash failed for $hash", e)
                null
            }
        }
    }

    private suspend fun fetchGameId(hash: String): Int? {
        val body = httpGetWithRetry("$BASE/dorequest.php?r=gameid&m=$hash") ?: return null
        return try {
            val obj = JSONObject(body)
            if (obj.optBoolean("Success")) obj.optInt("GameID", 0) else 0
        } catch (e: Exception) { 0 }
    }

    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url  = "$BASE/API/API_GetGame.php?z=$raUser&y=$raApiKey&i=$gameId"
        val body = httpGetWithRetry(url) ?: return null
        return try {
            val obj = parseResponseObject(body) ?: return GameMetadata(gameId = gameId)
            GameMetadata(
                gameId         = obj.optInt("ID", gameId),
                title          = obj.optString("Title"),
                consoleName    = obj.optString("ConsoleName"),
                imageIcon      = obj.optString("ImageIcon"),
                numAchievements = obj.optInt("NumAchievements")
            )
        } catch (e: Exception) { GameMetadata(gameId = gameId) }
    }

    private fun parseResponseObject(body: String): JSONObject? {
        val t = body.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) return item
                }
                null
            }
            else -> null
        }
    }

    private suspend fun httpGetWithRetry(url: String): String? {
        var lastEx: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> return resp.body?.string()
                        resp.code == 429 || resp.code >= 500 -> delay(1000L shl attempt)
                        else -> return null
                    }
                }
            } catch (e: Exception) {
                lastEx = e
                delay(1000L shl attempt)
            }
        }
        Log.e(TAG, "All retries exhausted for $url", lastEx)
        return null
    }
}
