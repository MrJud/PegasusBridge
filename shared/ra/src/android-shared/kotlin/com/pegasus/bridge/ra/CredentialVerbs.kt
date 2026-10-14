package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.core.SteamAccountCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android's two credential verbs, `set-credentials` and `clear-credentials`,
 * as far as they can be said without an intent or a service: which intents
 * may use them, which fields the first takes and what of them is written, and
 * when what is kept of a Steam account is dropped. DataLayerRouter and
 * RaService call this and keep only what needs Android. The desktop daemon
 * has its own `/credentials` and does not come here.
 *
 * No line logged here, and no answer written, holds a value. It names the
 * verb, or the blocks a write changed, and no more.
 */
object CredentialVerbs {

    const val SET = "set-credentials"
    const val CLEAR = "clear-credentials"

    /** What a browser marks an intent with that a web page sent. */
    const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"

    /**
     * The fields `set-credentials` takes, by their names in the URI, which
     * are also those of the extras the router hands RaService. Each is
     * optional, and an empty one is the same as none.
     */
    val FIELDS = listOf(
        "user", "apiKey",                                                 // ra
        "sgdbKey",                                                        // steamGridDb
        "igdbClientId", "igdbClientSecret",                               // igdb
        "ssDevId", "ssDevPassword", "ssUser", "ssPassword", "ssSoftname", // screenScraper
        "steamApiKey", "steamId")                                         // steam

    /** What an answer says when the write did not take. It names no field and holds no value. */
    const val WRITE_FAILED = "the credentials could not be written"

    /** What it says when a request had nothing that could be written, as the daemon says it. */
    const val NOTHING_TO_WRITE = "no credential fields supplied"

    private const val TAG = "CredentialVerbs"

    /**
     * Whether the router may act on a credential [verb] that came with
     * [categories]: not when a browser sent it.
     *
     * A browser marks every intent it sends for a page, an `intent:` URL's
     * too, with BROWSABLE, and such a URL can carry categories and extras as
     * well as the query. The router's filter does not take that category,
     * so such an intent matches nothing there; this is the second line, for
     * one that reaches the router all the same. A page that wrote a key or
     * cleared one would be acting on the user's own accounts. A theme's
     * Qt.openUrlExternally never sets the category, and launch-gamenative
     * drops such an intent on the same mark. Dropped is nothing written and
     * nothing cleared, no pending and no done file, and a line that names
     * the verb alone.
     */
    fun admits(verb: String, categories: Set<String>?): Boolean {
        if (categories == null || CATEGORY_BROWSABLE !in categories) return true
        BridgeLog.w(TAG, "$verb: dropped, it came through a browser")
        return false
    }

    /**
     * What of a `set-credentials` request is written: each field of [FIELDS]
     * that [read] gives and that is not empty, less a `steamId` that is not
     * seventeen digits. [read] is the URI's query in the router and the
     * intent's extras in RaService, so both hold the request to one rule.
     *
     * Empty when nothing is left, and the router then answers as it answers a
     * request that carried nothing: an error in `pending/` and a done marker.
     */
    fun fields(read: (String) -> String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in FIELDS) {
            val value = read(name)
            if (value.isNullOrEmpty()) continue
            if (name == "steamId" && !SteamAccountCache.isSteamId(value)) continue
            out[name] = value
        }
        return out
    }

    /** The blocks of credentials.json a write of [fields] changes, by name. */
    fun blocks(fields: Map<String, String>): List<String> = listOfNotNull(
        "ra".takeIf { "user" in fields || "apiKey" in fields },
        "steamGridDb".takeIf { "sgdbKey" in fields },
        "igdb".takeIf { "igdbClientId" in fields || "igdbClientSecret" in fields },
        "screenScraper".takeIf {
            listOf("ssDevId", "ssDevPassword", "ssUser", "ssPassword", "ssSoftname").any { it in fields }
        },
        "steam".takeIf { "steamApiKey" in fields || "steamId" in fields })

    /**
     * Writes [fields] with [write]; then, when the steam block was among
     * them, drops what is kept of every account under [cacheDir], the
     * folder SteamAccountCache.DIR of the data root's cache. Those files are
     * named by the SteamID and answered from for ten minutes without Steam
     * being asked, so a key typed over another would go on being answered
     * for by the old one. The daemon's `/credentials` does the same.
     *
     * Then answers at [answer], `credentials/{jobId}.json`, where the theme's
     * file transport reads a credentials job, as the daemon's `/credentials`
     * answers over HTTP: `{schemaVersion, status: "ok", updated}`, the blocks
     * written. When [write] threw, `status: "error"` and [WRITE_FAILED]; when
     * there was nothing to write, [NOTHING_TO_WRITE]. Neither the answer nor
     * the line logged holds a value, nor the exception's message: org.json
     * puts the text it could not read in its own.
     *
     * Returns whether the write took, so that RaService does not mark a job
     * done as one that did.
     */
    fun set(fields: Map<String, String>, cacheDir: File?, answer: File,
            write: (Map<String, String>) -> Unit): Boolean {
        if (fields.isEmpty()) {
            writeAnswer(answer, error = NOTHING_TO_WRITE)
            return false
        }
        try {
            write(fields)
        } catch (e: Exception) {
            BridgeLog.w(TAG, "$SET: the write failed (${e.javaClass.simpleName})")
            writeAnswer(answer, error = WRITE_FAILED)
            return false
        }
        val blocks = blocks(fields)
        if ("steam" in blocks) SteamAccountCache.forget(cacheDir)
        writeAnswer(answer, updated = blocks)
        BridgeLog.d(TAG, "$SET: blocks updated: $blocks")
        return true
    }

    /**
     * Writes a `set-credentials` answer at [file]: `status: "ok"` and the
     * [updated] blocks, or `status: "error"` and [error], which is one of
     * the sentences above and never a value.
     */
    fun writeAnswer(file: File, updated: List<String> = emptyList(), error: String? = null) {
        val json = JSONObject().put("schemaVersion", SchemaVersion.CURRENT)
        if (error == null) json.put("status", "ok").put("updated", JSONArray(updated))
        else json.put("status", "error").put("error", error)
        BridgePaths.writeAtomic(file, json.toString())
    }

    /**
     * Clears [block] with [clear], which says whether it was one that can be
     * cleared, and when it was the steam block drops what is kept of every
     * account under [cacheDir]: an account that was cleared leaves no library
     * of its games behind. Returns what [clear] returned.
     */
    fun clear(block: String, cacheDir: File?, clear: (String) -> Boolean): Boolean {
        val ok = clear(block)
        if (ok && block == "steam") SteamAccountCache.forget(cacheDir)
        // The block named only when it is one: an unknown one is whatever the URI held.
        BridgeLog.d(TAG, if (ok) "$CLEAR: '$block' cleared" else "$CLEAR: not a block that can be cleared")
        return ok
    }
}
