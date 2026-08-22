package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.HttpClient
import com.pegasus.bridge.core.SafeUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * RomM — a self-hosted library server, and the only source here that can be
 * asked "what is this file?" and answer with everything at once.
 *
 * ── Why it earns a place beside ScreenScraper ──────────────
 *
 * Because it is keyed by digest. Measured against the public demo: every ROM it
 * serves carries `md5_hash`, `crc_hash`, `sha1_hash` and — the one that matters
 * here — `ra_hash`, which is *the same rcheevos hash this project already
 * computes*. So a lookup is a digest lookup, not a title match, and the whole
 * class of failure that makes IGN refuse `DuckTales` because it files it under
 * `Disney's DuckTales` does not arise.
 *
 * And it has already done the scraping. One RomM record carries IGDB, MobyGames,
 * ScreenScraper, LaunchBox, Hasheous and RetroAchievements metadata together, so
 * for a user who self-hosts it this is one request against their own server
 * instead of four against other people's quotas.
 *
 * ── The thing that must not be forgotten ───────────────────
 *
 * **RomM's responses carry someone else's credentials.** Measured on the demo:
 *
 *     ss_metadata.box2d_url = https://neoclone.screenscraper.fr/api2/mediaJeu.php
 *       ?devid=…&devpassword=<the RomM instance's own password>&…
 *
 * That is not a hypothetical. Any consumer of this API receives it, and writing
 * one into a cache file or handing it to a theme would put a third party's
 * password on the user's disk. So every URL leaving this client goes through
 * [SafeUrl.redact] first, and media is fetched by the Bridge rather than by
 * whoever it is talking to.
 */
object RommClient {

    /**
     * A Client API Token, or a username and password.
     *
     * The token is the right default: it is created per user under
     * Administration → Client API Tokens, formatted `rmm_` plus 64 hex
     * characters, scope-limited, revocable server-side without touching the
     * Bridge, and it does not expire unless given an expiry. A password would
     * have to be kept in order to refresh a fifteen-minute access token.
     *
     * Both are optional: an instance may serve reads unauthenticated, as the
     * public demo does, and refusing to talk to one because no token was
     * supplied would be inventing a requirement the server does not have.
     */
    data class Credentials(
        val baseUrl: String,
        val token: String = "",
        val user: String = "",
        val password: String = ""
    ) {
        val base: String get() = baseUrl.trimEnd('/')
        val hasToken: Boolean get() = token.isNotBlank()
    }

    /** Why a call produced nothing, kept apart the way every source here must. */
    enum class Refusal {
        NOT_FOUND,      // the server answered, and the answer was no
        AUTH,           // the token is wrong, missing or out of scope
        TRANSPORT,      // never reached the server
        SERVER,         // it answered, badly
        MALFORMED;      // it answered with something this cannot read

        /** True only for a verdict the server actually gave. */
        val isAnswer: Boolean get() = this == NOT_FOUND
    }

    class RommException(message: String, val refusal: Refusal) : Exception(message)

    /**
     * A platform as RomM models it — including the ids every other source here is
     * keyed by, which is what lets a RomM answer cross-check a ScreenScraper one.
     */
    data class Platform(
        val id: Int,
        val slug: String,
        val name: String,
        val romCount: Int,
        val igdbId: Int = 0,
        val screenScraperId: Int = 0,
        val retroAchievementsId: Int = 0
    )

    /**
     * One ROM.
     *
     * [raHash] is the field that makes this source worth having: it is the same
     * rcheevos hash `RomScanPipeline` writes into `rom.hash`, so a local library
     * and a RomM library can be joined without either one guessing at a title.
     *
     * The media fields are *paths on the RomM server*, not absolute URLs, and are
     * deliberately kept that way — turning one into a URL is [mediaUrl]'s job, and
     * it is the only place that knows the base.
     */
    data class Rom(
        val id: Int,
        val name: String,
        val fsName: String,
        val platformSlug: String,
        val platformId: Int,
        val summary: String,
        val md5: String,
        val crc32: String,
        val sha1: String,
        val raHash: String,
        val raId: Int,
        val igdbId: Int,
        val screenScraperId: Int,
        val genres: List<String>,
        val companies: List<String>,
        val releaseDate: Long,
        val coverLargePath: String,
        val coverSmallPath: String,
        val videoPath: String,
        val screenshotPaths: List<String>
    )

    // ── Requests ────────────────────────────────────────────────────────────

    private fun headers(c: Credentials): Map<String, String> =
        if (c.hasToken) mapOf("Authorization" to "Bearer ${c.token}") else emptyMap()

    /**
     * A GET, with the refusal kinds kept apart.
     *
     * 401 and 403 are [Refusal.AUTH] and not a miss, for the reason this codebase
     * has already paid to learn once: a refusal cached as an answer writes a game
     * off permanently.
     */
    internal fun get(c: Credentials, path: String): Result<String> {
        val url = "${c.base}$path"
        val resp = HttpClient.getRaw(url, headers(c), patient = true).getOrElse {
            return Result.failure(RommException(
                "could not reach RomM at ${SafeUrl.label(c.base)}: ${it.message}",
                Refusal.TRANSPORT))
        }
        return when {
            resp.isSuccessful -> Result.success(resp.body)
            resp.code == 401 || resp.code == 403 -> Result.failure(RommException(
                if (c.hasToken) "RomM rejected the API token"
                else "RomM requires a token for this request",
                Refusal.AUTH))
            resp.code == 404 -> Result.failure(RommException("RomM has no such record", Refusal.NOT_FOUND))
            else -> Result.failure(RommException("RomM answered HTTP ${resp.code}", Refusal.SERVER))
        }
    }

    /** Proves the credentials and reports what the server has enabled. */
    fun heartbeat(c: Credentials): Result<JSONObject> =
        get(c, "/api/heartbeat").mapCatching {
            runCatching { JSONObject(it) }.getOrElse {
                throw RommException("RomM's heartbeat was not JSON", Refusal.MALFORMED)
            }
        }

    fun platforms(c: Credentials): Result<List<Platform>> =
        get(c, "/api/platforms").mapCatching { parsePlatforms(it).getOrThrow() }

    internal fun parsePlatforms(body: String): Result<List<Platform>> = runCatching {
        val arr = runCatching { JSONArray(body) }.getOrElse {
            throw RommException("RomM's platform list was not an array", Refusal.MALFORMED)
        }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Platform(
                id = o.optInt("id"),
                slug = o.optString("slug"),
                name = o.optString("name"),
                romCount = o.optInt("rom_count"),
                igdbId = o.optInt("igdb_id"),
                screenScraperId = o.optInt("ss_id"),
                retroAchievementsId = o.optInt("ra_id")
            )
        }
    }

    /**
     * ROMs, paged.
     *
     * The endpoint answers `{items, total, limit, offset}` rather than a bare
     * array — a caller that assumed an array would read a library of nine hundred
     * as nothing at all.
     */
    fun roms(c: Credentials, platformId: Int = 0, limit: Int = 100, offset: Int = 0):
        Result<Page> {
        val q = buildString {
            append("/api/roms?limit=").append(limit).append("&offset=").append(offset)
            if (platformId > 0) append("&platform_id=").append(platformId)
        }
        return get(c, q).mapCatching { parseRoms(it).getOrThrow() }
    }

    data class Page(val items: List<Rom>, val total: Int, val limit: Int, val offset: Int) {
        val hasMore: Boolean get() = offset + items.size < total
    }

    internal fun parseRoms(body: String): Result<Page> = runCatching {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw RommException("RomM's ROM list was not an object", Refusal.MALFORMED)
        }
        val arr = root.optJSONArray("items") ?: JSONArray()
        Page(
            items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseRom) },
            total = root.optInt("total"),
            limit = root.optInt("limit"),
            offset = root.optInt("offset")
        )
    }

    internal fun parseRom(o: JSONObject): Rom {
        val meta = o.optJSONObject("metadatum") ?: JSONObject()
        return Rom(
            id = o.optInt("id"),
            name = o.optString("name").ifEmpty { o.optString("fs_name_no_ext") },
            fsName = o.optString("fs_name"),
            platformSlug = o.optString("platform_slug"),
            platformId = o.optInt("platform_id"),
            summary = o.optString("summary"),
            md5 = o.optString("md5_hash"),
            crc32 = o.optString("crc_hash"),
            sha1 = o.optString("sha1_hash"),
            // The join key. Same algorithm, same value, no title matching.
            raHash = o.optString("ra_hash"),
            raId = o.optInt("ra_id"),
            igdbId = o.optInt("igdb_id"),
            screenScraperId = o.optInt("ss_id"),
            genres = stringList(meta.optJSONArray("genres")),
            companies = stringList(meta.optJSONArray("companies")),
            releaseDate = meta.optLong("first_release_date"),
            coverLargePath = o.optString("path_cover_large"),
            coverSmallPath = o.optString("path_cover_small"),
            videoPath = o.optString("path_video"),
            screenshotPaths = stringList(o.optJSONArray("merged_screenshots"))
        )
    }

    private fun stringList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }

    /**
     * Turns one of RomM's relative media paths into a URL on that server.
     *
     * Only used for paths RomM serves itself. The `ss_metadata` URLs are
     * *ScreenScraper's*, carry the instance's own developer password, and are
     * never fetched or stored — [redactedMetadata] is what a caller gets instead.
     */
    fun mediaUrl(c: Credentials, path: String): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        // The cover fields carry a `?ts=` cache-buster with a space in it, which
        // is not a valid URL until it is encoded.
        return c.base + "/" + path.trimStart('/').replace(" ", "%20")
    }

    /**
     * A RomM metadata blob with every credential-bearing URL redacted.
     *
     * The reason this function exists rather than a comment telling callers to be
     * careful: the field that leaks is `ss_metadata.box2d_url`, it is nested, its
     * name gives no hint, and the password in it belongs to somebody who is not
     * the user. A rule that has to be remembered at each call site is a rule that
     * will be forgotten at one of them.
     */
    fun redactedMetadata(o: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in o.keys()) {
            when (val v = o.get(key)) {
                is JSONObject -> out.put(key, redactedMetadata(v))
                is JSONArray -> out.put(key, redactedArray(v))
                is String -> out.put(key, if (looksLikeUrl(v)) SafeUrl.redact(v) else v)
                else -> out.put(key, v)
            }
        }
        return out
    }

    private fun redactedArray(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            when (val v = arr.get(i)) {
                is JSONObject -> out.put(redactedMetadata(v))
                is JSONArray -> out.put(redactedArray(v))
                is String -> out.put(if (looksLikeUrl(v)) SafeUrl.redact(v) else v)
                else -> out.put(v)
            }
        }
        return out
    }

    private fun looksLikeUrl(s: String) = s.startsWith("http://") || s.startsWith("https://")
}
