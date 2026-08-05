package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The RetroAchievements work that used to live inside `RaService`, with the
 * Android Service shell removed: no Intents, no notifications, no lifecycle.
 * The daemon and the Android service both drive this.
 */
class RaSync(
    private val paths: BridgePaths,
    private val config: Config
) {

    sealed class Result {
        data class Ok(val message: String) : Result()
        data class Skipped(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * User summary, completion progress and recently-played, fetched together.
     *
     * The three calls take a few seconds each, so they run in parallel: total
     * wall time is bounded by the slowest rather than their sum.
     */
    suspend fun refreshProfile(user: String): Result = coroutineScope {
        val creds = config.load().ra
            ?: return@coroutineScope Result.Skipped("no RA credentials")
        if (creds.user.isEmpty() || creds.apiKey.isEmpty())
            return@coroutineScope Result.Skipped("empty RA credentials")

        val summaryD    = async(Dispatchers.IO) { RaApiClient.fetchUserSummary(creds.user, creds.apiKey) }
        val completionD = async(Dispatchers.IO) { RaApiClient.fetchCompletion(creds.user, creds.apiKey) }
        val recentD     = async(Dispatchers.IO) { RaApiClient.fetchRecent(creds.user, creds.apiKey) }

        val summary    = summaryD.await()
        val completion = completionD.await()
        val recent     = recentD.await()

        if (summary.length() == 0 && completion.length() == 0 && recent.length() == 0)
            return@coroutineScope Result.Failed("all RA calls returned empty for $user")

        // Guarded per file, not on all three together: a run where only the
        // summary failed used to overwrite a good profile with an empty one,
        // which is what RA throttling during a scan produces.
        val now = BridgePaths.epochSeconds()
        if (summary.length() > 0) {
            BridgePaths.writeAtomic(paths.profile(user), JSONObject()
                .put("schemaVersion",  SchemaVersion.CURRENT)
                .put("fetchedAt",      now)
                .put("summary",        summary)
                .put("recentlyPlayed", recent)
                .toString(2))
        } else {
            BridgeLog.w(TAG, "empty summary — keeping the cached profile for $user")
        }
        if (completion.length() > 0) {
            BridgePaths.writeAtomic(paths.completion(user), JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("fetchedAt",     now)
                .put("data",          completion)
                .toString(2))
        } else {
            BridgeLog.w(TAG, "empty completion — keeping the cached one for $user")
        }

        Result.Ok("profile refreshed for $user")
    }

    /** Per-game progress, merged into the existing metadata under `ra.detail`. */
    fun refreshGameDetail(gameId: Int): Result {
        if (gameId <= 0) return Result.Failed("invalid gameId")
        val creds = config.load().ra ?: return Result.Skipped("no RA credentials")
        if (creds.user.isEmpty() || creds.apiKey.isEmpty())
            return Result.Skipped("empty RA credentials")

        val detail = RaApiClient.fetchGameDetail(gameId, creds.user, creds.apiKey)
        // An empty response means the call failed; keep whatever is already there.
        if (detail.length() == 0) return Result.Failed("empty detail for gameId=$gameId")

        val file = paths.metadata(gameId.toString())
        val meta = if (file.exists()) {
            try { JSONObject(file.readText()) }
            catch (e: Exception) { JSONObject().put("schemaVersion", SchemaVersion.CURRENT) }
        } else {
            JSONObject().put("schemaVersion", SchemaVersion.CURRENT)
        }

        val ra = meta.optJSONObject("ra") ?: JSONObject()
        ra.put("fetchedAt", BridgePaths.epochSeconds())
        ra.put("detail", detail)
        meta.put("ra", ra)

        BridgePaths.writeAtomic(file, meta.toString(2))
        return Result.Ok("detail merged for gameId=$gameId")
    }

    /** Console game list, filtered by `term`. Result written for the theme to read. */
    fun searchGames(consoleId: Int, term: String, jobId: String,
                    onlyWithAchievements: Boolean = false, limit: Int = 200): Result {
        if (consoleId <= 0) {
            writeSearch(jobId, "error", "invalid consoleId", JSONArray())
            return Result.Failed("invalid consoleId")
        }
        val creds = config.load().ra
        if (creds == null || creds.user.isEmpty() || creds.apiKey.isEmpty()) {
            writeSearch(jobId, "error", "no RA credentials", JSONArray())
            return Result.Skipped("no RA credentials")
        }

        val results = RaApiClient.searchGames(consoleId, term, creds.user, creds.apiKey,
                                              limit = limit,
                                              onlyWithAchievements = onlyWithAchievements)
        writeSearch(jobId, if (results.length() == 0) "no_results" else "ok", null, results,
                    consoleId, term)
        BridgeLog.d(TAG, "searchGames consoleId=$consoleId term='$term' hits=${results.length()}")
        return Result.Ok("${results.length()} results")
    }


    /**
     * Answers "which RetroAchievements game is this Pegasus game?".
     *
     * This is the operation the theme actually needs. It used to be assembled in
     * QML out of a fuzzy matcher and a console table that both had to be kept in
     * step with their Kotlin twins by hand; exposing the whole question instead
     * of the pieces is what lets a theme drop that duplicated logic.
     *
     * Order of preference:
     *  1. the scan index — an exact ROM hash match, so no guessing at all;
     *  2. fuzzy match against the console catalogue, ROM filename first.
     */
    fun matchGame(title: String, platformShortName: String, romPath: String? = null,
                  minScore: Double = 0.60): RaMatcher.Match? {
        // 1. The scan index is authoritative wherever it has an answer.
        RaMatcher.fromIndex(readIndex(), title, platformShortName, romPath)?.let { return it }

        // 2. Otherwise guess from the console catalogue.
        val consoleId = RaConsoleMap.consoleId(platformShortName)
        if (consoleId <= 0) return null
        val creds = config.load().ra ?: return null
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) return null

        val catalogue = RaApiClient.fetchGameList(consoleId, creds.user, creds.apiKey,
                                                 onlyWithAchievements = false)
        return RaMatcher.fromCatalogue(catalogue, title, platformShortName, romPath, minScore)
    }

    private fun readIndex(): JSONObject? = try {
        if (paths.discoveryIndex.isFile) JSONObject(paths.discoveryIndex.readText()) else null
    } catch (e: Exception) {
        null
    }

    /**
     * The console table itself, so a theme can label a platform without shipping
     * its own copy of the mapping.
     */
    fun consoleTable(): JSONObject = RaConsoleMap.asJson()

    private fun writeSearch(jobId: String, status: String, error: String?, results: JSONArray,
                            consoleId: Int = 0, term: String = "") {
        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status",  status)
            .put("results", results)
        if (consoleId > 0) payload.put("consoleId", consoleId)
        if (term.isNotEmpty()) payload.put("term", term)
        error?.let { payload.put("error", it) }
        BridgePaths.writeAtomic(paths.searchRa(jobId), payload.toString())
    }

    private companion object {
        const val TAG = "RaSync"
    }
}
