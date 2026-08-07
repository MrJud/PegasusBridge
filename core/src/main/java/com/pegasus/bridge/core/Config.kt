package com.pegasus.bridge.core

import org.json.JSONObject
import java.io.File

data class RaCreds(val user: String, val apiKey: String)
data class SgdbCreds(val apiKey: String)
data class IgdbCreds(
    val clientId: String,
    val clientSecret: String,
    val cachedToken: String = "",
    val cachedTokenExp: Long = 0L
)
data class RawgCreds(val apiKey: String)

/**
 * ScreenScraper wants two pairs and they are not interchangeable.
 *
 * [devId] and [devPassword] identify the *application* and are what the API
 * refuses without. [ssid] and [ssPassword] are a member login, optional, and
 * what lifts the request quota off the anonymous floor — they may well name the
 * same account that owns the devid, but the API takes them as separate
 * parameters and so does this.
 *
 * [softname] is the name registered with the devid. ScreenScraper checks it and
 * refuses a mismatch, so it is not decoration and not the user's to invent — it
 * has a default and is stored only if it ever has to differ.
 *
 * **Kept byte-identical to the daemon's copy in shared/core.** The two Config.kt
 * are separate files, not one shared source — only FuzzyMatch.kt lives in
 * android-shared — so a change here that is not mirrored there gives the two
 * platforms different credential stores, silently.
 */
data class ScreenScraperCreds(
    val devId: String,
    val devPassword: String,
    val ssid: String = "",
    val ssPassword: String = "",
    val softname: String = DEFAULT_SOFTNAME
) {
    companion object {
        /** Registered with the devid on 2026-08-07. Not a secret; it travels in every URL. */
        const val DEFAULT_SOFTNAME = "PegasusBridge"
    }
}

data class Credentials(
    val ra: RaCreds? = null,
    val steamGridDb: SgdbCreds? = null,
    val igdb: IgdbCreds? = null,
    val rawg: RawgCreds? = null,
    val screenScraper: ScreenScraperCreds? = null
)

object Config {

    fun load(): Credentials {
        val primary = Paths.CREDENTIALS
        if (primary.exists()) return parse(primary)

        // Fallback: legacy hasher_config.json (RA creds only)
        val legacy = Paths.LEGACY_HASHER
        if (legacy.exists()) return parseLegacy(legacy)

        return Credentials()
    }

    /**
     * Merges the supplied credentials into credentials.json. Every argument is
     * optional and a null one leaves its block untouched, so the theme can save
     * one settings field at a time without clobbering the others.
     *
     * Before this existed nothing ever wrote the steamGridDb or igdb blocks —
     * those keys lived only in the theme's own storage, so every SGDB and IGDB
     * call failed with "missing steamGridDb.apiKey in credentials.json".
     */
    fun writeCredentials(
        raUser: String? = null,
        raApiKey: String? = null,
        sgdbKey: String? = null,
        igdbClientId: String? = null,
        igdbClientSecret: String? = null,
        ssDevId: String? = null,
        ssDevPassword: String? = null,
        ssUser: String? = null,
        ssPassword: String? = null,
        ssSoftname: String? = null
    ) {
        val current = Paths.CREDENTIALS
        val json = if (current.exists()) {
            try { JSONObject(current.readText()) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        if (!json.has("schemaVersion")) json.put("schemaVersion", SchemaVersion.CURRENT)

        if (!raUser.isNullOrEmpty() || !raApiKey.isNullOrEmpty()) {
            val ra = json.optJSONObject("ra") ?: JSONObject()
            raUser?.takeIf   { it.isNotEmpty() }?.let { ra.put("user", it) }
            raApiKey?.takeIf { it.isNotEmpty() }?.let { ra.put("apiKey", it) }
            json.put("ra", ra)
        }

        sgdbKey?.takeIf { it.isNotEmpty() }?.let {
            val sgdb = json.optJSONObject("steamGridDb") ?: JSONObject()
            sgdb.put("apiKey", it)
            json.put("steamGridDb", sgdb)
        }

        if (!igdbClientId.isNullOrEmpty() || !igdbClientSecret.isNullOrEmpty()) {
            val igdb = json.optJSONObject("igdb") ?: JSONObject()
            igdbClientId?.takeIf     { it.isNotEmpty() }?.let { igdb.put("clientId", it) }
            igdbClientSecret?.takeIf { it.isNotEmpty() }?.let { igdb.put("clientSecret", it) }
            // Credentials changed, so any cached Twitch token is no longer ours.
            igdb.remove("cachedToken")
            igdb.remove("cachedTokenExp")
            json.put("igdb", igdb)
        }

        if (!ssDevId.isNullOrEmpty() || !ssDevPassword.isNullOrEmpty()
            || !ssUser.isNullOrEmpty() || !ssPassword.isNullOrEmpty()
            || !ssSoftname.isNullOrEmpty()) {
            val ss = json.optJSONObject("screenScraper") ?: JSONObject()
            ssDevId?.takeIf       { it.isNotEmpty() }?.let { ss.put("devId", it) }
            ssDevPassword?.takeIf { it.isNotEmpty() }?.let { ss.put("devPassword", it) }
            ssUser?.takeIf        { it.isNotEmpty() }?.let { ss.put("ssid", it) }
            ssPassword?.takeIf    { it.isNotEmpty() }?.let { ss.put("ssPassword", it) }
            ssSoftname?.takeIf    { it.isNotEmpty() }?.let { ss.put("softname", it) }
            json.put("screenScraper", ss)
        }

        json.put("updatedAt", System.currentTimeMillis() / 1000L)
        atomicWrite(current, json.toString(2))
    }

    /**
     * Forgets one credential block — what a theme's "log out" has to do now that
     * the Bridge, not the theme, is where credentials live. Writing blanks could
     * not express this: writeCredentials deliberately ignores them.
     */
    fun clearBlock(block: String): Boolean {
        if (block !in KNOWN_BLOCKS) return false
        val current = Paths.CREDENTIALS
        val json = if (current.exists()) JSONObject(current.readText()) else JSONObject()
        json.remove(block)
        json.put("updatedAt", System.currentTimeMillis() / 1000L)
        atomicWrite(current, json.toString(2))
        return true
    }

    /**
     * Which credentials are configured, and the RA username. Presence only —
     * never the secrets. This is what a theme binds its settings UI to.
     */
    fun status(): JSONObject {
        val c = load()
        return JSONObject()
            .put("ra", JSONObject()
                .put("configured", c.ra?.user?.isNotEmpty() == true && c.ra.apiKey.isNotEmpty())
                .put("user", c.ra?.user.orEmpty()))
            .put("steamGridDb", JSONObject()
                .put("configured", c.steamGridDb?.apiKey?.isNotEmpty() == true))
            .put("igdb", JSONObject()
                .put("configured", c.igdb?.clientId?.isNotEmpty() == true
                                && c.igdb.clientSecret.isNotEmpty()))
            // Two flags, because the two pairs mean different things: without the
            // developer pair the API answers nothing at all, while the member login
            // only lifts the quota. Reporting one flag would make a working setup on
            // the anonymous floor look identical to a broken one.
            .put("screenScraper", JSONObject()
                .put("configured", c.screenScraper?.devId?.isNotEmpty() == true
                                && c.screenScraper.devPassword.isNotEmpty())
                .put("hasUser", c.screenScraper?.ssid?.isNotEmpty() == true
                             && c.screenScraper.ssPassword.isNotEmpty())
                .put("user", c.screenScraper?.ssid.orEmpty()))
    }

    private val KNOWN_BLOCKS = setOf("ra", "steamGridDb", "igdb", "screenScraper")

    // Atomic write: write temp file then rename to avoid torn reads during overlap phase
    fun saveToken(source: String, token: String, expiresAt: Long) {
        val current = Paths.CREDENTIALS
        val json = if (current.exists()) JSONObject(current.readText()) else JSONObject()
        if (!json.has("schemaVersion")) json.put("schemaVersion", SchemaVersion.CURRENT)
        val block = json.optJSONObject(source) ?: JSONObject()
        block.put("cachedToken", token)
        block.put("cachedTokenExp", expiresAt)
        json.put(source, block)
        json.put("updatedAt", System.currentTimeMillis() / 1000L)
        atomicWrite(current, json.toString(2))
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(target)
    }

    private fun parse(file: File): Credentials {
        return try {
            val j = JSONObject(file.readText())
            Credentials(
                ra = j.optJSONObject("ra")?.let {
                    RaCreds(it.optString("user"), it.optString("apiKey"))
                },
                steamGridDb = j.optJSONObject("steamGridDb")?.let {
                    SgdbCreds(it.optString("apiKey"))
                },
                igdb = j.optJSONObject("igdb")?.let {
                    IgdbCreds(
                        clientId     = it.optString("clientId"),
                        clientSecret = it.optString("clientSecret"),
                        cachedToken  = it.optString("cachedToken"),
                        cachedTokenExp = it.optLong("cachedTokenExp")
                    )
                },
                rawg = j.optJSONObject("rawg")?.let {
                    val key = it.optString("apiKey")
                    if (key.isNotEmpty()) RawgCreds(key) else null
                },
                screenScraper = j.optJSONObject("screenScraper")?.let {
                    ScreenScraperCreds(
                        devId       = it.optString("devId"),
                        devPassword = it.optString("devPassword"),
                        ssid        = it.optString("ssid"),
                        ssPassword  = it.optString("ssPassword"),
                        // An older file has no softname, and the registered one is
                        // the right answer for every caller that does not override it.
                        softname    = it.optString("softname")
                                        .ifBlank { ScreenScraperCreds.DEFAULT_SOFTNAME }
                    )
                }
            )
        } catch (e: Exception) {
            Credentials()
        }
    }

    private fun parseLegacy(file: File): Credentials {
        return try {
            val j = JSONObject(file.readText())
            Credentials(
                ra = RaCreds(
                    user   = j.optString("ra_user"),
                    apiKey = j.optString("ra_api_key")
                )
            )
        } catch (e: Exception) {
            Credentials()
        }
    }
}
