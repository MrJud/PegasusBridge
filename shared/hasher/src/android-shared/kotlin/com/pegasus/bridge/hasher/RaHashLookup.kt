package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.SafeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

/** Resolves a ROM hash to a RetroAchievements game. */
interface RaHashLookup {
    /**
     * One of the four answers of [LookupOutcome], which callers must keep apart:
     * a match, a hash RetroAchievements does not know, a dump it knows and does
     * not consider playable, and a lookup that got no usable answer.
     *
     * A real id whose game could not be described is [LookupOutcome.Failed], not
     * the id alone: the title is what makes a match, and an answer that cannot
     * give one is a failure of the source.
     *
     * [RaApiHashLookup] answers from the lists of consoles and cannot answer
     * this one, which names none: it fails, and asks nobody.
     */
    suspend fun lookup(hash: String): LookupOutcome

    /**
     * The same, for a caller that knows which consoles the hash may be of:
     * [consoles] are RetroAchievements' ids, the one the file was hashed as
     * first, then the others its hash may be filed under, in the order to
     * try them.
     *
     * A lookup that asks by the hash alone has no use for them, and this is
     * what it inherits. One that wraps another has to override both, or
     * what it passes on is the question without the consoles.
     */
    suspend fun lookup(hash: String, consoles: List<Int>): LookupOutcome = lookup(hash)

    /**
     * Lookups in a row that the source failed, so a caller can stop a doomed
     * scan. Counted once per lookup, however many requests it took or none,
     * and cleared by a match or a miss.
     *
     * Not every [LookupOutcome.Failed] is one of them. What counts is the
     * source not answering as itself: a request that brought nothing back, a
     * status that is not a success, a body that is not what was asked for.
     * [RaApiHashLookup] leaves out, and leaves the count as it was for, a
     * lookup it could not make of an answer that did arrive whole: a list
     * that is another console's or has no game in it, and a hash it was
     * given no console for. Those are of one console, the files of a
     * collection come one after another, and eight of them would stop a scan
     * whose source is answering for every other. [LookupOutcome.IdOnly]
     * leaves the count alone as well.
     */
    val consecutiveFailures: Int get() = 0

    /**
     * True once the source has refused the credentials. Nothing after that
     * can be answered, so a caller should stop at once and say that this is
     * what it was: left to [consecutiveFailures] the scan would stop eight
     * lookups later as one whose source is down, with the advice to wait.
     *
     * Every request of [RaApiHashLookup] carries the key, so a bad one is
     * refused at the first that is made. None is made while the lists it
     * needs are on disk and fresh, and a key refused since then goes unseen
     * until one is.
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
 * The Web API has no game under such a number, and [RaApiHashLookup] no
 * longer asks `r=gameid`: the lists it answers from hold only the hashes
 * RetroAchievements lets count, so such a dump is not in them and comes out
 * as a hash that is not known. What is here is for the answers that came
 * while it was asked, which a ledger still holds, and for an audit's.
 */
object VirtualGameId {
    const val INCOMPATIBLE_BASE = 1_000_000_000
    const val UNTESTED_BASE = 1_100_000_000
    const val PATCH_REQUIRED_BASE = 1_200_000_000

    /**
     * (1487, UNTESTED) for 1100001487: the real id and the reason, or null
     * for an id that is not virtual. The comparisons are strict, as RAWeb's
     * are, so a base itself belongs to the one below it and the lowest is a
     * game's own id.
     */
    fun decode(gameId: Int): Pair<Int, LookupOutcome.Compatibility>? = when {
        gameId > PATCH_REQUIRED_BASE -> gameId - PATCH_REQUIRED_BASE to LookupOutcome.Compatibility.PATCH_REQUIRED
        gameId > UNTESTED_BASE       -> gameId - UNTESTED_BASE to LookupOutcome.Compatibility.UNTESTED
        gameId > INCOMPATIBLE_BASE   -> gameId - INCOMPATIBLE_BASE to LookupOutcome.Compatibility.INCOMPATIBLE
        else                         -> null
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
 * It asks for the list of a console, `API_GetGameList.php` with its hashes,
 * and answers every hash of that console from it: one request for a console
 * where there was one for every file and another for every match. Nothing is
 * asked about a hash, so nothing is asked of `dorequest.php`, and the list
 * says of a game what a match writes down, so nothing is asked about a game
 * either.
 *
 * A list is asked for when the first hash needs it and not before, kept
 * under [lists] as [RaGameList] writes it, and answered from for
 * [RaGameList.MAX_AGE_SECONDS]. A scan whose files are all settled asks for
 * none and reads none; one that finds its lists on disk and fresh makes no
 * request at all.
 *
 * What it must never do is take a list it does not have for a list that does
 * not have the hash. A miss is kept for fourteen days, and a list is every
 * file of a console at once. So a hash is not known only when every list it
 * was to be looked up in was read whole, and each held a game. A list that
 * could not be had fails the lookups that need it, for the rest of this
 * object's life and without being asked for again: its console's files come
 * one after another, and asking for each would be four requests a file. A
 * list past its time whose refresh fails is not answered from either.
 *
 * Every request carries the key, so the first one made shows whether it is
 * good: see [authRejected].
 *
 * The User-Agent is set deliberately: RetroAchievements refuses generic ones
 * with a 403, curl's default and OkHttp's own `okhttp/4.12.0` among them.
 *
 * [clock] gives the time a list is dated with and held to, in seconds.
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
    private val lists: File,
    baseUrl: String = RETROACHIEVEMENTS_URL,
    private val clock: () -> Long = BridgePaths::epochSeconds,
    private val device: DeviceConnection = DeviceConnection { false }
) : RaHashLookup {

    private val base = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // nanoTime rather than the wall clock, which NTP or a person can step: a step
    // backwards made the next wait as long as the step.
    private val paceMutex = Mutex()
    private var lastRequestAt = System.nanoTime() - MIN_INTERVAL_NS

    /** What became of a console's list, kept for as long as this object is. */
    private sealed interface Loaded {
        class Ok(val list: RaGameList) : Loaded

        /**
         * The list could not be had. [why] is what every lookup that needs
         * it ends with, and [counts] whether that is held against the
         * source: see [consecutiveFailures].
         */
        class Failed(val why: LookupOutcome.Failed, val counts: Boolean) : Loaded
    }

    // One list is loaded at a time, whoever asks: two workers that need the
    // same console make one request, and the second finds the list there.
    // Read without the lock first, so that a worker whose console is loaded
    // never waits for another's to be fetched.
    private val loadLock = Mutex()
    private val loaded = ConcurrentHashMap<Int, Loaded>()

    private val attempts = AtomicInteger()
    private val fetched = AtomicInteger()
    private val read = AtomicInteger()

    /** Requests made, each attempt of one that was retried counted. */
    val requests: Int get() = attempts.get()
    /** Lists a request brought back and that were taken. */
    val listsFetched: Int get() = fetched.get()
    /** Lists found on disk, fresh, and answered from with no request. */
    val listsRead: Int get() = read.get()

    // Per lookup, not per request, and not for every lookup that fails: see
    // [RaHashLookup.consecutiveFailures] for which. A lookup that meets a
    // list already known to be missing makes no request and counts all the
    // same, which is what stops a scan on a source that is down after eight
    // files and four requests.
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

    override suspend fun lookup(hash: String): LookupOutcome = lookup(hash, emptyList())

    override suspend fun lookup(hash: String, consoles: List<Int>): LookupOutcome {
        var counts = true
        val outcome = try {
            val answer = answer(hash, consoles)
            counts = answer.counts
            answer.outcome
        } catch (c: CancellationException) {
            // CancellationException is an Exception, so the broad catch below used
            // to swallow it and answer `null` — which reads as "no answer" and
            // bumps the failure count. A scan being aborted would then look like a
            // scan whose source had gone down.
            throw c
        } catch (e: Exception) {
            // For a fault in the lookup itself. What a request throws is caught
            // where it is retried, and what a body's JSON throws where the body
            // is read, so nothing the source does is known to arrive here. It
            // is given as no answer, which is what it was counted as while a
            // failure was a null, and nothing reads the kind yet: whoever first
            // acts on a kind should ask whether this one wants its own.
            BridgeLog.e(TAG, "lookup failed for hash $hash: ${describe(e)}")
            LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, kind(e))
        }
        when (outcome) {
            is LookupOutcome.Failed -> if (counts) failures.incrementAndGet()
            // Neither way. Nothing here answers it any more, and the type is
            // one whose every case has to be named.
            is LookupOutcome.IdOnly -> Unit
            is LookupOutcome.Match, LookupOutcome.NotFound -> failures.set(0)
        }
        return outcome
    }

    private class Answer(val outcome: LookupOutcome, val counts: Boolean = true)

    /**
     * The hash in the first of its consoles' lists that has it.
     *
     * The lists are gone through in the order given and the next is loaded
     * only after a miss in the one before, so a hash found where it was
     * hashed costs its own console's list and no other. A list that could
     * not be had ends the lookup there as a failure: the hash may be in it,
     * and "not known" is for a hash every list was looked in.
     *
     * In lowercase, as the lists' keys are whatever case they came in.
     */
    private suspend fun answer(hash: String, consoles: List<Int>): Answer {
        val md5 = hash.lowercase(Locale.ROOT)
        val wanted = consoles.filter { it > 0 }.distinct()
        // Not the source's doing, and so not held against it.
        if (wanted.isEmpty()) {
            return Answer(LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no console to look the hash up in"),
                          counts = false)
        }
        for (console in wanted) {
            val list = when (val had = listFor(console)) {
                is Loaded.Failed -> return Answer(had.why, had.counts)
                is Loaded.Ok -> had.list
            }
            val game = list[md5] ?: continue
            // The title is what makes a match, and a count that is no number
            // would be written down as a game with no achievements. The list
            // is taken all the same: this is one game of it.
            if (game.title.isBlank() || game.numAchievements < 0) {
                BridgeLog.w(TAG, "console $console: no usable description of game ${game.gameId} for hash $md5")
                return Answer(LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED,
                                                   "no usable description of game ${game.gameId}"))
            }
            return Answer(LookupOutcome.Match(GameMetadata(
                gameId          = game.gameId,
                title           = game.title,
                consoleName     = list.consoleName,
                imageIcon       = game.imageIcon,
                numAchievements = game.numAchievements
            )))
        }
        return Answer(LookupOutcome.NotFound)
    }

    private suspend fun listFor(console: Int): Loaded =
        loaded[console] ?: loadLock.withLock {
            // A failure is kept as a list is. What is thrown, a cancelled
            // scan or a fault in here, keeps nothing and lets go of the lock.
            loaded[console] ?: load(console).also { loaded[console] = it }
        }

    /** From disk when the list there is whole and fresh, and by a request when not. */
    private suspend fun load(console: Int): Loaded {
        // The key has been refused once and is the same key: no request is
        // spent on hearing it again for another console.
        if (rejected) return Loaded.Failed(LookupOutcome.Failed(LookupOutcome.Cause.AUTH, "HTTP 401"), counts = true)
        val now = clock()
        val kept = RaGameList.read(lists, console)?.takeIf { it.freshAt(now) } ?: return fetch(console)
        read.incrementAndGet()
        BridgeLog.i(TAG, "console $console: ${kept.games} games, ${kept.hashes} hashes, read from disk, " +
                         "${(now - kept.fetchedAt).coerceAtLeast(0) / DAY_SECONDS} days old")
        return Loaded.Ok(kept)
    }

    /**
     * Asks for the list, without `f=1`: games with no achievements are most
     * of a list and hold most of its hashes, and a file of one of them is a
     * game RetroAchievements knows.
     *
     * A 401 is the key refused, and sets [authRejected]. It is what RAWeb's
     * api-token guard answers, `{"message":"Unauthenticated.",…}`, whenever `y`
     * matches no account's web API key: a wrong key, a revoked one, an empty one,
     * and a banned account's, since a ban clears the key. The user name is not
     * checked at all.
     *
     * Nothing is written to disk for a list that is not taken.
     */
    private suspend fun fetch(console: Int): Loaded {
        // Built and not written out: a key or a name with a character a URL
        // gives a meaning to, an `&` or a `+`, would arrive as something else.
        val url = "$base/API/API_GetGameList.php".toHttpUrl().newBuilder()
            .addQueryParameter("z", raUser)
            .addQueryParameter("y", raApiKey)
            .addQueryParameter("i", console.toString())
            .addQueryParameter("h", "1")
            .build().toString()
        val reply = when (val got = getWithRetry(url)) {
            is Step.GaveUp -> return Loaded.Failed(got.failed, counts = true)
            is Step.Got -> got.value
        }
        if (reply.code == 401) {
            rejected = true
            return failed(LookupOutcome.Cause.AUTH, "HTTP 401")
        }
        if (!reply.ok) return failed(LookupOutcome.Cause.REFUSED, "HTTP ${reply.code}")
        val body = reply.body.orEmpty()
        val noList = "no usable list of console $console"
        return when (val parsed = RaGameList.parse(console, body, clock())) {
            // Not a list at all: a page served with 200 by a proxy or a
            // maintenance screen, an answer cut short, an object that says no.
            // The source is not answering as itself, whatever the console.
            RaGameList.Parsed.NotAnArray -> {
                BridgeLog.w(TAG, "$noList: ${excerpt(body)}")
                val obj = try { JSONObject(body) } catch (e: JSONException) { null }
                if (saysNo(obj)) failed(LookupOutcome.Cause.REFUSED, "the list of console $console was refused")
                else failed(LookupOutcome.Cause.MALFORMED, noList)
            }
            // A whole answer that is not this console's list. The source
            // answered, so this is not held against it: it is one console's
            // trouble, and the rest of the library is still to be scanned.
            is RaGameList.Parsed.Refused -> {
                BridgeLog.w(TAG, "$noList, ${parsed.why}: ${excerpt(body)}")
                failed(LookupOutcome.Cause.MALFORMED, noList, counts = false)
            }
            is RaGameList.Parsed.Listed -> {
                val list = parsed.list
                // No console a scan asks about is empty: its files were
                // hashed as a console RetroAchievements has games for. An
                // empty list is an answer gone wrong, and believed it would
                // make a miss of every file of the console for fourteen days.
                if (list.listed == 0) {
                    BridgeLog.w(TAG, "an empty list of console $console: not taken, and not kept")
                    return failed(LookupOutcome.Cause.MALFORMED, "an empty list of console $console", counts = false)
                }
                // A list that cannot be kept is still the list: this scan
                // answers from it, and the next asks again.
                try { list.write(lists) } catch (e: Exception) {
                    BridgeLog.w(TAG, "console $console: the list could not be kept on disk: ${describe(e)}")
                }
                fetched.incrementAndGet()
                BridgeLog.i(TAG, "console $console: ${list.listed} listed, ${list.games} games, " +
                                 "${list.hashes} hashes, fetched")
                Loaded.Ok(list)
            }
        }
    }

    private fun failed(cause: LookupOutcome.Cause, detail: String, counts: Boolean = true) =
        Loaded.Failed(LookupOutcome.Failed(cause, detail), counts)

    /**
     * What one request came to: the reply, or the failure the lookup ends
     * with. The failure is made where it happens, which is the only place
     * that knows what kind it was.
     */
    private sealed interface Step<out T> {
        class Got<T>(val value: T) : Step<T>
        class GaveUp(val failed: LookupOutcome.Failed) : Step<Nothing>
    }

    private fun gaveUp(cause: LookupOutcome.Cause, detail: String) =
        Step.GaveUp(LookupOutcome.Failed(cause, detail))

    /**
     * A body that is an object with a `Success` that is not true: the source
     * refusing. One that says nothing of the kind and cannot be used is only
     * not an answer.
     */
    private fun saysNo(obj: JSONObject?): Boolean =
        obj != null && obj.has("Success") && obj.opt("Success") != true

    // The key as it travels in a URL, where it differs from the key as
    // typed: once as the request carries it and once as a form would write
    // it, with a `+` for a space.
    private val keyForms: List<String> = if (raApiKey.isBlank()) emptyList() else listOfNotNull(
        raApiKey,
        try {
            HttpUrl.Builder().scheme("http").host("localhost").addQueryParameter("y", raApiKey)
                .build().encodedQuery?.removePrefix("y=")
        } catch (e: Exception) { null },
        try { URLEncoder.encode(raApiKey, "UTF-8") } catch (e: Exception) { null }
    ).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /**
     * Text from outside made fit for a log: one line, short, and without the API
     * key. An error page can echo the query it was sent, and every query
     * carries `y=<key>`, as typed or as a URL writes it; exception messages
     * go through here too, because nothing promises what a library puts in
     * one.
     */
    private fun excerpt(text: String?): String {
        val line = text.orEmpty().replace(WHITESPACE, " ").trim()
        if (line.isEmpty()) return "(empty)"
        val safe = keyForms.fold(line) { kept, key -> kept.replace(key, "***") }
        return if (safe.length <= EXCERPT_CHARS) safe else safe.take(EXCERPT_CHARS) + "…"
    }

    /**
     * The exception's class and its message, through [excerpt], never the
     * throwable itself. For the log and nowhere else: see [kind].
     */
    private fun describe(e: Exception): String =
        e.message?.let { "${e.javaClass.simpleName}: ${excerpt(it)}" } ?: e.javaClass.simpleName

    /**
     * The exception's class alone, which is what a [LookupOutcome.Failed]
     * says of a request that brought nothing back. Not [describe]: the
     * message of such an exception is where the request was going. A refused
     * connection names the address and the port, a name that does not resolve
     * is the whole of its message, and a connection dropped half-way quotes
     * the URL. The log has all of that, on a line that names the host anyway;
     * the outcome goes wherever a caller takes it, a ledger or a screen.
     */
    private fun kind(e: Exception): String =
        e.javaClass.simpleName.ifEmpty { e.javaClass.name.substringAfterLast('.') }

    /**
     * A success, or the first refusal that asking again would not change, or the
     * failure once every attempt has failed: what the last of them was, a status
     * or no answer at all. The refusal is handed back as the reply it is rather
     * than as a failure because one of them, a 401, says the key is no good,
     * and what follows from that is the caller's to do.
     */
    private suspend fun getWithRetry(url: String): Step<HttpReply> {
        var last = LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, "no attempt made")
        // What the last attempt came to, in the words the log has for it: for
        // a status the same as the failure's own, for an exception more.
        var said = last.detail
        for (attempt in 0 until MAX_RETRIES) {
            try {
                pace()
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                attempts.incrementAndGet()
                val reply = execute(req)
                // An answer, whatever it says, came over a connection. Said
                // to the platform's side as well, and nothing it throws is
                // let turn a request that was answered into one that failed.
                unreachable = false
                try { device.answered() } catch (t: Throwable) { }
                when {
                    reply.ok -> return Step.Got(reply)
                    // 403 belongs here: it is what being refused for too many
                    // requests looks like, and treating it as fatal made the
                    // client give up on the first one.
                    reply.code == 403 || reply.code == 429 || reply.code >= 500 -> {
                        last = LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP ${reply.code}")
                        said = last.detail
                    }
                    else -> {
                        BridgeLog.e(TAG, "HTTP ${reply.code} for ${SafeUrl.redact(url)}")
                        return Step.Got(reply)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                last = LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, kind(e))
                said = describe(e)
                // Asked here and nowhere else: after a request that was made
                // and brought back no answer at all. With no connection the
                // attempts left would fail as this one did, and the back-off
                // before each is seven seconds of waiting for nothing. With
                // one, this is a failure like any other and is tried again.
                unreachable = try { device.offline() } catch (t: Throwable) { false }
                if (unreachable) {
                    // Redacted as below, and for the same reason.
                    BridgeLog.e(TAG, "no internet connection: not asking again for " +
                                     "${SafeUrl.redact(url)} ($said)")
                    return gaveUp(LookupOutcome.Cause.OFFLINE, last.detail)
                }
            }
            // Not after the last attempt: nothing is left to wait for, and sleeping
            // there added eight seconds to every request that failed for good.
            if (attempt < MAX_RETRIES - 1) delay(1000L shl attempt)
        }
        // Redacted, because this URL is `API_GetGameList.php?z=…&y=<api key>`
        // and the desktop log is stderr or a journal that ends up in bug reports.
        // What survives — host, endpoint and the console — is what makes the
        // line worth having; the key never was.
        BridgeLog.e(TAG, "all retries exhausted for ${SafeUrl.redact(url)} ($said)")
        return Step.GaveUp(last)
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
        // A request is a whole console now and there are few of them, one at
        // a time behind the lock that loads a list. The spacing is the one
        // there was for a request per file.
        const val MIN_INTERVAL_MS = 250L
        val MIN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(MIN_INTERVAL_MS)
        const val MAX_RETRIES = 4
        const val EXCERPT_CHARS = 160
        const val DAY_SECONDS = 24L * 60 * 60
        val WHITESPACE = Regex("\\s+")
    }
}
