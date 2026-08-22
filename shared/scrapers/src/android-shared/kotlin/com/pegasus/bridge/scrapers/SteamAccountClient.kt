package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.HttpClient
import com.pegasus.bridge.core.SafeUrl
import org.json.JSONArray
import org.json.JSONObject

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
 * ── And the one that arrives as HTML ───────────────────────
 *
 * Measured: `GetPlayerAchievements` without a key answers **HTTP 400 with an
 * HTML body**, not JSON. Every parse here therefore treats "this is not JSON" as
 * a refusal rather than as a broken response — the same lesson ScreenScraper's
 * plain-text login errors already taught this codebase.
 */
object SteamAccountClient {

    internal var BASE = "https://api.steampowered.com"

    /**
     * [apiKey] is the user's own, from steamcommunity.com/dev/apikey.
     * [steamId] is the 64-bit id; [resolveVanity] turns a profile name into one.
     */
    data class Credentials(val apiKey: String, val steamId: String)

    enum class Refusal {
        /** The profile is private, or its game details are. Not an error. */
        PRIVATE,
        /** The key is missing, wrong, or rate-limited. */
        AUTH,
        /** The account does not own the game, or it has no achievements at all. */
        NOT_FOUND,
        TRANSPORT,
        SERVER,
        MALFORMED;

        val isAnswer: Boolean get() = this == NOT_FOUND || this == PRIVATE
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
     * One game's progress.
     *
     * [total] and [unlocked] are Steam's and Steam's alone. They must never be
     * added to a RetroAchievements total: two accounts, two catalogues, two sets
     * of games, and a combined number would describe nothing that exists.
     */
    data class Progress(
        val appId: Int,
        val gameName: String,
        val unlocked: Int,
        val total: Int,
        val achievements: List<Achievement>
    ) {
        val fraction: Double get() = if (total <= 0) 0.0 else unlocked.toDouble() / total
    }

    // ── Requests ────────────────────────────────────────────────────────────

    private fun call(url: String, what: String): Result<JSONObject> {
        val resp = HttpClient.getRaw(url, patient = true).getOrElse {
            return Result.failure(SteamException(
                "could not reach Steam for $what: ${it.message}", Refusal.TRANSPORT))
        }
        // The body is HTML on a bad request, so status is checked before parsing:
        // handing `<html>Bad Request</html>` to a JSON parser produces an
        // exception whose message sends the reader entirely the wrong way.
        if (resp.code == 401 || resp.code == 403)
            return Result.failure(SteamException("Steam refused the API key", Refusal.AUTH))
        if (resp.code == 400)
            return Result.failure(SteamException(
                "Steam rejected the request for $what — usually a missing or malformed key",
                Refusal.AUTH))
        if (!resp.isSuccessful)
            return Result.failure(SteamException("Steam answered HTTP ${resp.code}", Refusal.SERVER))

        return runCatching { JSONObject(resp.body) }.recoverCatching {
            throw SteamException("Steam's answer for $what was not JSON", Refusal.MALFORMED)
        }
    }

    /** A profile name to a 64-bit id. */
    fun resolveVanity(apiKey: String, vanityName: String): Result<String> {
        val url = "$BASE/ISteamUser/ResolveVanityURL/v1/?key=$apiKey&vanityurl=$vanityName"
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
        val url = "$BASE/IPlayerService/GetOwnedGames/v1/?key=${c.apiKey}&steamid=${c.steamId}" +
                  "&include_appinfo=1&include_played_free_games=1&format=json"
        return call(url, "the owned games").mapCatching { root ->
            val r = root.optJSONObject("response")
                ?: throw SteamException("no response block", Refusal.MALFORMED)
            // An empty object — not an empty list — is what a private profile
            // returns. Saying "you own nothing" would be a wrong answer with no
            // hint about the setting that caused it.
            if (!r.has("games") && !r.has("game_count"))
                throw SteamException(
                    "this Steam profile's game details are private; " +
                    "set Game details to Public to sync the library", Refusal.PRIVATE)
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
     * One game's achievements, with names and pictures.
     *
     * Two calls, because Steam splits them: `GetPlayerAchievements` says which
     * are unlocked, `GetSchemaForGame` says what they are called and what they
     * look like. The schema is the same for every user, so a caller that syncs a
     * library should cache it per app rather than per player.
     *
     * The schema call is allowed to fail without taking the result with it: knowing
     * that 12 of 40 are unlocked is useful even when their names could not be fetched.
     */
    fun achievements(c: Credentials, appId: Int, includeSchema: Boolean = true): Result<Progress> {
        val url = "$BASE/ISteamUserStats/GetPlayerAchievements/v1/" +
                  "?appid=$appId&key=${c.apiKey}&steamid=${c.steamId}&l=english"
        return call(url, "the achievements for app $appId").mapCatching { root ->
            val stats = root.optJSONObject("playerstats")
                ?: throw SteamException("no playerstats block", Refusal.MALFORMED)

            if (!stats.optBoolean("success", true)) {
                val message = stats.optString("error")
                throw SteamException(
                    when {
                        message.contains("Profile is not public", true) ->
                            "this Steam profile is private; set it to Public to see achievements"
                        message.contains("stats", true) ->
                            "this game reports no achievements for this account"
                        else -> message.ifEmpty { "Steam declined to answer about app $appId" }
                    },
                    if (message.contains("private", true)) Refusal.PRIVATE else Refusal.NOT_FOUND)
            }

            val unlocked = parseAchievements(stats.optJSONArray("achievements"))
            val schema = if (includeSchema) schemaFor(c.apiKey, appId).getOrNull() else null
            val merged = if (schema == null) unlocked else unlocked.map { a ->
                schema[a.apiName]?.let {
                    a.copy(displayName = it.displayName, description = it.description,
                           iconUrl = it.iconUrl, iconGrayUrl = it.iconGrayUrl, hidden = it.hidden)
                } ?: a
            }
            Progress(
                appId = appId,
                gameName = stats.optString("gameName"),
                unlocked = merged.count { it.unlocked },
                total = merged.size,
                achievements = merged
            )
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

    /** The per-app achievement definitions. Identical for every user of that app. */
    fun schemaFor(apiKey: String, appId: Int): Result<Map<String, Achievement>> {
        val url = "$BASE/ISteamUserStats/GetSchemaForGame/v2/?key=$apiKey&appid=$appId&l=english"
        return call(url, "the achievement schema for app $appId").mapCatching { root ->
            val arr = root.optJSONObject("game")
                ?.optJSONObject("availableGameStats")
                ?.optJSONArray("achievements") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isEmpty()) return@mapNotNull null
                name to Achievement(
                    apiName = name,
                    unlocked = false,
                    unlockedAt = 0,
                    displayName = o.optString("displayName"),
                    description = o.optString("description"),
                    iconUrl = o.optString("icon"),
                    iconGrayUrl = o.optString("icongray"),
                    hidden = o.optInt("hidden") == 1
                )
            }.toMap()
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
