package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.HttpClient
import com.pegasus.bridge.core.ScreenScraperCreds
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ScreenScraper — the only source here that identifies a game by what the file *is*
 * rather than by what it is called.
 *
 * Every other scraper is asked "which game is named this?" and the answer has to be
 * matched by title, which is where a retro library loses games: IGN calls DuckTales
 * "Disney's DuckTales" and an unattended rule rightly refuses it. libretro improved on
 * that by matching the ROM's *file name*. This one matches the file's **hash**, which
 * survives a rename.
 *
 * ── Two credential pairs, and they are not interchangeable ──
 *
 * `devid`/`devpassword` identify the application; the API refuses every request without
 * them. `ssid`/`sspassword` are a member login and only lift the quota off the anonymous
 * floor. `softname` is checked against the name registered with the devid and a mismatch
 * is refused, so it is not decoration — [Config] defaults it to the registered value.
 *
 * ── What identifies a game, per system ─────────────────────
 *
 * For cartridge and disc systems the hashes do it: `md5`, `crc`, `sha1`, plus the file
 * size. [com.pegasus.bridge.hasher.HashResult] already carries `fileMd5` and
 * `fileCrc32` — and note those describe the ROM *inside* an archive, not the archive,
 * which is right here and wrong for MAME.
 *
 * For **arcade**, ScreenScraper is keyed by the MAME romset short name, so the identity
 * is `romnom=pacman.zip` and the hashes must not be sent: they would describe the
 * extracted ROM and match nothing. This is why arcade works here where libretro cannot —
 * libretro's MAME repository is keyed by MAME *descriptions*, which no ROM set carries.
 */
object ScreenScraperClient {

    // `internal var` rather than const so the tests can point it at a MockWebServer and
    // exercise the parsers without a network or a quota.
    internal var BASE = "https://api.screenscraper.fr/api2"

    /** What the API says about the account, which is also the only way to prove the credentials. */
    data class Quota(
        val user: String,
        val maxThreads: Int,
        val requestsToday: Int,
        val maxRequestsPerDay: Int,
        val maxRequestsPerMinute: Int
    )

    data class Media(val type: String, val region: String, val url: String)

    data class Game(
        val id: String,
        val title: String,
        val publisher: String,
        val developer: String,
        val players: String,
        val releaseYear: String,
        val genres: List<String>,
        val description: String,
        val rating: String,
        val media: List<Media>
    )

    /**
     * The credential parameters every call carries.
     *
     * The member login is appended **only when both halves are present**: sending an
     * `ssid` with no `sspassword` is not a smaller request, it is a rejected one.
     */
    private fun authParams(c: ScreenScraperCreds): String {
        val sb = StringBuilder()
        sb.append("devid=").append(enc(c.devId))
        sb.append("&devpassword=").append(enc(c.devPassword))
        sb.append("&softname=").append(enc(c.softname))
        sb.append("&output=json")
        if (c.ssid.isNotEmpty() && c.ssPassword.isNotEmpty()) {
            sb.append("&ssid=").append(enc(c.ssid))
            sb.append("&sspassword=").append(enc(c.ssPassword))
        }
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun creds(config: Config): Result<ScreenScraperCreds> {
        val c = config.load().screenScraper
        return when {
            c == null || c.devId.isEmpty() || c.devPassword.isEmpty() ->
                Result.failure(Exception("missing screenScraper.devId/devPassword in credentials.json"))
            else -> Result.success(c)
        }
    }

    /**
     * Verifies the credentials and reports the quota.
     *
     * This is the cheapest call the API has and the only one whose failure means
     * "your credentials are wrong" rather than "that game is not in the database", so
     * it is what a settings screen should use to prove a login — the same job
     * `refreshRaProfile` does for RetroAchievements.
     */
    fun userInfo(config: Config): Result<Quota> {
        val c = creds(config).getOrElse { return Result.failure(it) }
        val url = "$BASE/ssuserInfos.php?" + authParams(c)
        val body = HttpClient.get(url).getOrElse { return Result.failure(it) }
        return parseUserInfo(body)
    }

    internal fun parseUserInfo(body: String): Result<Quota> {
        // ScreenScraper answers a bad login with plain text, not JSON — so a parse
        // failure here is a credential failure, and saying "unreadable response" would
        // send the reader looking for a network problem that is not there.
        val root = try { JSONObject(body) } catch (e: Exception) {
            return Result.failure(Exception(explainPlainText(body)))
        }
        val ssuser = root.optJSONObject("response")?.optJSONObject("ssuser")
            ?: return Result.failure(Exception(explainPlainText(body)))
        return Result.success(
            Quota(
                user                 = ssuser.optString("id"),
                maxThreads           = ssuser.optString("maxthreads").toIntOrNull() ?: 1,
                requestsToday        = ssuser.optString("requeststoday").toIntOrNull() ?: 0,
                maxRequestsPerDay    = ssuser.optString("maxrequestsperday").toIntOrNull() ?: 0,
                maxRequestsPerMinute = ssuser.optString("maxrequestspermin").toIntOrNull() ?: 0
            )
        )
    }

    /**
     * Turns the API's plain-text refusals into something a settings screen can show.
     *
     * These are the ones worth naming, because each sends the reader somewhere
     * different: a wrong devid is a typo, a closed API is nothing to do with the user,
     * and a wrong member login still leaves the developer pair working.
     */
    internal fun explainPlainText(body: String): String {
        val t = body.trim().take(300)
        val lower = t.lowercase()
        return when {
            lower.contains("erreur de login") || lower.contains("error de login")
                || lower.contains("login error") ->
                "ScreenScraper rejected the login — check which password belongs to the developer pair"
            lower.contains("softname") ->
                "ScreenScraper rejected the softname — it must match the one registered with the devid"
            lower.contains("api totalement fermé") || lower.contains("api closed")
                || lower.contains("fermé") ->
                "ScreenScraper has closed the API for now — not a credential problem"
            lower.contains("maximum threads") || lower.contains("quota") ->
                "ScreenScraper quota reached"
            t.isEmpty() -> "ScreenScraper returned nothing"
            else -> "ScreenScraper: $t"
        }
    }
}
