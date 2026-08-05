package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Resolves a ROM hash to a RetroAchievements game. */
interface RaHashLookup {
    /**
     * null means the request never got an answer — not "RetroAchievements does
     * not know this hash", which is [GameMetadata] with gameId 0. Callers must
     * keep the two apart: recording a failure as an answer writes a game off,
     * and an incremental rescan will never ask about it again.
     */
    suspend fun lookup(hash: String): GameMetadata?

    /** Consecutive failed requests, so a caller can stop a doomed scan. */
    val consecutiveFailures: Int get() = 0
}

/**
 * Live implementation against retroachievements.org.
 *
 * The User-Agent is set deliberately: `dorequest.php` rejects requests that
 * carry none, which is why a curl reproduction without one appears to show the
 * endpoint as blocked when it is not.
 */
class RaApiHashLookup(
    private val raUser: String,
    private val raApiKey: String,
    baseUrl: String = "https://retroachievements.org"
) : RaHashLookup {

    private val base = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    private val paceMutex = kotlinx.coroutines.sync.Mutex()
    private var lastRequestAt = 0L

    @Volatile private var failures = 0
    override val consecutiveFailures: Int get() = failures

    /** Spaces requests out, whatever the parallelism, so RA sees a steady trickle. */
    private suspend fun pace() = paceMutex.withLock {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    override suspend fun lookup(hash: String): GameMetadata? = semaphore.withPermit {
        try {
            val gameId = fetchGameId(hash) ?: return@withPermit null
            if (gameId == 0) { failures = 0; return@withPermit GameMetadata(gameId = 0) }
            fetchMetadata(gameId)?.also { failures = 0 }
        } catch (e: Exception) {
            BridgeLog.e(TAG, "lookup failed for $hash", e)
            null
        }
    }

    private suspend fun fetchGameId(hash: String): Int? {
        val body = getWithRetry("$base/dorequest.php?r=gameid&m=$hash") ?: return null
        return try {
            val obj = JSONObject(body)
            if (obj.optBoolean("Success")) obj.optInt("GameID", 0) else 0
        } catch (e: Exception) { 0 }
    }

    /**
     * Uses `API_GetGameExtended.php`, not `API_GetGame.php`.
     *
     * The plain endpoint does not return `NumAchievements` at all — its response
     * carries only Title, Console*, Image*, Developer, Publisher, Genre and
     * Released — so reading the field there always yielded 0 and every scanned
     * game was recorded with zero achievements.
     */
    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url = "$base/API/API_GetGameExtended.php?z=$raUser&y=$raApiKey&i=$gameId"
        val body = getWithRetry(url) ?: return null
        return try {
            val obj = firstObject(body) ?: return GameMetadata(gameId = gameId)
            GameMetadata(
                gameId          = obj.optInt("ID", gameId),
                title           = obj.optString("Title"),
                consoleName     = obj.optString("ConsoleName"),
                imageIcon       = obj.optString("ImageIcon"),
                numAchievements = obj.optInt("NumAchievements")
            )
        } catch (e: Exception) { GameMetadata(gameId = gameId) }
    }

    private fun firstObject(body: String): JSONObject? {
        val t = body.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                (0 until arr.length()).firstNotNullOfOrNull { arr.opt(it) as? JSONObject }
            }
            else -> null
        }
    }

    private suspend fun getWithRetry(url: String): String? {
        var last: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                pace()
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> { failures = 0; return resp.body?.string() }
                        // 403 belongs here: it is what being refused for too many
                        // requests looks like, and treating it as fatal made the
                        // client give up on the first one.
                        resp.code == 403 || resp.code == 429 || resp.code >= 500 ->
                            delay(1000L shl attempt)
                        else -> { failures++; return null }
                    }
                }
            } catch (e: Exception) {
                last = e
                delay(1000L shl attempt)
            }
        }
        BridgeLog.e(TAG, "all retries exhausted for $url", last)
        failures++
        return null
    }

    private companion object {
        const val TAG = "RaApiHashLookup"
        const val USER_AGENT = "PegasusBridge/1.0"
        // Eight in flight with no pacing gets this client refused by RA after
        // about 85 requests, after which every lookup fails. Two in flight, at
        // most one every 250 ms.
        const val MAX_PARALLEL = 2
        const val MIN_INTERVAL_MS = 250L
        const val MAX_RETRIES = 4
    }
}
