package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.HttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A remote control for a Spotify client the user is already running.
 *
 * Nothing here streams, decodes or plays audio. The Bridge sends a command to
 * Spotify's Web API, Spotify's own client obeys it, and the theme's only part is
 * a button. That is the whole scope, and keeping it that narrow is what makes it
 * a small feature rather than a media pipeline.
 *
 * ── Premium, or nothing ────────────────────────────────────
 *
 * `PUT /v1/me/player/play` and every other transport endpoint are documented as
 * Premium-only, and a free account is refused with **403**. Reading what is
 * playing is not restricted, so a free account can be shown the current track
 * and can do nothing about it. A settings screen should say so *before* asking
 * anyone to authorise, not after the first 403.
 *
 * ── Why PKCE, and why 127.0.0.1 ────────────────────────────
 *
 * A locally installed daemon cannot keep a client secret — anyone with the
 * machine has it — so the Authorization Code flow with PKCE is the only correct
 * choice. And since April 2025 Spotify validates redirect URIs strictly: a
 * loopback **IP literal** may use plain HTTP, while `http://localhost:PORT` is
 * refused outright. The daemon already binds 127.0.0.1, so the callback costs
 * one route rather than an embedded browser.
 */
object SpotifyRemote {

    internal var AUTH_BASE = "https://accounts.spotify.com"
    internal var API_BASE = "https://api.spotify.com/v1"

    /**
     * The least that will do.
     *
     * `user-read-playback-state` and `user-read-currently-playing` to see, and
     * `user-modify-playback-state` to act. Nothing about the library, nothing
     * about the user's identity, nothing about their playlists — asking for a
     * scope in case it is useful later is asking a user to grant something for a
     * reason that does not exist yet.
     */
    val SCOPES = listOf(
        "user-read-playback-state",
        "user-read-currently-playing",
        "user-modify-playback-state"
    )

    enum class Refusal {
        /** 403 on a transport call: almost always a free account. */
        NEEDS_PREMIUM,
        /** 404 from the player: nothing is running for Spotify to control. */
        NO_ACTIVE_DEVICE,
        AUTH,
        RATE_LIMIT,
        TRANSPORT,
        SERVER,
        MALFORMED
    }

    class SpotifyException(message: String, val refusal: Refusal) : Exception(message)

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long
    ) {
        /** A minute of slack, so a call is never made with a token about to die. */
        fun isValid(now: Long = System.currentTimeMillis() / 1000L) = now < expiresAt - 60
    }

    data class NowPlaying(
        val isPlaying: Boolean,
        val track: String,
        val artist: String,
        val album: String,
        val artUrl: String,
        val progressMs: Long,
        val durationMs: Long,
        val deviceName: String
    )

    // ── PKCE ────────────────────────────────────────────────────────────────

    /** The verifier and the challenge derived from it. */
    data class Pkce(val verifier: String, val challenge: String)

    /**
     * A fresh verifier and its S256 challenge.
     *
     * 64 random bytes, base64url without padding — comfortably inside the spec's
     * 43-to-128-character window. `SecureRandom` and not `Random`: the verifier is
     * the only thing stopping an authorisation code intercepted on this machine
     * from being exchanged by something else on it.
     */
    fun newPkce(random: SecureRandom = SecureRandom()): Pkce {
        val bytes = ByteArray(64).also(random::nextBytes)
        val verifier = b64url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, b64url(digest))
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * The URL to open in the user's browser.
     *
     * [redirectUri] must be the loopback IP literal registered with the app —
     * `http://127.0.0.1:<port>/spotify/callback`. `localhost` is refused by
     * Spotify and would fail at the authorise step with a message that does not
     * mention the reason.
     */
    fun authorizeUrl(clientId: String, redirectUri: String, pkce: Pkce, state: String): String =
        "$AUTH_BASE/authorize" +
        "?client_id=${enc(clientId)}" +
        "&response_type=code" +
        "&redirect_uri=${enc(redirectUri)}" +
        "&code_challenge_method=S256" +
        "&code_challenge=${enc(pkce.challenge)}" +
        "&state=${enc(state)}" +
        "&scope=${enc(SCOPES.joinToString(" "))}"

    /** Swaps the authorisation code for tokens. No client secret is involved. */
    fun exchangeCode(
        clientId: String, code: String, redirectUri: String, verifier: String
    ): Result<Tokens> {
        val body = "grant_type=authorization_code" +
                   "&code=${enc(code)}" +
                   "&redirect_uri=${enc(redirectUri)}" +
                   "&client_id=${enc(clientId)}" +
                   "&code_verifier=${enc(verifier)}"
        return postForm("$AUTH_BASE/api/token", body).mapCatching { parseTokens(it, "") }
    }

    fun refresh(clientId: String, refreshToken: String): Result<Tokens> {
        val body = "grant_type=refresh_token" +
                   "&refresh_token=${enc(refreshToken)}" +
                   "&client_id=${enc(clientId)}"
        // Spotify may or may not issue a new refresh token; when it does not, the
        // old one stays valid and dropping it would log the user out on the next
        // expiry for no reason.
        return postForm("$AUTH_BASE/api/token", body).mapCatching { parseTokens(it, refreshToken) }
    }

    internal fun parseTokens(body: String, fallbackRefresh: String): Tokens {
        val j = runCatching { JSONObject(body) }.getOrElse {
            throw SpotifyException("Spotify's token response was not JSON", Refusal.MALFORMED)
        }
        j.optString("error").takeIf { it.isNotEmpty() }?.let {
            throw SpotifyException("Spotify refused the token request: $it", Refusal.AUTH)
        }
        val access = j.optString("access_token")
        if (access.isEmpty()) throw SpotifyException("no access token in the response", Refusal.MALFORMED)
        return Tokens(
            accessToken = access,
            refreshToken = j.optString("refresh_token").ifEmpty { fallbackRefresh },
            expiresAt = System.currentTimeMillis() / 1000L + j.optLong("expires_in", 3600)
        )
    }

    private fun postForm(url: String, body: String): Result<String> =
        HttpClient.post(url, body, "application/x-www-form-urlencoded")
            .recoverCatching { throw SpotifyException(
                "could not reach Spotify: ${it.message}", Refusal.TRANSPORT) }

    // ── Control ─────────────────────────────────────────────────────────────

    /** What is playing, or null when nothing is. Works on a free account. */
    fun nowPlaying(token: String): Result<NowPlaying?> {
        val resp = HttpClient.getRaw("$API_BASE/me/player", auth(token)).getOrElse {
            return Result.failure(SpotifyException(
                "could not reach Spotify: ${it.message}", Refusal.TRANSPORT))
        }
        // 204: nothing is playing. An empty body, and an answer rather than a fault.
        if (resp.code == 204 || resp.body.isBlank()) return Result.success(null)
        if (resp.code == 401) return Result.failure(
            SpotifyException("the Spotify token has expired", Refusal.AUTH))
        if (!resp.isSuccessful) return Result.failure(
            SpotifyException("Spotify answered HTTP ${resp.code}", Refusal.SERVER))

        return runCatching {
            val j = JSONObject(resp.body)
            val item = j.optJSONObject("item")
            val artists = item?.optJSONArray("artists")
            NowPlaying(
                isPlaying = j.optBoolean("is_playing"),
                track = item?.optString("name").orEmpty(),
                artist = (0 until (artists?.length() ?: 0))
                    .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                    .joinToString(", "),
                album = item?.optJSONObject("album")?.optString("name").orEmpty(),
                artUrl = item?.optJSONObject("album")?.optJSONArray("images")
                    ?.optJSONObject(0)?.optString("url").orEmpty(),
                progressMs = j.optLong("progress_ms"),
                durationMs = item?.optLong("duration_ms") ?: 0,
                deviceName = j.optJSONObject("device")?.optString("name").orEmpty()
            )
        }.recoverCatching {
            throw SpotifyException("could not read Spotify's player state", Refusal.MALFORMED)
        }
    }

    enum class Command(val path: String, val method: String) {
        PLAY("/me/player/play", "PUT"),
        PAUSE("/me/player/pause", "PUT"),
        NEXT("/me/player/next", "POST"),
        PREVIOUS("/me/player/previous", "POST")
    }

    /**
     * Sends one transport command.
     *
     * The 403 is the case worth handling carefully: it is what a free account
     * gets, and reporting it as a generic failure would send the user looking for
     * a network problem instead of at their subscription. The 404 is the other —
     * Spotify has no device to command, which means the client is not running.
     */
    fun control(token: String, command: Command, deviceId: String = ""): Result<Unit> {
        val url = API_BASE + command.path + if (deviceId.isNotEmpty()) "?device_id=${enc(deviceId)}" else ""
        val resp = HttpClient.send(url, command.method, "", auth(token)).getOrElse {
            return Result.failure(SpotifyException(
                "could not reach Spotify: ${it.message}", Refusal.TRANSPORT))
        }
        return when {
            resp.code in 200..299 -> Result.success(Unit)
            resp.code == 401 -> Result.failure(
                SpotifyException("the Spotify token has expired", Refusal.AUTH))
            resp.code == 403 -> Result.failure(SpotifyException(
                "Spotify allows playback control on Premium accounts only",
                Refusal.NEEDS_PREMIUM))
            resp.code == 404 -> Result.failure(SpotifyException(
                "no active Spotify device — open Spotify and play something first",
                Refusal.NO_ACTIVE_DEVICE))
            resp.code == 429 -> Result.failure(
                SpotifyException("Spotify is rate-limiting this client", Refusal.RATE_LIMIT))
            else -> Result.failure(
                SpotifyException("Spotify answered HTTP ${resp.code}", Refusal.SERVER))
        }
    }

    /**
     * Pauses around a game launch, and resumes after — but only if it was the one
     * that paused.
     *
     * The bookkeeping is the point. Resuming unconditionally would start music a
     * user had deliberately stopped before launching, and doing that once is
     * enough for the feature to be turned off for good.
     */
    class LaunchPause(private val token: String) {
        private var pausedByUs = false

        fun onGameStart(): Boolean {
            val playing = nowPlaying(token).getOrNull()?.isPlaying == true
            if (!playing) return false
            pausedByUs = control(token, Command.PAUSE).isSuccess
            return pausedByUs
        }

        fun onGameExit(): Boolean {
            if (!pausedByUs) return false
            pausedByUs = false
            return control(token, Command.PLAY).isSuccess
        }
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
