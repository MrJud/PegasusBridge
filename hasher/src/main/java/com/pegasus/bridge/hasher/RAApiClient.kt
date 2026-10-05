package com.pegasus.bridge.hasher

import android.util.Log
import com.pegasus.bridge.core.SafeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

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
        private const val EXCERPT_CHARS  = 160
        private val WHITESPACE = Regex("\\s+")

        /**
         * Ids above this are not games. RAWeb's VirtualGameIdService answers a hash
         * it holds but does not support with the game's id plus 1 000 000 000
         * (incompatible), 1 100 000 000 (untested) or 1 200 000 000 (needs a patch),
         * and API_GetGameExtended answers `[]` for every one of them.
         */
        const val VIRTUAL_ID_BASE = 1_000_000_000
    }

    /**
     * The outcomes a lookup can have.
     *
     * [Failed] exists because it used to be indistinguishable from [Miss]: a
     * refused request was recorded as "RetroAchievements does not know this
     * game", the file was marked processed, and an incremental rescan would
     * never try it again. Hundreds of games were written off that way.
     *
     * [Hit], [Miss] and [Incompatible] are answers; the other two are not.
     */
    sealed class Lookup {
        /** A game, with a title and an achievement count. */
        data class Hit(val meta: GameMetadata) : Lookup()
        object Miss : Lookup()
        /**
         * RetroAchievements holds the hash but only as a [virtualId]: a dump it
         * marks incompatible, untested or needing a patch. As stable an answer as
         * a [Miss], and nothing the Web API can describe, so it is not asked.
         */
        data class Incompatible(val virtualId: Int) : Lookup()
        object Failed : Lookup()
        /**
         * The Web API turned the key down. RAWeb answers 401 to a key it does
         * not know, which is what a regenerated key, a deleted account and a ban
         * (banning clears the key) all leave behind. Every later lookup that
         * finds a game would be refused the same way.
         */
        object KeyRefused : Lookup()
    }

    private class KeyRefusedException : Exception()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    private val paceMutex = Mutex()
    private var lastRequestAt = 0L

    // Per lookup, not per request, and cleared only by an answer. Cleared by any
    // 2xx, the r=gameid call — which carries no key, so RA answers it 200 even
    // when the key has been revoked — zeroed what the metadata call's 401 had
    // just raised. With two workers the count never got past 2, and the abort
    // at 8 could not happen.
    private val failures = AtomicInteger()

    /** Lookups in a row that got no answer. A caller watches this to stop a doomed scan early. */
    val consecutiveFailures: Int get() = failures.get()

    /** Spaces requests out, whatever the parallelism, so RA sees a steady trickle. */
    private suspend fun pace() = paceMutex.withLock {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    suspend fun lookupHash(hash: String): Lookup = semaphore.withPermit {
        val result = try {
            val id = fetchGameId(hash)
            when {
                id == null -> Lookup.Failed
                id == 0    -> Lookup.Miss
                // Before the metadata call, not after it: that call can only
                // answer `[]` for one of these, and asking cost a request per ROM
                // on every scan.
                id > VIRTUAL_ID_BASE -> Lookup.Incompatible(id)
                else -> fetchMetadata(id)?.let { Lookup.Hit(it) } ?: Lookup.Failed
            }
        } catch (c: CancellationException) {
            // Before the broad catch, which used to swallow it: a scan being
            // cancelled then logged an error for every lookup in flight and
            // reported each one as failed.
            throw c
        } catch (e: KeyRefusedException) {
            Lookup.KeyRefused
        } catch (e: Exception) {
            Log.e(TAG, "lookupHash failed for $hash: ${describe(e)}")
            Lookup.Failed
        }
        if (result == Lookup.Failed || result == Lookup.KeyRefused) failures.incrementAndGet()
        else failures.set(0)
        result
    }

    /**
     * The game id, 0 for a hash RA does not know, or null when the body is not
     * an answer.
     *
     * Only `Success: true` with a whole GameID counts. Read with optBoolean and
     * optInt, most of what falls short became 0 — `Success: false`, a missing
     * GameID, 0.5 — and 0 is a Miss, which also cleared the failure count. RAWeb
     * answers a client it has blocked with exactly `Success: false, GameID: 0`.
     */
    private suspend fun fetchGameId(hash: String): Int? {
        val body = httpGetWithRetry("$BASE/dorequest.php?r=gameid&m=$hash") ?: return null
        val obj = try { JSONObject(body) } catch (e: JSONException) { null }
        val id = obj?.takeIf { it.opt("Success") == true }?.let { wholeNumber(it, "GameID") }
        if (id == null) Log.w(TAG, "no usable game id for hash $hash: ${excerpt(body)}")
        return id
    }

    // API_GetGame.php does not return NumAchievements at all — its response has
    // only Title/Console/Image*/Developer/Publisher/Genre/Released. Reading the
    // field from there always yielded 0, so every scanned game was recorded with
    // zero achievements, in metadata/{gameId}.json and in the discovery index.
    // API_GetGameExtended.php returns the same fields plus the real count.
    //
    // null unless the body describes the game asked about. An HTML page served
    // with 200, a body cut short, `[]`, another game's ID, no title: each of these
    // used to come back as the id alone, which the collector counted as a game RA
    // could not describe and which cleared the failure count. For a real id they
    // are the endpoint failing, since RA describes every game it has; the one
    // `[]` that is an answer, for a virtual id, is never asked for.
    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url  = "$BASE/API/API_GetGameExtended.php?z=$raUser&y=$raApiKey&i=$gameId"
        val body = httpGetWithRetry(url) ?: return null
        val obj = try { firstObject(body) } catch (e: JSONException) { null }
        val title = (obj?.opt("Title") as? String)?.takeIf { it.isNotBlank() }
        val achievements = obj?.let { wholeNumber(it, "NumAchievements") }
        val refused = obj != null && obj.has("Success") && obj.opt("Success") != true
        if (obj == null || refused || wholeNumber(obj, "ID") != gameId || title == null || achievements == null) {
            Log.w(TAG, "no usable metadata for game $gameId: ${excerpt(body)}")
            return null
        }
        return GameMetadata(
            gameId          = gameId,
            title           = title,
            consoleName     = obj.optString("ConsoleName"),
            imageIcon       = obj.optString("ImageIcon"),
            numAchievements = achievements
        )
    }

    /**
     * A whole number from 0 up, or null.
     *
     * `optInt` was the trap: it turns 0.5 into 0, a missing field into 0 and
     * 4294967296 into 0 as well, and a GameID of 0 means "RA does not know this
     * ROM". A decimal string passes, being the same number written differently.
     */
    private fun wholeNumber(obj: JSONObject, key: String): Int? =
        obj.opt(key)?.toString()?.toIntOrNull()?.takeIf { it >= 0 }

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

    /**
     * Text from outside made fit for Logcat: one line, short, and without the API
     * key. An error page can echo the query it was sent, and the metadata query
     * carries `y=<key>`; Logcat is readable by anything holding READ_LOGS and by
     * every bug report a user pastes.
     */
    private fun excerpt(text: String?): String {
        val line = text.orEmpty().replace(WHITESPACE, " ").trim()
        if (line.isEmpty()) return "(empty)"
        val safe = if (raApiKey.isBlank()) line else line.replace(raApiKey, "***")
        return if (safe.length <= EXCERPT_CHARS) safe else safe.take(EXCERPT_CHARS) + "…"
    }

    /** The exception's class and its message, through [excerpt], never the throwable itself. */
    private fun describe(e: Exception): String =
        e.message?.let { "${e.javaClass.simpleName}: ${excerpt(it)}" } ?: e.javaClass.simpleName

    private suspend fun httpGetWithRetry(url: String): String? {
        var lastFailure = "no attempt made"
        for (attempt in 0 until MAX_RETRIES) {
            pace()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val reply = try {
                execute(req)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                lastFailure = describe(e)
                null
            }
            when {
                reply == null -> Unit
                reply.code in 200..299 -> return reply.body.orEmpty()
                // Only the metadata call carries the key, and dorequest's r=gameid
                // never answers 401, so this can only be the key. Retrying cannot
                // help and neither can the next lookup.
                reply.code == 401 -> {
                    Log.e(TAG, "API key refused (HTTP 401) for ${SafeUrl.redact(url)}")
                    throw KeyRefusedException()
                }
                // 403 belongs here: that is what being refused for too many
                // requests looks like, and treating it as fatal made the client
                // give up on the first one.
                reply.code == 403 || reply.code == 429 || reply.code >= 500 ->
                    lastFailure = "HTTP ${reply.code}"
                else -> {
                    Log.e(TAG, "HTTP ${reply.code} for ${SafeUrl.redact(url)}")
                    return null
                }
            }
            // Not after the last attempt: nothing is left to wait for, and sleeping
            // there added eight seconds to every request that failed for good.
            if (attempt < MAX_RETRIES - 1) delay(1000L shl attempt)
        }
        // Redacted: this URL carries `y=<RetroAchievements API key>`, and Logcat
        // is readable by anything holding READ_LOGS as well as by every bug
        // report a user pastes.
        Log.e(TAG, "All retries exhausted for ${SafeUrl.redact(url)} ($lastFailure)")
        return null
    }

    private class HttpReply(val code: Int, val body: String?)

    /**
     * One request that a cancelled coroutine actually stops.
     *
     * `execute()` blocked its thread in a socket read that cancellation could not
     * reach, so an aborted or cancelled scan waited for every request in flight,
     * up to the 30-second read timeout each. Cancelling the call closes the socket
     * under that read. Only a success's body is read: nothing here uses an error
     * page.
     */
    private suspend fun execute(request: Request): HttpReply = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) = cont.resumeWith(runCatching {
                response.use { HttpReply(it.code, if (it.isSuccessful) it.body?.string() else null) }
            })
        })
    }
}
