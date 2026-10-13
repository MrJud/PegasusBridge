package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The `steam-account` source, whole: what each op answers, in what state, and
 * what of it is kept on disk. Both shells compile this one copy and their
 * dispatchers hand it the request as it came, so a theme reads one payload on
 * the desktop and on Android.
 *
 * ── A state, not an error ──────────────────────────────────
 *
 * Whatever Steam does, [run] returns a `results` object, and what happened is
 * its `state`: answered, not set up, key refused, private, no achievements,
 * rate limited, offline, an answer that could not be used. A SteamException
 * used to leave here as an exception, which the desktop turned into an HTTP
 * 500 and Android into a bare sentence, and a theme could tell a private
 * profile from a dead network on neither. Only a request that is wrong in
 * itself, an op or a parameter, is thrown.
 *
 * The `message` beside the state is one of the sentences in [sentence] and
 * nothing else. It is never an exception's text and never Steam's: the first
 * can quote a URL, which holds the key, and the second is not ours to show.
 *
 * ── What is kept, and for how long ─────────────────────────
 *
 * Under `cache/steam` of the data root, beside `cache/ra-lists`:
 *
 *     schema/<appId>.<language>.json       30 days; 7 when it lists nothing
 *     <steamId>/library.json               10 minutes
 *     <steamId>/progress/<appId>.json      10 minutes
 *
 * The schema is every account's, so it is kept once per game and language.
 * The progress file is the account's own unlock list, and `achievements` is
 * the schema with that list laid over it.
 *
 * A file is written for an answer and for nothing else. Private, a refused
 * key, a rate limit, no network and an unusable answer are states of the
 * moment: kept, each would go on being said after it had stopped being true.
 * When a file has gone old and the call for a new one ends in no network, a
 * rate limit or an unusable answer, the old file is served and marked `stale`.
 * It is not served after "private" or a refused key, which are about the
 * account as it is now.
 *
 * A name on disk is made of a number parsed here, a language out of
 * [LANGUAGES] and a SteamID held to seventeen digits. No parameter is ever
 * part of a path as it came in, and the key is in no file and no name.
 *
 * One lock is held from the look at the disk to the write. On Android each
 * request runs in a coroutine of its own, and a game's four tiles asking at
 * once would otherwise each find nothing kept and each ask Steam.
 */
object SteamAccountOps {

    /** The folder of everything kept here, under the data root's cache. */
    const val DIR = "steam"

    internal const val LIBRARY_TTL_SECONDS = 10L * 60
    internal const val PROGRESS_TTL_SECONDS = 10L * 60
    internal const val SCHEMA_TTL_SECONDS = 30L * 24 * 60 * 60

    /**
     * A schema that lists nothing is believed for a week and not a month: a
     * game can be given achievements after its release, and until this runs
     * out it is shown as having none.
     */
    internal const val EMPTY_SCHEMA_TTL_SECONDS = 7L * 24 * 60 * 60

    /**
     * `refresh=1` does not reach a file younger than this. A screen that asks
     * for a refresh as it opens, and a user who leans on the button, would
     * otherwise spend the key's daily allowance on answers a minute old.
     */
    internal const val REFRESH_FLOOR_SECONDS = 60L

    /**
     * How long `progress` goes on starting calls. A theme waits on the one
     * answer for the whole list; the games not reached come back `skipped`
     * and are asked for again with the next request, by which time those
     * answered are on disk.
     */
    internal const val BUDGET_MILLIS = 8_000L

    internal const val MAX_APP_IDS = 20

    /** Of every file kept here. A file of another number is not read. */
    private const val FORMAT = 1

    /** A file dated further ahead than this was written by a clock since set back. */
    private const val CLOCK_SLACK_SECONDS = 60L

    private const val TAG = "SteamAccountOps"

    /**
     * The clock, in milliseconds. A var as [BridgeLog.current] is: nothing
     * else lets a test see a file go old or the budget run out without
     * waiting for it.
     */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    private val lock = Any()

    /**
     * What a request came to. [wire] is the word a theme reads.
     *
     * [fatal] is a state that every other game of the same request would meet
     * too, so `progress` asks about no more of them. [servesStale] is a state
     * that says nothing about the account, only that Steam could not be asked
     * just now, which is when an old file is better than none.
     */
    enum class State(val wire: String, val fatal: Boolean, val servesStale: Boolean) {
        OK("ok", false, false),
        NOT_CONFIGURED("not_configured", true, false),
        KEY_REFUSED("key_refused", true, false),
        PRIVATE("private", true, false),
        NO_ACHIEVEMENTS("no_achievements", false, false),
        RATE_LIMITED("rate_limited", true, true),
        OFFLINE("offline", true, true),
        UPSTREAM_ERROR("upstream_error", false, true),
        SKIPPED("skipped", false, false)
    }

    /**
     * The one sentence of a state. The library's "private" is its own because
     * that call cannot tell the two things it may mean apart: Steam answers a
     * profile whose game details are not public, and a SteamID that nobody
     * has, with the same empty object.
     */
    internal fun sentence(state: State, op: String): String = when (state) {
        State.OK              -> "Steam answered."
        State.NOT_CONFIGURED  -> "Steam is not set up: a Web API key and a 17-digit SteamID are needed."
        State.KEY_REFUSED     -> "Steam refused the Web API key."
        State.PRIVATE         ->
            if (op == "library")
                "Steam listed no games: this profile's game details are not public, " +
                "or no account has this SteamID."
            else "This Steam profile's game details are not public."
        State.NO_ACHIEVEMENTS -> "This game has no achievements on Steam."
        State.RATE_LIMITED    -> "Steam is refusing requests for now: too many were made."
        State.OFFLINE         -> "Steam could not be reached."
        State.UPSTREAM_ERROR  -> "Steam gave an answer that could not be used."
        State.SKIPPED         -> "Not asked this time."
    }

    /**
     * The theme's language code to Steam's name for the language, which is
     * what its API takes and what a schema file is named by. Whatever is not
     * here is `english`, and so is whatever is not a code at all.
     */
    private val LANGUAGES = mapOf(
        "ar" to "arabic", "bg" to "bulgarian", "cs" to "czech", "da" to "danish",
        "de" to "german", "el" to "greek", "en" to "english", "es" to "spanish",
        "fi" to "finnish", "fr" to "french", "hu" to "hungarian", "id" to "indonesian",
        "it" to "italian", "ja" to "japanese", "ko" to "koreana", "nb" to "norwegian",
        "nl" to "dutch", "nn" to "norwegian", "no" to "norwegian", "pl" to "polish",
        "pt" to "portuguese", "ro" to "romanian", "ru" to "russian", "sv" to "swedish",
        "th" to "thai", "tr" to "turkish", "uk" to "ukrainian", "vi" to "vietnamese",
        "zh" to "schinese"
    )

    /** Where the region changes the language Steam means. */
    private val REGIONAL = mapOf(
        "pt_br" to "brazilian",
        "zh_cn" to "schinese", "zh_sg" to "schinese", "zh_hans" to "schinese",
        "zh_tw" to "tchinese", "zh_hk" to "tchinese", "zh_hant" to "tchinese",
        "es_419" to "latam", "es_mx" to "latam", "es_ar" to "latam"
    )

    private val LANGUAGE_CODE = Regex("[a-z]{2,3}(_[a-z0-9]{2,4})?")

    internal fun steamLanguage(code: String?): String {
        val c = code.orEmpty().trim().lowercase(Locale.ROOT).replace('-', '_')
        if (!LANGUAGE_CODE.matches(c)) return "english"
        return REGIONAL[c] ?: LANGUAGES[c.substringBefore('_')] ?: "english"
    }

    // ── The one way in ──────────────────────────────────────────────────────

    /**
     * Answers [op] with the `results` object of the source's contract.
     *
     * [credentials] is the steam block as the configuration holds it, or null
     * for none; a blank field in it, or a SteamID that is not seventeen
     * digits, is the same as none. [cacheDir] is the folder [DIR] of the data
     * root's cache, or null where there is no data root, and then nothing is
     * kept and every request asks Steam.
     *
     * @throws IllegalArgumentException for an op or a parameter that is wrong
     *   in itself. Nothing Steam does is thrown, but for `resolve`, which is
     *   as it was: it throws what the client throws.
     */
    fun run(
        op: String,
        params: Map<String, String>,
        credentials: SteamAccountClient.Credentials?,
        cacheDir: File?
    ): JSONObject {
        val startedAt = clock()
        // The parameters are read before the configuration is looked at: a
        // request that is wrong is wrong on a Bridge that is not set up too.
        return when (op) {
            "resolve" -> resolve(params, credentials)
            "library" -> {
                val refresh = refreshOf(params)
                val account = accountOf(credentials)
                    ?: return libraryRefused(State.NOT_CONFIGURED, "")
                synchronized(lock) { library(account, Shelf(cacheDir), refresh) }
            }
            "progress" -> {
                val appIds = appIdsOf(params)
                val language = steamLanguage(params["lang"])
                val refresh = refreshOf(params)
                val account = accountOf(credentials)
                synchronized(lock) { progress(account, appIds, language, Shelf(cacheDir), refresh, startedAt) }
            }
            "achievements" -> {
                val appId = appIdOf(params)
                val language = steamLanguage(params["lang"])
                val refresh = refreshOf(params)
                val account = accountOf(credentials)
                    ?: return achievementsRefused(State.NOT_CONFIGURED, appId)
                synchronized(lock) { achievements(account, appId, language, Shelf(cacheDir), refresh) }
            }
            else -> throw IllegalArgumentException("steam-account: unknown op '$op'")
        }
    }

    private fun refreshOf(params: Map<String, String>): Boolean =
        params["refresh"].let { it == "1" || it == "true" }

    private fun appIdOf(params: Map<String, String>): Int =
        (params["appId"] ?: params["appid"])?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("missing numeric param: appId|appid")

    private fun appIdsOf(params: Map<String, String>): List<Int> {
        val given = (params["appIds"] ?: params["appids"]).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (given.isEmpty()) throw IllegalArgumentException("missing appIds")
        val appIds = given.map {
            it.toIntOrNull()?.takeIf { id -> id > 0 }
                ?: throw IllegalArgumentException("appIds takes positive whole numbers separated by commas")
        }.distinct()
        if (appIds.size > MAX_APP_IDS)
            throw IllegalArgumentException("appIds takes at most $MAX_APP_IDS games in one request")
        return appIds
    }

    /** The credentials when they are whole, and null for any way they are not. */
    private fun accountOf(c: SteamAccountClient.Credentials?): SteamAccountClient.Credentials? {
        if (c == null || c.apiKey.isBlank()) return null
        val steamId = c.steamId.trim()
        // Spelled out, and not `\d`: on Android that takes the digits of every script.
        if (steamId.length != 17 || !steamId.all { it in '0'..'9' }) return null
        return SteamAccountClient.Credentials(c.apiKey.trim(), steamId)
    }

    private fun nowSeconds(): Long = clock() / 1000L

    /** What every `results` object of this source begins with. */
    private fun results(state: State, op: String, cached: Boolean, stale: Boolean, fetchedAt: Long) =
        JSONObject()
            // Named so nothing downstream is tempted to add these numbers to a
            // RetroAchievements total. Two accounts, two catalogues; a combined
            // number would describe nothing that exists.
            .put("namespace", "steam")
            .put("state", state.wire)
            .put("message", sentence(state, op))
            .put("cached", cached)
            .put("stale", stale)
            .put("fetchedAt", fetchedAt)

    private fun stateOf(e: Throwable): State = when ((e as? SteamAccountClient.SteamException)?.refusal) {
        SteamAccountClient.Refusal.PRIVATE      -> State.PRIVATE
        SteamAccountClient.Refusal.KEY_REFUSED  -> State.KEY_REFUSED
        SteamAccountClient.Refusal.RATE_LIMITED -> State.RATE_LIMITED
        SteamAccountClient.Refusal.TRANSPORT    -> State.OFFLINE
        else                                    -> State.UPSTREAM_ERROR
    }

    /**
     * A line for the log about a call that gave no answer: which, and the
     * client's name for why. Never the exception's own message.
     */
    private fun note(what: String, e: Throwable, state: State) {
        val why = (e as? SteamAccountClient.SteamException)?.refusal?.name ?: e.javaClass.simpleName
        BridgeLog.w(TAG, "$what: ${state.wire} ($why)")
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    private fun resolve(params: Map<String, String>, credentials: SteamAccountClient.Credentials?): JSONObject {
        val name = params["vanity"] ?: throw IllegalArgumentException("missing vanity")
        val key = credentials?.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing steam.apiKey")
        return JSONObject().put("steamId", SteamAccountClient.resolveVanity(key, name).getOrThrow())
    }

    // ── What is on disk ─────────────────────────────────────────────────────

    /**
     * Where each kept answer lies, or nowhere when there is no data root.
     * Every name is made here and of nothing but what its function is given:
     * a number, a language of [LANGUAGES], an id [accountOf] has held to
     * seventeen digits.
     */
    private class Shelf(private val dir: File?) {
        fun schema(appId: Int, language: String): File? =
            dir?.let { File(File(it, "schema"), "$appId.$language.json") }

        fun library(steamId: String): File? =
            dir?.let { File(File(it, steamId), "library.json") }

        fun progressDir(steamId: String): File? =
            dir?.let { File(File(it, steamId), "progress") }

        fun progress(steamId: String, appId: Int): File? =
            progressDir(steamId)?.let { File(it, "$appId.json") }
    }

    /**
     * The file as an object, or null: no file, not JSON, another [FORMAT].
     * Null is an answer to ask for again, so nothing here throws and nothing
     * is mended.
     */
    private fun read(file: File?): JSONObject? {
        if (file == null || !file.isFile) return null
        return try {
            JSONObject(file.readText()).takeIf { it.optInt("format") == FORMAT }
        } catch (e: Exception) {
            null
        }
    }

    /** A cache that cannot be written costs a request next time, nothing more. */
    private fun write(file: File?, json: JSONObject) {
        if (file == null) return
        try {
            BridgePaths.writeAtomic(file, json.toString())
        } catch (e: Exception) {
            BridgeLog.w(TAG, "could not keep ${file.name}: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Whether a file of [fetchedAt] is answered from. With [refresh] only one
     * younger than the floor is; a file from the future is not either way.
     */
    private fun fresh(fetchedAt: Long, ttl: Long, refresh: Boolean, now: Long): Boolean {
        val oldest = if (refresh) minOf(ttl, REFRESH_FLOOR_SECONDS - 1) else ttl
        return now - fetchedAt in -CLOCK_SLACK_SECONDS..oldest
    }

    // ── library ─────────────────────────────────────────────────────────────

    private fun libraryRefused(state: State, steamId: String): JSONObject =
        results(state, "library", cached = false, stale = false, fetchedAt = nowSeconds())
            .put("steamId", steamId)
            .put("games", JSONArray())

    private fun library(account: SteamAccountClient.Credentials, shelf: Shelf, refresh: Boolean): JSONObject {
        val now = nowSeconds()
        val file = shelf.library(account.steamId)
        val kept = read(file)?.takeIf { it.optString("steamId") == account.steamId && it.optJSONArray("games") != null }
        if (kept != null && fresh(kept.optLong("fetchedAt"), LIBRARY_TTL_SECONDS, refresh, now))
            return libraryResults(account, shelf, kept, cached = true, stale = false)

        val games = SteamAccountClient.ownedGames(account).getOrElse { e ->
            val state = stateOf(e)
            note("library", e, state)
            return if (kept != null && state.servesStale) libraryResults(account, shelf, kept, cached = true, stale = true)
                   else libraryRefused(state, account.steamId)
        }

        val list = JSONArray()
        for (g in games) {
            list.put(JSONObject()
                .put("appId", g.appId)
                .put("name", g.name)
                .put("playtimeMinutes", g.playtimeMinutes)
                .put("playtimeLast2WeeksMinutes", g.playtimeLast2WeeksMinutes)
                .put("lastPlayedAt", g.lastPlayedAt)
                .put("iconUrl", g.iconUrl))
        }
        val fetched = JSONObject()
            .put("format", FORMAT)
            .put("fetchedAt", now)
            .put("steamId", account.steamId)
            .put("games", list)
        write(file, fetched)
        return libraryResults(account, shelf, fetched, cached = false, stale = false)
    }

    /**
     * The library as it is answered: last played first, then most played.
     *
     * A game's `unlocked`, `total` and `progressAt` are there when a progress
     * file for it is on disk, whatever its age, and are read from that file.
     * They cost no call: a library of hundreds is not hundreds of questions,
     * and what fills them in is `progress`, asked for the games on screen.
     */
    private fun libraryResults(
        account: SteamAccountClient.Credentials,
        shelf: Shelf,
        kept: JSONObject,
        cached: Boolean,
        stale: Boolean
    ): JSONObject {
        val arr = kept.optJSONArray("games") ?: JSONArray()
        val games = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optInt("appId") > 0 }
            .sortedWith(compareByDescending<JSONObject> { it.optLong("lastPlayedAt") }
                .thenByDescending { it.optInt("playtimeMinutes") }
                .thenBy { it.optString("name") })
        // One listing of the folder, not a look for a file per game.
        val withProgress = shelf.progressDir(account.steamId)?.list()?.toHashSet() ?: emptySet<String>()

        val out = JSONArray()
        for (g in games) {
            val appId = g.optInt("appId")
            val entry = JSONObject()
                .put("appId", appId)
                .put("name", g.optString("name"))
                .put("playtimeMinutes", g.optInt("playtimeMinutes"))
                .put("playtimeLast2WeeksMinutes", g.optInt("playtimeLast2WeeksMinutes"))
                .put("lastPlayedAt", g.optLong("lastPlayedAt"))
                .put("iconUrl", g.optString("iconUrl"))
                .put("headerUrl", headerUrl(appId))
                .put("launchUri", SteamAccountClient.desktopLaunchUri(appId))
            if ("$appId.json" in withProgress) {
                readProgress(shelf.progress(account.steamId, appId), account.steamId, appId)?.let {
                    entry.put("unlocked", it.unlocked).put("total", it.total).put("progressAt", it.fetchedAt)
                }
            }
            out.put(entry)
        }
        return results(State.OK, "library", cached, stale, kept.optLong("fetchedAt"))
            .put("steamId", account.steamId)
            .put("games", out)
    }

    /**
     * The store's wide picture of a game, from its number alone. Measured on
     * 2026-10-13: this address answers 200 for old games and for new ones,
     * which the address with a hash in it, the one Steam's own client uses,
     * cannot be made from here.
     */
    internal fun headerUrl(appId: Int): String =
        "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"

    // ── One game's progress ─────────────────────────────────────────────────

    private class Unlock(val id: String, val unlocked: Boolean, val unlockedAt: Long)

    /**
     * What one account has of one game, as it is kept.
     *
     * [noStats] is Steam having said that it has no stats for this game and
     * this account. Then [unlocks] is empty and [total] is the schema's count:
     * nothing when the game has no achievements, and all of them when the
     * schema lists some all the same.
     */
    private class ProgressRecord(
        val appId: Int,
        val gameName: String,
        val noStats: Boolean,
        val unlocks: List<Unlock>,
        val unlocked: Int,
        val total: Int,
        val fetchedAt: Long
    ) {
        val state: State get() = if (total == 0) State.NO_ACHIEVEMENTS else State.OK
    }

    /**
     * [trouble] is what the call ended in when [record] is an old file served
     * in its place. [schema] is the schema the answer was made with, when one
     * was needed for it.
     */
    private class ProgressGot(
        val state: State,
        val record: ProgressRecord?,
        val cached: Boolean,
        val stale: Boolean,
        val trouble: State? = null,
        val schema: SchemaGot? = null
    )

    private fun readProgress(file: File?, steamId: String, appId: Int): ProgressRecord? {
        val j = read(file) ?: return null
        if (j.optString("steamId") != steamId || j.optInt("appId") != appId) return null
        val arr = j.optJSONArray("achievements") ?: return null
        val unlocks = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return null
            Unlock(o.optString("id").ifEmpty { return null }, o.optBoolean("unlocked"), o.optLong("unlockedAt"))
        }
        return ProgressRecord(
            appId = appId,
            gameName = j.optString("gameName"),
            noStats = j.optBoolean("noStats"),
            unlocks = unlocks,
            unlocked = j.optInt("unlocked"),
            total = j.optInt("total"),
            fetchedAt = j.optLong("fetchedAt")
        )
    }

    private fun writeProgress(file: File?, steamId: String, r: ProgressRecord) {
        if (file == null) return
        val arr = JSONArray()
        for (u in r.unlocks)
            arr.put(JSONObject().put("id", u.id).put("unlocked", u.unlocked).put("unlockedAt", u.unlockedAt))
        write(file, JSONObject()
            .put("format", FORMAT)
            .put("fetchedAt", r.fetchedAt)
            .put("steamId", steamId)
            .put("appId", r.appId)
            .put("gameName", r.gameName)
            .put("noStats", r.noStats)
            .put("unlocked", r.unlocked)
            .put("total", r.total)
            .put("achievements", arr))
    }

    /**
     * What stops `progress` from starting another call: a state every other
     * game would meet as well, or the time it was given.
     */
    private class Halt(private val startedAt: Long) {
        var fatal: State? = null
        var calls = 0
        fun reached(): Boolean = fatal != null || clock() - startedAt >= BUDGET_MILLIS
    }

    /**
     * One game's progress: from disk while the file is fresh, from Steam
     * otherwise, and from an old file when Steam cannot be asked.
     *
     * A fresh file is answered from whatever [halt] says, since it costs no
     * call. Past that, a [halt] that has been reached answers `skipped`.
     *
     * "No stats" from the player call is not yet "no achievements". It was
     * measured of a tool, whose schema lists nothing, and that is the state
     * `no_achievements`. Should Steam say it of a game whose schema lists
     * some, the state is `ok` with none of them unlocked: the schema decides,
     * not the sentence. An answer that lists no achievement at all is read
     * the same way. A game the account never started needs none of this:
     * Steam answers with its whole list and nothing achieved.
     */
    private fun progressOf(
        account: SteamAccountClient.Credentials,
        appId: Int,
        language: String,
        shelf: Shelf,
        refresh: Boolean,
        halt: Halt?
    ): ProgressGot {
        val now = nowSeconds()
        val file = shelf.progress(account.steamId, appId)
        val kept = readProgress(file, account.steamId, appId)
        if (kept != null && fresh(kept.fetchedAt, PROGRESS_TTL_SECONDS, refresh, now))
            return ProgressGot(kept.state, kept, cached = true, stale = false)

        if (halt != null) {
            if (halt.reached()) return ProgressGot(State.SKIPPED, null, cached = false, stale = false)
            halt.calls++
        }

        fun failed(state: State): ProgressGot {
            if (state.fatal && halt != null && halt.fatal == null) halt.fatal = state
            return if (kept != null && state.servesStale)
                       ProgressGot(kept.state, kept, cached = true, stale = true, trouble = state)
                   else ProgressGot(state, null, cached = false, stale = false)
        }

        val stats = SteamAccountClient.playerAchievements(account, appId, language).getOrElse { e ->
            if ((e as? SteamAccountClient.SteamException)?.refusal != SteamAccountClient.Refusal.NO_STATS) {
                val state = stateOf(e)
                note("app $appId", e, state)
                return failed(state)
            }
            null
        }

        if (stats != null && stats.achievements.isNotEmpty()) {
            val record = ProgressRecord(
                appId = appId,
                gameName = stats.gameName,
                noStats = false,
                unlocks = stats.achievements.map { Unlock(it.apiName, it.unlocked, it.unlockedAt) },
                unlocked = stats.achievements.count { it.unlocked },
                total = stats.achievements.size,
                fetchedAt = now
            )
            writeProgress(file, account.steamId, record)
            return ProgressGot(record.state, record, cached = false, stale = false)
        }

        val schema = schemaOf(account, appId, language, shelf, refresh, noStats = true)
        schema.trouble?.takeIf { it.fatal }?.let { if (halt != null && halt.fatal == null) halt.fatal = it }
        val listed = schema.record ?: return failed(schema.trouble ?: State.UPSTREAM_ERROR)
        val record = ProgressRecord(
            appId = appId,
            gameName = stats?.gameName.orEmpty().ifEmpty { listed.gameName },
            noStats = true,
            unlocks = emptyList(),
            unlocked = 0,
            total = listed.achievements.size,
            fetchedAt = now
        )
        writeProgress(file, account.steamId, record)
        return ProgressGot(record.state, record, cached = false, stale = schema.stale, schema = schema)
    }

    // ── A game's schema ─────────────────────────────────────────────────────

    private class Definition(
        val id: String,
        val title: String,
        val description: String,
        val iconUrl: String,
        val iconLockedUrl: String,
        val hidden: Boolean
    )

    private class SchemaRecord(
        val gameName: String,
        val achievements: List<Definition>,
        val fetchedAt: Long
    ) {
        val ttl: Long get() = if (achievements.isEmpty()) EMPTY_SCHEMA_TTL_SECONDS else SCHEMA_TTL_SECONDS
    }

    /**
     * [trouble] is what a call for the schema ended in when one was made and
     * gave none. [record] may be there all the same: the old file, and then
     * [stale] says so.
     */
    private class SchemaGot(val record: SchemaRecord?, val trouble: State?, val stale: Boolean)

    private fun readSchema(file: File?, appId: Int, language: String): SchemaRecord? {
        val j = read(file) ?: return null
        if (j.optInt("appId") != appId || j.optString("language") != language) return null
        val arr = j.optJSONArray("achievements") ?: return null
        val list = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return null
            Definition(
                id = o.optString("id").ifEmpty { return null },
                title = o.optString("title"),
                description = o.optString("description"),
                iconUrl = o.optString("iconUrl"),
                iconLockedUrl = o.optString("iconLockedUrl"),
                hidden = o.optBoolean("hidden")
            )
        }
        return SchemaRecord(j.optString("gameName"), list, j.optLong("fetchedAt"))
    }

    private fun writeSchema(file: File?, appId: Int, language: String, r: SchemaRecord) {
        if (file == null) return
        val arr = JSONArray()
        for (d in r.achievements) {
            arr.put(JSONObject()
                .put("id", d.id)
                .put("title", d.title)
                .put("description", d.description)
                .put("iconUrl", d.iconUrl)
                .put("iconLockedUrl", d.iconLockedUrl)
                .put("hidden", d.hidden))
        }
        write(file, JSONObject()
            .put("format", FORMAT)
            .put("fetchedAt", r.fetchedAt)
            .put("appId", appId)
            .put("language", language)
            .put("gameName", r.gameName)
            .put("achievements", arr))
    }

    /**
     * A game's schema in one language: from disk while the file is fresh,
     * from Steam otherwise. [again] asks Steam whatever is on disk.
     *
     * A schema that lists nothing is kept only when [noStats], which is the
     * player call having said the same. On its own it is not believed: it is
     * also what Steam gives when it has mislaid a game's achievements, and
     * kept for a week it would take the names off every one of them.
     *
     * With [ask] false Steam is not asked at all, and what is on disk is
     * answered with however old: for a caller that has just been told Steam
     * cannot be reached, and would only be told again.
     *
     * A key refused here is not reported as one. This call is only ever made
     * after the player call answered, with the same key, about the same
     * game; Steam refuses a schema with the page it sends for a wrong key for
     * reasons of the game's own, and a key that has truly gone is refused at
     * the next player call, which says so.
     */
    private fun schemaOf(
        account: SteamAccountClient.Credentials,
        appId: Int,
        language: String,
        shelf: Shelf,
        refresh: Boolean,
        noStats: Boolean,
        again: Boolean = false,
        ask: Boolean = true
    ): SchemaGot {
        val now = nowSeconds()
        val file = shelf.schema(appId, language)
        val kept = readSchema(file, appId, language)
        if (!again && kept != null && fresh(kept.fetchedAt, kept.ttl, refresh && ask, now))
            return SchemaGot(kept, trouble = null, stale = false)
        if (!ask) return SchemaGot(kept, trouble = null, stale = kept != null)

        fun failed(state: State): SchemaGot =
            if (kept != null && state.servesStale) SchemaGot(kept, state, stale = true)
            else SchemaGot(null, state, stale = false)

        val schema = SteamAccountClient.schemaFor(account.apiKey, appId, language).getOrElse { e ->
            val state = stateOf(e).let { if (it == State.KEY_REFUSED) State.UPSTREAM_ERROR else it }
            note("schema of app $appId", e, state)
            return failed(state)
        }
        if (schema.achievements.isEmpty() && !noStats) return failed(State.UPSTREAM_ERROR)

        val record = SchemaRecord(
            gameName = schema.gameName,
            achievements = schema.achievements.map {
                Definition(it.apiName, it.displayName, it.description, it.iconUrl, it.iconGrayUrl, it.hidden)
            },
            fetchedAt = now
        )
        writeSchema(file, appId, language, record)
        return SchemaGot(record, trouble = null, stale = false)
    }

    // ── progress ────────────────────────────────────────────────────────────

    private fun progress(
        account: SteamAccountClient.Credentials?,
        appIds: List<Int>,
        language: String,
        shelf: Shelf,
        refresh: Boolean,
        startedAt: Long
    ): JSONObject {
        val halt = Halt(startedAt)
        if (account == null) halt.fatal = State.NOT_CONFIGURED
        val items = JSONArray()
        var stale = false
        var answered = 0
        for (appId in appIds) {
            val got = if (account == null) ProgressGot(State.SKIPPED, null, cached = false, stale = false)
                      else progressOf(account, appId, language, shelf, refresh, halt)
            stale = stale || got.stale
            if (got.record != null) answered++
            items.put(JSONObject()
                .put("appId", appId)
                .put("state", got.state.wire)
                .put("unlocked", got.record?.unlocked ?: 0)
                .put("total", got.record?.total ?: 0)
                .put("cached", got.cached)
                .put("fetchedAt", got.record?.fetchedAt ?: 0L))
        }
        return results(halt.fatal ?: State.OK, "progress",
                       cached = halt.calls == 0 && answered > 0, stale = stale, fetchedAt = nowSeconds())
            .put("items", items)
    }

    // ── achievements ────────────────────────────────────────────────────────

    private fun achievementsRefused(state: State, appId: Int): JSONObject =
        results(state, "achievements", cached = false, stale = false, fetchedAt = nowSeconds())
            .put("appId", appId)
            .put("gameName", "")
            .put("unlocked", 0)
            .put("total", 0)
            .put("progress", 0.0)
            .put("achievements", JSONArray())

    /**
     * One game's achievements: the schema, with the account's unlock list
     * laid over it.
     *
     * When Steam listed the account's achievements the list is that one, in
     * its order, and the schema gives each its name and pictures; one the
     * schema does not know keeps its id for a name. A schema that cannot be
     * had does not take the answer with it: that 12 of 40 are unlocked is
     * worth showing without their names.
     *
     * When Steam had no stats the list is the schema's, every one locked.
     */
    private fun achievements(
        account: SteamAccountClient.Credentials,
        appId: Int,
        language: String,
        shelf: Shelf,
        refresh: Boolean
    ): JSONObject {
        val got = progressOf(account, appId, language, shelf, refresh, halt = null)
        val record = got.record ?: return achievementsRefused(got.state, appId)
        // Known to have none: there is nothing a schema could add.
        if (record.noStats && record.total == 0)
            return achievementsRefused(State.NO_ACHIEVEMENTS, appId)
                .put("gameName", record.gameName)
                .put("cached", got.cached)
                .put("stale", got.stale)
                .put("fetchedAt", record.fetchedAt)

        // Told a moment ago that Steam cannot be reached, or will not be asked
        // more for now: the schema is taken as it lies on disk.
        val mayAsk = got.trouble?.fatal != true
        val first = got.schema
            ?: schemaOf(account, appId, language, shelf, refresh, noStats = record.noStats, ask = mayAsk)
        // A schema from before the account's list that lacks one of its
        // achievements is one the game has outgrown, and is asked for again:
        // once, since the one that comes back is no older than the list.
        val schema = if (mayAsk && first.trouble == null && outgrown(first.record, record))
            schemaOf(account, appId, language, shelf, refresh, noStats = false, again = true)
        else first
        val listed = schema.record

        val list = JSONArray()
        if (record.noStats) {
            if (listed == null) return achievementsRefused(schema.trouble ?: State.UPSTREAM_ERROR, appId)
            for (d in listed.achievements) list.put(entry(d.id, d, unlocked = false, unlockedAt = 0L))
        } else {
            val byId = listed?.achievements?.associateBy { it.id }.orEmpty()
            for (u in record.unlocks) list.put(entry(u.id, byId[u.id], u.unlocked, u.unlockedAt))
        }

        val total = list.length()
        val unlocked = if (record.noStats) 0 else record.unlocked
        return results(if (total == 0) State.NO_ACHIEVEMENTS else State.OK, "achievements",
                       cached = got.cached, stale = got.stale || schema.stale, fetchedAt = record.fetchedAt)
            .put("appId", appId)
            .put("gameName", record.gameName.ifEmpty { listed?.gameName.orEmpty() })
            .put("unlocked", unlocked)
            .put("total", total)
            .put("progress", if (total == 0) 0.0 else unlocked.toDouble() / total)
            .put("achievements", list)
    }

    private fun outgrown(schema: SchemaRecord?, record: ProgressRecord): Boolean {
        if (schema == null || record.noStats || schema.fetchedAt >= record.fetchedAt) return false
        val known = schema.achievements.mapTo(HashSet()) { it.id }
        return record.unlocks.any { it.id !in known }
    }

    private fun entry(id: String, d: Definition?, unlocked: Boolean, unlockedAt: Long): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", d?.title.orEmpty().ifEmpty { id })
            .put("description", d?.description.orEmpty())
            .put("unlocked", unlocked)
            .put("unlockedAt", unlockedAt)
            .put("iconUrl", d?.iconUrl.orEmpty())
            .put("iconLockedUrl", d?.iconLockedUrl.orEmpty())
            .put("hidden", d?.hidden ?: false)
}
