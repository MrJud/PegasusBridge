package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.HttpClient
import com.pegasus.bridge.core.SafeUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The Steam **account**: what a user owns, what they have played, what they have
 * unlocked. Deliberately not the same thing as [SteamStoreClient].
 *
 * The two were one idea in the proposal and are two things in practice. The
 * store is public product data and needs no key; this needs the user's own Web
 * API key and their SteamID, answers only about them, and can be refused for
 * reasons that have nothing to do with the request being wrong.
 *
 * ── The refusal that is not an error ───────────────────────
 *
 * Achievements are visible only on a **public** profile. A private one is not a
 * failure to retry and not an empty result to cache — it is a state to show,
 * with a sentence saying what to change. Collapsing it into "no achievements"
 * would tell a user their game has none when what it has is a privacy setting.
 *
 * ── The status says less than the body ─────────────────────
 *
 * Measured against Steam on 2026-10-13: a wrong key is **HTTP 403 with an HTML
 * body** on `GetPlayerAchievements` and **401 with an HTML body** on
 * `GetOwnedGames`, and no key at all is **400 with an HTML body**. A profile
 * that is not public is a **403** as well, and a game that has no stats a
 * **400**, both measured the same day, and what tells them from a refused key
 * is that their body is JSON and says which it is:
 * `{"playerstats":{"error":"Profile is not public","success":false}}` and
 * `{"playerstats":{"error":"Requested app has no stats","success":false}}`.
 * So the body is read first and the status second, in [judge], and a status
 * alone never names a refusal that a body could have named better. This used
 * to go by the status before the body was looked at, and a private profile
 * came out as a key to type again.
 */
object SteamAccountClient {

    internal var BASE = "https://api.steampowered.com"

    /**
     * [apiKey] is the user's own, from steamcommunity.com/dev/apikey.
     * [steamId] is the 64-bit id; [resolveVanity] turns a profile name into one.
     */
    data class Credentials(val apiKey: String, val steamId: String)

    /**
     * Why a call gave no answer. [NO_STATS] and [NOT_FOUND] are Steam answering
     * "there is none", which stays true and may be kept. Every other one is
     * Steam declining, for a reason that may be gone by the next call.
     *
     * [PRIVATE] was counted among the answers once. It is a setting the user
     * can change in a minute, and an answer kept on disk would go on saying
     * "private" after they had.
     */
    enum class Refusal {
        /** The profile's game details are not public. A state to show, never to keep. */
        PRIVATE,
        /** Steam did not take the key: wrong, revoked or missing. */
        KEY_REFUSED,
        /**
         * "Requested app has no stats". Measured of a tool, which has no
         * achievements, and said of it while the profile was private too. It
         * is not what a game the account never started gets: that one was
         * answered with its whole list and nothing achieved. Whether the game
         * has achievements is for the schema to say.
         */
        NO_STATS,
        /** No profile of that name. [resolveVanity] only. */
        NOT_FOUND,
        /** HTTP 429. */
        RATE_LIMITED,
        TRANSPORT,
        /** A status that is Steam's own trouble: 5xx, or one this table has no row for. */
        SERVER,
        /** A body that cannot be used: not JSON, or a refusal in words not known here. */
        MALFORMED;

        val isAnswer: Boolean get() = this == NO_STATS || this == NOT_FOUND
    }

    class SteamException(message: String, val refusal: Refusal) : Exception(message)

    data class OwnedGame(
        val appId: Int,
        val name: String,
        val playtimeMinutes: Int,
        val playtimeLast2WeeksMinutes: Int,
        val iconHash: String,
        val lastPlayedAt: Long
    ) {
        /** Steam's CDN serves the icon from the app id and the hash together. */
        val iconUrl: String get() =
            if (iconHash.isBlank()) ""
            else "https://media.steampowered.com/steamcommunity/public/images/apps/$appId/$iconHash.jpg"
    }

    data class Achievement(
        val apiName: String,
        val unlocked: Boolean,
        val unlockedAt: Long,
        val displayName: String = "",
        val description: String = "",
        val iconUrl: String = "",
        val iconGrayUrl: String = "",
        val hidden: Boolean = false
    )

    /**
     * What `GetPlayerAchievements` says of one game: its name, and every
     * achievement with whether this account has it. Names and pictures are not
     * here; they are the schema's.
     */
    data class PlayerStats(val gameName: String, val achievements: List<Achievement>)

    /**
     * What `GetSchemaForGame` says of one game: its name and its achievements
     * as they are called and pictured, in the order Steam lists them. The same
     * for every account, and none of them [Achievement.unlocked].
     */
    data class Schema(val gameName: String, val achievements: List<Achievement>)

    // ── Requests ────────────────────────────────────────────────────────────

    /** What [judge] makes of one answer: the JSON to read, or why there is none. */
    internal sealed interface Verdict {
        class Answer(val root: JSONObject) : Verdict
        class Refused(val refusal: Refusal) : Verdict
    }

    /**
     * One answer, judged by the first row that fits. The rows are in this order
     * on purpose: what the body says comes before anything the status says.
     *
     * A profile that is not public and a game without stats are named by
     * `playerstats.error` at whatever status they arrive with. After them the
     * status speaks: 429, then 401, then a 403 or a 400 **whose body is not
     * JSON**, which is the page Steam sends for a key it does not take. A 403
     * or a 400 that is JSON and names neither of the two above is Steam saying
     * something this table does not know, and is not held against the key.
     *
     * Nothing here quotes the body. It is Steam's text, and a sentence shown to
     * the user is never made of it.
     */
    internal fun judge(code: Int, body: String): Verdict {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val stats = root?.optJSONObject("playerstats")
        val said = stats?.optString("error").orEmpty()
        val declined = stats != null && (said.isNotEmpty() || !stats.optBoolean("success", true))
        val refusal = when {
            said.contains("not public", ignoreCase = true) -> Refusal.PRIVATE
            said.contains("no stats", ignoreCase = true)   -> Refusal.NO_STATS
            code == 429                                    -> Refusal.RATE_LIMITED
            code == 401                                    -> Refusal.KEY_REFUSED
            (code == 403 || code == 400) && root == null   -> Refusal.KEY_REFUSED
            code >= 500                                    -> Refusal.SERVER
            declined                                       -> Refusal.MALFORMED
            code !in 200..299                              -> Refusal.SERVER
            root == null                                   -> Refusal.MALFORMED
            else                                           -> return Verdict.Answer(root)
        }
        return Verdict.Refused(refusal)
    }

    /**
     * The sentence of a [SteamException]. [what] is words of this file and an
     * app id, never a parameter as it came in and never anything Steam or the
     * network layer wrote: the URL carries the key, and an exception raised
     * over a URL can quote it.
     */
    private fun sentence(refusal: Refusal, what: String, code: Int): String = when (refusal) {
        Refusal.PRIVATE      -> "this Steam profile's game details are not public"
        Refusal.KEY_REFUSED  -> "Steam refused the API key"
        Refusal.NO_STATS     -> "Steam has no stats for this game and this account"
        Refusal.NOT_FOUND    -> "Steam knows nothing by that name"
        Refusal.RATE_LIMITED -> "Steam is refusing requests for now: too many were made"
        Refusal.TRANSPORT    -> "could not reach Steam for $what"
        Refusal.SERVER       -> "Steam answered HTTP $code for $what"
        Refusal.MALFORMED    -> "Steam's answer for $what could not be used"
    }

    private fun refused(refusal: Refusal, what: String, code: Int = 0): SteamException =
        SteamException(sentence(refusal, what, code), refusal)

    // The ordinary client and its ten seconds: these are small JSON answers,
    // asked for while a screen waits. The patient client is ScreenScraper's.
    private fun call(url: String, what: String): Result<JSONObject> {
        val resp = runCatching { HttpClient.getRaw(url).getOrThrow() }
            .getOrElse { return Result.failure(refused(Refusal.TRANSPORT, what)) }

        return when (val verdict = judge(resp.code, resp.body)) {
            is Verdict.Answer  -> Result.success(verdict.root)
            is Verdict.Refused -> Result.failure(refused(verdict.refusal, what, resp.code))
        }
    }

    // A key is letters and digits and a SteamID is digits, as Steam issues
    // them. Neither is checked before it comes here, and a profile name is
    // whatever the user typed: an `&` in any of them would begin a parameter.
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** A profile name to a 64-bit id. */
    fun resolveVanity(apiKey: String, vanityName: String): Result<String> {
        val url = "$BASE/ISteamUser/ResolveVanityURL/v1/?key=${enc(apiKey)}&vanityurl=${enc(vanityName)}"
        return call(url, "the profile name").mapCatching { root ->
            val r = root.optJSONObject("response")
                ?: throw SteamException("no response block", Refusal.MALFORMED)
            if (r.optInt("success") != 1)
                throw SteamException("no Steam profile called '$vanityName'", Refusal.NOT_FOUND)
            r.optString("steamid")
        }
    }

    /**
     * The owned library.
     *
     * `include_appinfo` is what turns a list of numbers into a list of games, and
     * `include_played_free_games` is why a library that plays nothing but free
     * titles does not come back empty.
     */
    fun ownedGames(c: Credentials): Result<List<OwnedGame>> {
        val url = "$BASE/IPlayerService/GetOwnedGames/v1/?key=${enc(c.apiKey)}&steamid=${enc(c.steamId)}" +
                  "&include_appinfo=1&include_played_free_games=1&format=json"
        return call(url, "the owned games").mapCatching { root ->
            val r = root.optJSONObject("response")
                ?: throw SteamException("no response block", Refusal.MALFORMED)
            // An empty object — not an empty list — is what a profile whose game
            // details are not public returns, and what a SteamID nobody has
            // returns too. Saying "you own nothing" would be a wrong answer with
            // no hint about either. An account that owns nothing still has its
            // `game_count`, of 0.
            if (!r.has("games") && !r.has("game_count"))
                throw refused(Refusal.PRIVATE, "the owned games")
            parseOwnedGames(r.optJSONArray("games"))
        }
    }

    internal fun parseOwnedGames(arr: JSONArray?): List<OwnedGame> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            OwnedGame(
                appId = o.optInt("appid"),
                name = o.optString("name"),
                playtimeMinutes = o.optInt("playtime_forever"),
                playtimeLast2WeeksMinutes = o.optInt("playtime_2weeks"),
                iconHash = o.optString("img_icon_url"),
                lastPlayedAt = o.optLong("rtime_last_played")
            )
        }.filter { it.appId > 0 }
    }

    /**
     * Which of one game's achievements this account has.
     *
     * [language] is one of Steam's own names for a language, `italian` or
     * `english`, and not a code. It changes nothing this keeps; it is sent so
     * that both calls about a game are made in one language.
     */
    fun playerAchievements(c: Credentials, appId: Int, language: String = "english"): Result<PlayerStats> {
        val url = "$BASE/ISteamUserStats/GetPlayerAchievements/v1/" +
                  "?appid=$appId&key=${enc(c.apiKey)}&steamid=${enc(c.steamId)}&l=${enc(language)}"
        val what = "the achievements for app $appId"
        return call(url, what).mapCatching { root ->
            val stats = root.optJSONObject("playerstats") ?: throw refused(Refusal.MALFORMED, what)
            PlayerStats(stats.optString("gameName"), parseAchievements(stats.optJSONArray("achievements")))
        }
    }

    internal fun parseAchievements(arr: JSONArray?): List<Achievement> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("apiname").ifEmpty { o.optString("name") }
            if (name.isEmpty()) return@mapNotNull null
            Achievement(
                apiName = name,
                unlocked = o.optInt("achieved") == 1,
                unlockedAt = o.optLong("unlocktime")
            )
        }
    }

    /**
     * The per-app achievement definitions. Identical for every user of that app,
     * and written in [language], which is one of Steam's own names for one.
     *
     * Steam splits a game's achievements over two calls: [playerAchievements]
     * says which are unlocked, this says what they are called and what they
     * look like. [SteamAccountOps] puts the two together and keeps each for as
     * long as it stays true, which for this one is weeks.
     *
     * A game with no achievements is an answer here and not a refusal: a
     * `game` block with nothing in it, and so a schema of none.
     */
    fun schemaFor(apiKey: String, appId: Int, language: String = "english"): Result<Schema> {
        val url = "$BASE/ISteamUserStats/GetSchemaForGame/v2/" +
                  "?key=${enc(apiKey)}&appid=$appId&l=${enc(language)}"
        return call(url, "the achievement schema for app $appId").mapCatching { root ->
            val game = root.optJSONObject("game")
            val arr = game?.optJSONObject("availableGameStats")?.optJSONArray("achievements") ?: JSONArray()
            Schema(game?.optString("gameName").orEmpty(), (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isEmpty()) return@mapNotNull null
                Achievement(
                    apiName = name,
                    unlocked = false,
                    unlockedAt = 0,
                    displayName = o.optString("displayName"),
                    description = o.optString("description"),
                    iconUrl = o.optString("icon"),
                    iconGrayUrl = o.optString("icongray"),
                    hidden = o.optInt("hidden") == 1
                )
            })
        }
    }

    /**
     * How the desktop asks Steam to run a game.
     *
     * A URI handed to the system, not a process this starts: Steam has to be the
     * one launching, or cloud saves, the overlay and playtime tracking all fail
     * quietly. Nothing in this project ever touches a purchase flow.
     */
    fun desktopLaunchUri(appId: Int): String = "steam://rungameid/$appId"

    fun storeUri(appId: Int): String = "steam://store/$appId"

    /** For a log line: the endpoint, never the key. */
    internal fun label(url: String): String = SafeUrl.label(url)
}
