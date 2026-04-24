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

data class Credentials(
    val ra: RaCreds? = null,
    val steamGridDb: SgdbCreds? = null,
    val igdb: IgdbCreds? = null,
    val rawg: RawgCreds? = null
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
