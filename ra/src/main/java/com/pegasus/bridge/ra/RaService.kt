package com.pegasus.bridge.ra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class RaService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Syncing RetroAchievements…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verb  = intent?.getStringExtra(EXTRA_VERB)  ?: run { stopSelf(startId); return START_NOT_STICKY }
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            Paths.ensureAll()
            try {
                when (verb) {
                    VERB_PROFILE          -> handleProfile(intent, jobId)
                    VERB_DETAIL           -> handleDetail(intent, jobId)
                    VERB_SEARCH_GAMES     -> handleSearchGames(intent, jobId)
                    VERB_SET_CREDENTIALS  -> handleSetCredentials(intent, jobId)
                    VERB_MATCH            -> handleMatch(intent, jobId)
                    VERB_CONSOLES         -> handleConsoles(jobId)
                    VERB_CRED_STATUS      -> handleCredentialsStatus(jobId)
                    VERB_CRED_CLEAR       -> handleClearCredentials(intent, jobId)
                    else                  -> Log.w(TAG, "Unknown verb: $verb")
                }
            } catch (e: Exception) {
                Log.e(TAG, "RaService error for verb=$verb", e)
            } finally {
                Paths.markDone(jobId)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private suspend fun handleProfile(intent: Intent, jobId: String) = coroutineScope {
        val user = intent.getStringExtra(EXTRA_USER) ?: return@coroutineScope
        val creds = Config.load().ra ?: return@coroutineScope
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) return@coroutineScope

        val now = System.currentTimeMillis() / 1000L

        // Run all 3 API calls in parallel — each takes 2-5s, so parallel cuts
        // total wall time from ~9-15s to ~3-5s (bounded by the slowest call).
        val summaryDeferred      = async(Dispatchers.IO) { RaApiClient.fetchUserSummary(creds.user, creds.apiKey) }
        val completionDeferred   = async(Dispatchers.IO) { RaApiClient.fetchCompletion(creds.user, creds.apiKey) }
        val recentPlayedDeferred = async(Dispatchers.IO) { RaApiClient.fetchRecent(creds.user, creds.apiKey) }

        val summary      = summaryDeferred.await()
        val completion   = completionDeferred.await()
        val recentPlayed = recentPlayedDeferred.await()

        // Each file is guarded on its own data, not on all three together.
        // The old all-or-nothing check let a run where only the summary failed
        // overwrite a good profile with an empty one — which is exactly what
        // happens when RA throttles mid-scan, and it cost the user their avatar
        // and points with no error shown anywhere.
        if (summary.length() > 0) {
            Paths.profile(user).writeText(JSONObject()
                .put("schemaVersion",  SchemaVersion.CURRENT)
                .put("fetchedAt",      now)
                .put("summary",        summary)
                .put("recentlyPlayed", recentPlayed)
                .toString(2))
        } else {
            Log.w(TAG, "Profile job: empty summary — keeping the cached profile for $user")
        }

        if (completion.length() > 0) {
            Paths.completion(user).writeText(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("fetchedAt",     now)
                .put("data",          completion)
                .toString(2))
        } else {
            Log.w(TAG, "Profile job: empty completion — keeping the cached one for $user")
        }

        Log.d(TAG, "Profile job done for user=$user")
    }

    private fun handleDetail(intent: Intent, jobId: String) {
        val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1).takeIf { it > 0 } ?: return
        val creds  = Config.load().ra ?: return
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) return

        val now    = System.currentTimeMillis() / 1000L
        val detail = RaApiClient.fetchGameDetail(gameId, creds.user, creds.apiKey)

        // Skip merge se la chiamata è tornata vuota (errore network/auth) — evita di
        // sovrascrivere un detail valido con uno vuoto.
        if (detail.length() == 0) {
            Log.w(TAG, "Detail job: empty response — skipping merge for gameId=$gameId")
            return
        }

        // Merge into metadata/{gameId}.json — read existing, upsert ra.detail
        val metaFile = Paths.metadata(gameId.toString())
        val meta = if (metaFile.exists()) {
            try { JSONObject(metaFile.readText()) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject().put("schemaVersion", SchemaVersion.CURRENT)
        }

        val raBlock = meta.optJSONObject("ra") ?: JSONObject()
        raBlock.put("fetchedAt", now)
        raBlock.put("detail",    detail)
        meta.put("ra", raBlock)

        metaFile.writeText(meta.toString(2))
        Log.d(TAG, "Detail job done for gameId=$gameId")
    }

    private fun handleSearchGames(intent: Intent, jobId: String) {
        val consoleId = intent.getIntExtra(EXTRA_CONSOLE_ID, -1).takeIf { it > 0 }
            ?: run { writeSearchError(jobId, "invalid consoleId"); return }
        val term = intent.getStringExtra(EXTRA_TERM).orEmpty()
        val creds = Config.load().ra ?: run {
            writeSearchError(jobId, "no credentials"); return
        }
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) {
            writeSearchError(jobId, "empty credentials"); return
        }

        val withAch = intent.getBooleanExtra(EXTRA_WITH_ACH, false)
        val limit   = intent.getIntExtra(EXTRA_LIMIT, 200).coerceIn(1, 5000)
        val results = RaApiClient.searchGames(consoleId, term, creds.user, creds.apiKey,
                                              limit = limit, onlyWithAchievements = withAch)
        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("consoleId", consoleId)
            .put("term",      term)
            .put("status",    if (results.length() == 0) "no_results" else "ok")
            .put("results",   results)
        Paths.searchRa(jobId).writeText(payload.toString())
        Log.d(TAG, "SearchGames job done for consoleId=$consoleId term='$term' hits=${results.length()}")
    }

    // Accepts any subset of the credential fields; absent ones leave their block
    // in credentials.json untouched, so the theme can save one settings field at
    // a time. Never log the values.
    private fun handleSetCredentials(intent: Intent, jobId: String) {
        val user   = intent.getStringExtra(EXTRA_USER)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        val sgdb   = intent.getStringExtra(EXTRA_SGDB_KEY)
        val igdbId = intent.getStringExtra(EXTRA_IGDB_CLIENT_ID)
        val igdbSc = intent.getStringExtra(EXTRA_IGDB_CLIENT_SECRET)
        val ssDev  = intent.getStringExtra(EXTRA_SS_DEV_ID)
        val ssDevP = intent.getStringExtra(EXTRA_SS_DEV_PASSWORD)
        val ssUser = intent.getStringExtra(EXTRA_SS_USER)
        val ssPass = intent.getStringExtra(EXTRA_SS_PASSWORD)
        val ssSoft = intent.getStringExtra(EXTRA_SS_SOFTNAME)

        Config.writeCredentials(
            raUser           = user,
            raApiKey         = apiKey,
            sgdbKey          = sgdb,
            igdbClientId     = igdbId,
            igdbClientSecret = igdbSc,
            ssDevId          = ssDev,
            ssDevPassword    = ssDevP,
            ssUser           = ssUser,
            ssPassword       = ssPass,
            ssSoftname       = ssSoft
        )

        val touched = listOfNotNull(
            if (!user.isNullOrEmpty() || !apiKey.isNullOrEmpty()) "ra" else null,
            if (!sgdb.isNullOrEmpty()) "steamGridDb" else null,
            if (!igdbId.isNullOrEmpty() || !igdbSc.isNullOrEmpty()) "igdb" else null,
            if (listOf(ssDev, ssDevP, ssUser, ssPass, ssSoft).any { !it.isNullOrEmpty() })
                "screenScraper" else null
        )
        Log.d(TAG, "SetCredentials job done, blocks updated: $touched")
    }

    /**
     * Which RetroAchievements game a Pegasus game is.
     *
     * Shares RaMatcher with the desktop daemon, so a theme gets the same answer
     * on both platforms and needs no matcher of its own. The reply goes to the
     * search-ra slot the theme already polls.
     */
    private fun handleMatch(intent: Intent, jobId: String) {
        val title    = intent.getStringExtra(EXTRA_TERM).orEmpty()
        val platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()
        val romPath  = intent.getStringExtra(EXTRA_FILE)

        val index = try {
            Paths.discoveryIndex.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        } catch (e: Exception) { null }

        var match = RaMatcher.fromIndex(index, title, platform, romPath)
        if (match == null) {
            val consoleId = RaConsoleMap.consoleId(platform)
            val creds = Config.load().ra
            if (consoleId > 0 && creds != null && creds.user.isNotEmpty() && creds.apiKey.isNotEmpty()) {
                val catalogue = RaApiClient.fetchGameList(consoleId, creds.user, creds.apiKey)
                match = RaMatcher.fromCatalogue(catalogue, title, platform, romPath)
            }
        }

        Paths.searchRa(jobId).writeText(
            RaMatcher.toJson(match).put("schemaVersion", SchemaVersion.CURRENT).toString())
        Log.d(TAG, "Match job done for '$title' ($platform) -> ${match?.gameId ?: 0}")
    }

    /** The console table, so a theme can label a platform without its own copy. */
    private fun handleConsoles(jobId: String) {
        Paths.searchRa(jobId).writeText(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("table", RaConsoleMap.asJson())
            .toString())
    }

    /** Which credentials are set, and the RA username. Never the secrets. */
    private fun handleCredentialsStatus(jobId: String) {
        Paths.searchRa(jobId).writeText(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("credentials", Config.status())
            .toString())
    }

    private fun handleClearCredentials(intent: Intent, jobId: String) {
        val block = intent.getStringExtra(EXTRA_BLOCK).orEmpty()
        val ok = Config.clearBlock(block)
        Paths.searchRa(jobId).writeText(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", if (ok) "ok" else "error")
            .put("cleared", if (ok) block else "")
            .put("credentials", Config.status())
            .toString())
        Log.d(TAG, "ClearCredentials '$block' -> $ok")
    }

    private fun writeSearchError(jobId: String, msg: String) {
        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status",        "error")
            .put("error",         msg)
            .put("results",       org.json.JSONArray())
        Paths.searchRa(jobId).writeText(payload.toString())
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "RA Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

    companion object {
        private const val TAG           = "RaService"
        private const val CHANNEL_ID    = "ra_sync"
        private const val NOTIFICATION_ID = 3

        const val EXTRA_VERB       = "verb"
        const val EXTRA_JOB_ID     = "jobId"
        const val EXTRA_USER       = "user"
        const val EXTRA_API_KEY    = "apiKey"
        const val EXTRA_SGDB_KEY           = "sgdbKey"
        const val EXTRA_IGDB_CLIENT_ID     = "igdbClientId"
        const val EXTRA_IGDB_CLIENT_SECRET = "igdbClientSecret"
        const val EXTRA_SS_DEV_ID       = "ssDevId"
        const val EXTRA_SS_DEV_PASSWORD = "ssDevPassword"
        const val EXTRA_SS_USER         = "ssUser"
        const val EXTRA_SS_PASSWORD     = "ssPassword"
        const val EXTRA_SS_SOFTNAME     = "ssSoftname"
        const val EXTRA_GAME_ID    = "gameId"
        const val EXTRA_CONSOLE_ID = "consoleId"
        const val EXTRA_TERM       = "term"
        const val EXTRA_PLATFORM   = "platform"
        const val EXTRA_FILE       = "file"
        const val EXTRA_BLOCK      = "block"
        const val EXTRA_WITH_ACH   = "withAchievements"
        const val EXTRA_LIMIT      = "limit"

        const val VERB_PROFILE          = "refresh-ra-profile"
        const val VERB_DETAIL           = "refresh-ra"
        const val VERB_SEARCH_GAMES     = "search-ra-games"
        const val VERB_SET_CREDENTIALS  = "set-credentials"
        const val VERB_MATCH            = "match-ra"
        const val VERB_CONSOLES         = "ra-consoles"
        const val VERB_CRED_STATUS      = "credentials-status"
        const val VERB_CRED_CLEAR       = "clear-credentials"
    }
}
