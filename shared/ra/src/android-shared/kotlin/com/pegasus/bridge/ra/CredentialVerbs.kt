package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.SteamAccountCache
import java.io.File

/**
 * Android's two credential verbs, `set-credentials` and `clear-credentials`,
 * as far as they can be said without an intent or a service: which intents
 * may use them, which fields the first takes and what of them is written, and
 * when what is kept of a Steam account is dropped. DataLayerRouter and
 * RaService call this and keep only what needs Android. The desktop daemon
 * has its own `/credentials` and does not come here.
 *
 * No line logged here holds a value. It names the verb, or the blocks a write
 * changed, and no more.
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

    private const val TAG = "CredentialVerbs"

    /**
     * Whether the router may act on a credential [verb] that came with
     * [categories]: not when a browser sent it.
     *
     * The router's filter takes BROWSABLE, so a web page can reach it with an
     * `intent:` URL, and such a URL can carry categories and extras as well as
     * the query. A page that wrote a key or cleared one would be acting on the
     * user's own accounts. A theme's Qt.openUrlExternally never sets the
     * category, and launch-gamenative drops such an intent on the same mark.
     * Dropped is nothing written and nothing cleared, no pending and no done
     * file, and a line that names the verb alone.
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
     */
    fun set(fields: Map<String, String>, cacheDir: File?, write: (Map<String, String>) -> Unit) {
        if (fields.isEmpty()) return
        write(fields)
        val blocks = blocks(fields)
        if ("steam" in blocks) SteamAccountCache.forget(cacheDir)
        BridgeLog.d(TAG, "$SET: blocks updated: $blocks")
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
