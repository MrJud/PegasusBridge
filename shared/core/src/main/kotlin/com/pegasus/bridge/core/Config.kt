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

data class Credentials(
    val ra: RaCreds? = null,
    val steamGridDb: SgdbCreds? = null,
    val igdb: IgdbCreds? = null
)

/**
 * Reads and merges `config/credentials.json`. The theme cannot write files, so
 * every credential reaches disk through here.
 *
 * The `rawg` block the Android version parsed is gone: nothing ever read it.
 */
class Config(private val paths: BridgePaths) {

    fun load(): Credentials {
        val f = paths.credentials
        if (!f.exists()) return Credentials()
        return try {
            val j = JSONObject(f.readText())
            Credentials(
                ra = j.optJSONObject("ra")?.let {
                    RaCreds(it.optString("user"), it.optString("apiKey"))
                },
                steamGridDb = j.optJSONObject("steamGridDb")?.let {
                    SgdbCreds(it.optString("apiKey"))
                },
                igdb = j.optJSONObject("igdb")?.let {
                    IgdbCreds(
                        clientId       = it.optString("clientId"),
                        clientSecret   = it.optString("clientSecret"),
                        cachedToken    = it.optString("cachedToken"),
                        cachedTokenExp = it.optLong("cachedTokenExp")
                    )
                }
            )
        } catch (e: Exception) {
            BridgeLog.w(TAG, "credentials.json unreadable, treating as empty: ${e.message}")
            Credentials()
        }
    }

    /**
     * Merges the supplied credentials into the file. Every argument is optional
     * and a null or blank one leaves its block untouched, so the theme can save
     * one settings field at a time without clobbering the others.
     */
    fun writeCredentials(
        raUser: String? = null,
        raApiKey: String? = null,
        sgdbKey: String? = null,
        igdbClientId: String? = null,
        igdbClientSecret: String? = null
    ) {
        val json = readOrEmpty()

        if (!raUser.isNullOrBlank() || !raApiKey.isNullOrBlank()) {
            val ra = json.optJSONObject("ra") ?: JSONObject()
            raUser?.takeIf   { it.isNotBlank() }?.let { ra.put("user", it) }
            raApiKey?.takeIf { it.isNotBlank() }?.let { ra.put("apiKey", it) }
            json.put("ra", ra)
        }

        sgdbKey?.takeIf { it.isNotBlank() }?.let {
            json.put("steamGridDb", (json.optJSONObject("steamGridDb") ?: JSONObject()).put("apiKey", it))
        }

        if (!igdbClientId.isNullOrBlank() || !igdbClientSecret.isNullOrBlank()) {
            val igdb = json.optJSONObject("igdb") ?: JSONObject()
            igdbClientId?.takeIf     { it.isNotBlank() }?.let { igdb.put("clientId", it) }
            igdbClientSecret?.takeIf { it.isNotBlank() }?.let { igdb.put("clientSecret", it) }
            // The credentials changed, so a cached Twitch token is no longer ours.
            igdb.remove("cachedToken")
            igdb.remove("cachedTokenExp")
            json.put("igdb", igdb)
        }

        persist(json)
    }

    /**
     * Forgets one credential block — what a theme's "log out" has to do now that
     * the Bridge, not the theme, is where credentials live. Writing blanks could
     * not express this: [writeCredentials] deliberately ignores them.
     *
     * Returns false for an unknown block rather than silently doing nothing.
     */
    fun clearBlock(block: String): Boolean {
        if (block !in KNOWN_BLOCKS) return false
        val json = readOrEmpty()
        json.remove(block)
        persist(json)
        BridgeLog.d(TAG, "cleared credential block '$block'")
        return true
    }

    /**
     * Which credentials are configured, and the RA username.
     *
     * Presence only — never the secrets. This is what a theme binds its settings
     * UI to, so it can show "configured" without holding a copy of the value.
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
    }

    /** Caches an OAuth token next to the credentials that obtained it. */
    fun saveToken(source: String, token: String, expiresAt: Long) {
        val json = readOrEmpty()
        val block = json.optJSONObject(source) ?: JSONObject()
        block.put("cachedToken", token)
        block.put("cachedTokenExp", expiresAt)
        json.put(source, block)
        persist(json)
    }

    private fun readOrEmpty(): JSONObject {
        val f = paths.credentials
        val json = if (f.exists()) {
            try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
        if (!json.has("schemaVersion")) json.put("schemaVersion", SchemaVersion.CURRENT)
        return json
    }

    private fun persist(json: JSONObject) {
        json.put("updatedAt", BridgePaths.epochSeconds())
        BridgePaths.writeAtomic(paths.credentials, json.toString(2))
    }

    private companion object {
        const val TAG = "Config"
        val KNOWN_BLOCKS = setOf("ra", "steamGridDb", "igdb")
    }
}
