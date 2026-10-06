package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
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

/** Resolves a ROM hash to a RetroAchievements game. */
interface RaHashLookup {
    /**
     * One of four answers, which callers must keep apart:
     *
     * - null: the request never got a usable answer. Not "RetroAchievements does
     *   not know this hash" — recording a failure as an answer writes a game off,
     *   and an incremental rescan will never ask about it again.
     * - gameId 0: RetroAchievements does not know the hash. A verdict.
     * - a [VirtualGameId] with a blank title: it knows the hash, but only as a
     *   dump it does not consider playable as it is — incompatible, untested, or
     *   needing a patch. A verdict too, and not a match: there is no game to
     *   describe under that number.
     * - any other gameId, with its title: a match.
     *
     * A real id whose game could not be described is null, not the id alone: the
     * title is what makes a match, and an answer that cannot give one is a
     * failure of the source.
     */
    suspend fun lookup(hash: String): GameMetadata?

    /**
     * Lookups in a row that ended in null, so a caller can stop a doomed scan.
     * Counted once per lookup, however many requests it took, and cleared by any
     * answer except a virtual id, which leaves it as it was: no description is
     * asked for one, so it says nothing either way about whether descriptions
     * still come back.
     */
    val consecutiveFailures: Int get() = 0

    /**
     * True once the source has refused the credentials. Nothing after that can be
     * a match, since describing a game needs the key, so a caller should stop at
     * once. Waiting for [consecutiveFailures] does not work here: the hashes the
     * source does not know are answered without the key, and each of them clears
     * the count.
     */
    val authRejected: Boolean get() = false

    /**
     * True when the last request that failed did so on a device that says it
     * has no connection, and nothing has been heard from the source since. A
     * caller should stop at once, as for [authRejected], and say that this is
     * what it was: the lookups after it would fail the same way, and waiting
     * for [consecutiveFailures] took half a minute on a tablet in airplane
     * mode and then blamed a source that had never been reached.
     *
     * What happened to a request, and not what the device says when asked.
     * A lookup that had nothing to ask leaves it false wherever it runs, so a
     * library scanned before is scanned again on a plane and found cached.
     * So does a lookup whose requests were all answered, with a refusal each
     * time: it failed, and not for want of a connection. And unlike a refused
     * key it is taken back, by the first answer that arrives.
     */
    val offline: Boolean get() = false
}

/**
 * The ids RetroAchievements gives a hash it knows but does not consider playable
 * as it is: the real game id plus a base for why. Mirrors RAWeb's
 * VirtualGameIdService, where `r=gameid` gets its answer, down to the strict
 * comparisons: an id is virtual when it is *above* 1 000 000 000.
 *
 * The Web API has no game under such a number — API_GetGameExtended answers `[]`
 * — so asking it for one costs a request and learns nothing.
 */
object VirtualGameId {
    const val INCOMPATIBLE_BASE = 1_000_000_000
    const val UNTESTED_BASE = 1_100_000_000
    const val PATCH_REQUIRED_BASE = 1_200_000_000

    fun isVirtual(gameId: Int): Boolean = gameId > INCOMPATIBLE_BASE

    /** "game 1487, untested" for 1100001487: the real id and the reason, for a person to read. */
    fun describe(gameId: Int): String = when {
        gameId > PATCH_REQUIRED_BASE -> "game ${gameId - PATCH_REQUIRED_BASE}, patch required"
        gameId > UNTESTED_BASE       -> "game ${gameId - UNTESTED_BASE}, untested"
        gameId > INCOMPATIBLE_BASE   -> "game ${gameId - INCOMPATIBLE_BASE}, incompatible"
        else                         -> "game $gameId"
    }
}

/** Where [RaApiHashLookup] asks unless it is told another place, as a test tells it. */
const val RETROACHIEVEMENTS_URL = "https://retroachievements.org"

/**
 * What [RaApiHashLookup] has to do with the platform's knowledge of its own
 * connection: one question, and one thing it tells in return.
 *
 * One object and not two functions side by side. Given as a second function
 * after the first, the telling takes the place of the question in every
 * caller that writes the question as its last argument in braces, and the
 * compiler says nothing: a block that ends in `true` is as good a function
 * that returns nothing.
 */
fun interface DeviceConnection {
    /**
     * True when the platform is certain there is no connection. Asked only
     * about a request that has just failed without an answer, to tell why,
     * and never before one.
     */
    fun offline(): Boolean

    /**
     * A request has brought back an answer, whatever its status. For a
     * platform whose opinion of the connection has to be set against what a
     * request has just shown, as [OfflineVerdict] does on Android. Nothing to
     * do for one that looks afresh each time it is asked.
     */
    fun answered() {}
}

/**
 * Live implementation against retroachievements.org.
 *
 * The User-Agent is set deliberately: `dorequest.php` refuses generic ones with
 * a 403 — curl's default and OkHttp's own `okhttp/4.12.0` among them — which is
 * why a plain curl reproduction appears to show the endpoint as blocked when it
 * is not.
 *
 * [device] is what the platform knows of its own connection. It is asked
 * only about a request that has just failed without an answer, to tell why,
 * and never before one: every request is tried whatever it would say, so a
 * platform that is wrong about being offline costs nothing while requests get
 * through. What it throws counts as a no. It is told of every request that
 * is answered, and what it throws then is dropped. Left out, nothing is ever
 * put down to the connection.
 */
class RaApiHashLookup(
    private val raUser: String,
    private val raApiKey: String,
    baseUrl: String = RETROACHIEVEMENTS_URL,
    private val device: DeviceConnection = DeviceConnection { false }
) : RaHashLookup {

    private val base = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    // nanoTime rather than the wall clock, which NTP or a person can step: a step
    // backwards made the next wait as long as the step.
    private val paceMutex = Mutex()
    private var lastRequestAt = System.nanoTime() - MIN_INTERVAL_NS

    // Per lookup, not per request. Counting requests let the r=gameid call, which
    // carries no key and so is answered 200 even when the key has been revoked,
    // zero what the metadata call's 401 had just raised. The count swung between
    // 0 and 1 and a scan with a dead key went through the whole library.
    private val failures = AtomicInteger()
    override val consecutiveFailures: Int get() = failures.get()

    // Never cleared: the key is fixed for the life of this object, and the daemon
    // builds a new one for every scan.
    @Volatile private var rejected = false
    override val authRejected: Boolean get() = rejected

    // What the device said of its connection when a request last failed, and
    // false again once any request is answered. The stored value and never a
    // question put to the device from here: the pipeline reads this after
    // every lookup that came to nothing, and one that was answered four times
    // with a refusal is not to be put down to the connection.
    @Volatile private var unreachable = false
    override val offline: Boolean get() = unreachable

    /** Spaces requests out, whatever the parallelism, so RA sees a steady trickle. */
    private suspend fun pace() = paceMutex.withLock {
        val wait = MIN_INTERVAL_NS - (System.nanoTime() - lastRequestAt)
        if (wait > 0) delay(TimeUnit.NANOSECONDS.toMillis(wait + 999_999))
        lastRequestAt = System.nanoTime()
    }

    override suspend fun lookup(hash: String): GameMetadata? = semaphore.withPermit {
        val result = try {
            when (val gameId = fetchGameId(hash)) {
                null -> null
                0    -> GameMetadata(gameId = 0)
                // The id alone: the Web API has no game under it to describe. Of 143
                // ROMs one library had that RA's hash list did not match, 65 came
                // back as such ids, each costing a metadata request that answered [].
                else -> if (VirtualGameId.isVirtual(gameId)) GameMetadata(gameId = gameId)
                        else fetchMetadata(gameId)
            }
        } catch (c: CancellationException) {
            // CancellationException is an Exception, so the broad catch below used
            // to swallow it and answer `null` — which reads as "no answer" and
            // bumps the failure count. A scan being aborted would then look like a
            // scan whose source had gone down.
            throw c
        } catch (e: Exception) {
            BridgeLog.e(TAG, "lookup failed for hash $hash: ${describe(e)}")
            null
        }
        when {
            result == null -> failures.incrementAndGet()
            // Neither way. Clearing the count here let a broken metadata endpoint
            // hide behind a library's virtual ids, and counting it would let a run
            // of them stop a scan whose source is answering perfectly well.
            VirtualGameId.isVirtual(result.gameId) -> Unit
            else -> failures.set(0)
        }
        result
    }

    /**
     * The game id, 0 for a hash RA does not know, or null when the body is not
     * an answer.
     *
     * Only `Success: true` with a whole GameID counts. Most of what falls short used
     * to read as 0 — an HTML page served with 200 by a proxy or a maintenance
     * screen, `Success: false`, a missing GameID — and 0 is NOT_FOUND, which the
     * ledger keeps for fourteen days. RAWeb answers a client it has blocked with
     * `Success: false` and `GameID: 0`; read loosely, that writes off every ROM in
     * the library at once.
     */
    private suspend fun fetchGameId(hash: String): Int? {
        val reply = getWithRetry("$base/dorequest.php?r=gameid&m=$hash")?.takeIf { it.ok } ?: return null
        val body = reply.body.orEmpty()
        val obj = try { JSONObject(body) } catch (e: JSONException) { null }
        val id = obj?.takeIf { it.opt("Success") == true }?.let { wholeNumber(it, "GameID") }
        if (id == null) BridgeLog.w(TAG, "no usable game id for hash $hash: ${excerpt(body)}")
        return id
    }

    /**
     * Uses `API_GetGameExtended.php`, not `API_GetGame.php`.
     *
     * The plain endpoint does not return `NumAchievements` at all — its response
     * carries only Title, Console*, Image*, Developer, Publisher, Genre and
     * Released — so reading the field there always yielded 0 and every scanned
     * game was recorded with zero achievements.
     *
     * Only a real id is asked about, and RA hands one out only for a game it has,
     * so a body that does not describe that game — `[]`, a page that is not
     * JSON, another game's ID, no title, no achievement count, `Success: false` —
     * is the endpoint failing, and comes back as null. It used to come back as
     * the id alone, which cleared the failure count: a metadata endpoint serving
     * a maintenance page with 200 went unnoticed through a whole library, every
     * match retried and none of it counted as failing.
     *
     * A 401 is the key refused, and sets [authRejected]. It is what RAWeb's
     * api-token guard answers, `{"message":"Unauthenticated.",…}`, whenever `y`
     * matches no account's web API key: a wrong key, a revoked one, an empty one,
     * and a banned account's, since a ban clears the key. The user name is not
     * checked at all. The 404 RAWeb gives for a banned user is about the user a
     * request names in `u`, which this one does not carry.
     */
    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url = "$base/API/API_GetGameExtended.php?z=$raUser&y=$raApiKey&i=$gameId"
        val reply = getWithRetry(url) ?: return null
        if (reply.code == 401) rejected = true
        if (!reply.ok) return null
        val body = reply.body.orEmpty()
        val obj = try { firstObject(body) } catch (e: JSONException) { null }
        if (obj != null && obj.has("Success") && obj.opt("Success") != true) {
            BridgeLog.w(TAG, "metadata for game $gameId refused: ${excerpt(body)}")
            return null
        }
        val title = (obj?.opt("Title") as? String)?.takeIf { it.isNotBlank() }
        val achievements = obj?.let { wholeNumber(it, "NumAchievements") }
        if (obj == null || wholeNumber(obj, "ID") != gameId || title == null || achievements == null) {
            BridgeLog.w(TAG, "no usable metadata for game $gameId: ${excerpt(body)}")
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
     * Text from outside made fit for a log: one line, short, and without the API
     * key. An error page can echo the query it was sent, and the metadata query
     * carries `y=<key>`; exception messages go through here too, because nothing
     * promises what a library puts in one.
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

    /**
     * A success, or the first refusal that asking again would not change, or null
     * once every attempt has failed. The refusal is handed back rather than
     * reduced to null because one of them, a 401, says the key is no good.
     */
    private suspend fun getWithRetry(url: String): HttpReply? {
        var lastFailure = "no attempt made"
        for (attempt in 0 until MAX_RETRIES) {
            try {
                pace()
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val reply = execute(req)
                // An answer, whatever it says, came over a connection. Said
                // to the platform's side as well, and nothing it throws is
                // let turn a request that was answered into one that failed.
                unreachable = false
                try { device.answered() } catch (t: Throwable) { }
                when {
                    reply.ok -> return reply
                    // 403 belongs here: it is what being refused for too many
                    // requests looks like, and treating it as fatal made the
                    // client give up on the first one.
                    reply.code == 403 || reply.code == 429 || reply.code >= 500 ->
                        lastFailure = "HTTP ${reply.code}"
                    else -> {
                        BridgeLog.e(TAG, "HTTP ${reply.code} for ${SafeUrl.redact(url)}")
                        return reply
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                lastFailure = describe(e)
                // Asked here and nowhere else: after a request that was made
                // and brought back no answer at all. With no connection the
                // attempts left would fail as this one did, and the back-off
                // before each is seven seconds of waiting for nothing. With
                // one, this is a failure like any other and is tried again.
                unreachable = try { device.offline() } catch (t: Throwable) { false }
                if (unreachable) {
                    // Redacted as below, and for the same reason.
                    BridgeLog.e(TAG, "no internet connection: not asking again for " +
                                     "${SafeUrl.redact(url)} ($lastFailure)")
                    return null
                }
            }
            // Not after the last attempt: nothing is left to wait for, and sleeping
            // there added eight seconds to every request that failed for good.
            if (attempt < MAX_RETRIES - 1) delay(1000L shl attempt)
        }
        // Redacted, because this URL is `API_GetGameExtended.php?z=…&y=<api key>`
        // and the desktop log is stderr or a journal that ends up in bug reports.
        // What survives — host, endpoint and the game id — is what makes the line
        // worth having; the key never was.
        BridgeLog.e(TAG, "all retries exhausted for ${SafeUrl.redact(url)} ($lastFailure)")
        return null
    }

    private class HttpReply(val code: Int, val body: String?) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * One request that a cancelled coroutine actually stops.
     *
     * `execute()` blocked its thread in a socket read that cancellation could not
     * reach, so an abandoned scan kept its requests going until the 30-second read
     * timeout. Cancelling the call closes the socket under that read. Only a
     * success's body is read: nothing here uses an error page.
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

    private companion object {
        const val TAG = "RaApiHashLookup"
        const val USER_AGENT = "PegasusBridge/1.0"
        // Eight in flight with no pacing gets this client refused by RA after
        // about 85 requests, after which every lookup fails. Two in flight, at
        // most one every 250 ms.
        const val MAX_PARALLEL = 2
        const val MIN_INTERVAL_MS = 250L
        val MIN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(MIN_INTERVAL_MS)
        const val MAX_RETRIES = 4
        const val EXCERPT_CHARS = 160
        val WHITESPACE = Regex("\\s+")
    }
}
