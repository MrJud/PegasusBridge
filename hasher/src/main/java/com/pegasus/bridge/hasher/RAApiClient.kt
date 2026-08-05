package com.pegasus.bridge.hasher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
        // Eight in flight with no pacing got this client refused by RA after
        // about 85 requests, and every lookup after that failed silently. Two in
        // flight, at most one every 250 ms, is ~4 req/s — enough to scan a large
        // library in minutes without looking like an attack.
        private const val MAX_PARALLEL   = 2
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_RETRIES    = 4
    }

    /**
     * The three outcomes a lookup can have.
     *
     * [Failed] exists because it used to be indistinguishable from [Miss]: a
     * refused request was recorded as "RetroAchievements does not know this
     * game", the file was marked processed, and an incremental rescan would
     * never try it again. Hundreds of games were written off that way.
     */
    sealed class Lookup {
        data class Hit(val meta: GameMetadata) : Lookup()
        object Miss : Lookup()
        object Failed : Lookup()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    private val paceMutex = kotlinx.coroutines.sync.Mutex()
    private var lastRequestAt = 0L

    /** Consecutive failures. A caller watches this to stop a doomed scan early. */
    @Volatile var consecutiveFailures = 0
        private set

    /** Spaces requests out, whatever the parallelism, so RA sees a steady trickle. */
    private suspend fun pace() = paceMutex.withLock {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    suspend fun lookupHash(hash: String): Lookup = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            try {
                when (val id = fetchGameId(hash)) {
                    null -> Lookup.Failed
                    0    -> Lookup.Miss.also { consecutiveFailures = 0 }
                    else -> {
                        val meta = fetchMetadata(id)
                        if (meta == null) Lookup.Failed
                        else { consecutiveFailures = 0; Lookup.Hit(meta) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "lookupHash failed for $hash", e)
                Lookup.Failed
            }
        }
    }

    /** null means the request failed; 0 means RetroAchievements does not know it. */
    private suspend fun fetchGameId(hash: String): Int? {
        val body = httpGetWithRetry("$BASE/dorequest.php?r=gameid&m=$hash") ?: return null
        return try {
            val obj = JSONObject(body)
            if (obj.optBoolean("Success")) obj.optInt("GameID", 0) else 0
        } catch (e: Exception) {
            // Unparseable is a failed request, not an answer.
            null
        }
    }

    // API_GetGame.php does not return NumAchievements at all — its response has
    // only Title/Console/Image*/Developer/Publisher/Genre/Released. Reading the
    // field from there always yielded 0, so every scanned game was recorded with
    // zero achievements, in metadata/{gameId}.json and in the discovery index.
    // API_GetGameExtended.php returns the same fields plus the real count.
    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url  = "$BASE/API/API_GetGameExtended.php?z=$raUser&y=$raApiKey&i=$gameId"
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
                pace()
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> { consecutiveFailures = 0; return resp.body?.string() }
                        // 403 belongs here: that is what being refused for too
                        // many requests looks like, and treating it as fatal made
                        // the client give up on the first one.
                        resp.code == 403 || resp.code == 429 || resp.code >= 500 ->
                            delay(1000L shl attempt)
                        else -> { consecutiveFailures++; return null }
                    }
                }
            } catch (e: Exception) {
                lastEx = e
                delay(1000L shl attempt)
            }
        }
        consecutiveFailures++
        Log.e(TAG, "All retries exhausted for $url", lastEx)
        return null
    }
}
