package com.pegasus.bridge

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.hasher.HasherService
import com.pegasus.bridge.media.MediaService
import com.pegasus.bridge.pegasus.PegasusService
import com.pegasus.bridge.ra.CredentialVerbs
import com.pegasus.bridge.ra.RaConsoleMap
import com.pegasus.bridge.ra.RaService
import com.pegasus.bridge.scrapers.GameNativeLaunch
import com.pegasus.bridge.video.VideoPlayerActivity
import com.pegasus.bridge.video.VideoService
import org.json.JSONObject

/**
 * Thin activity, no UI — receives every `pegasus-data://<verb>?…` intent URI and dispatches
 * to the correct Service (or Activity for `play-video`).
 *
 * Contract: writes `pending/{jobId}.json` + `done/{jobId}.done` before terminating for any
 * verb it can't or won't handle, so the theme's polling loop never hangs.
 *
 * Phase 7 note: legacy schemes `rahasher://` and `pegasus-video://` have been removed from
 * the manifest; the theme side must now use only `pegasus-data://`.
 */
class DataLayerRouter : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        dispatch(uri)
        finish()
    }

    private fun dispatch(uri: Uri) {
        val verb  = uri.host ?: run { finish(); return }
        // A job id names the files of the answer, so one that is not a file
        // name of its own (Paths.isFileName) is taken as none, as the daemon
        // takes one it cannot use. Otherwise any verb, from a web page too,
        // could write its answer over credentials.json.
        val jobId = uri.getQueryParameter("jobId")?.takeIf(Paths::isFileName)
            ?: java.util.UUID.randomUUID().toString()

        when (verb) {
            "scan" -> {
                val roots = uri.getQueryParameter("roots") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, HasherService::class.java).apply {
                    putExtra(HasherService.EXTRA_ROOTS,  roots)
                    putExtra(HasherService.EXTRA_JOB_ID, jobId)
                    // For whoever is timing a card with more or fewer readers.
                    // The theme does not send it. Left out, or not a number, it
                    // is not passed on and the service's own count stands.
                    uri.getQueryParameter("hashWorkers")?.toIntOrNull()
                        ?.let { putExtra(HasherService.EXTRA_HASH_WORKERS, it) }
                })
            }
            "scrape-media" -> {
                // Names media/{gameId}.json, so held as a job id is.
                val gameId   = uri.getQueryParameter("gameId")?.takeIf(Paths::isFileName)
                    ?: return stub(jobId, verb)
                val title    = uri.getQueryParameter("title")    ?: return stub(jobId, verb)
                val platform = uri.getQueryParameter("platform") ?: ""
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra(MediaService.EXTRA_VERB, MediaService.VERB_SCRAPE_MEDIA)
                    putExtra("gameId",                gameId)
                    putExtra("title",                 title)
                    putExtra("platform",              platform)
                    putExtra(MediaService.EXTRA_JOB_ID, jobId)
                })
            }
            "scrape-source" -> {
                val source = uri.getQueryParameter("source") ?: return stub(jobId, verb)
                val op     = uri.getQueryParameter("op")     ?: return stub(jobId, verb)
                // Raccogli tutti gli altri param URL (escluso jobId/source/op) in un Bundle.
                val paramsBundle = Bundle().apply {
                    for (name in uri.queryParameterNames) {
                        if (name == "jobId" || name == "source" || name == "op") continue
                        uri.getQueryParameter(name)?.let { putString(name, it) }
                    }
                }
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra(MediaService.EXTRA_VERB,   MediaService.VERB_SCRAPE_SOURCE)
                    putExtra(MediaService.EXTRA_JOB_ID, jobId)
                    putExtra(MediaService.EXTRA_SOURCE, source)
                    putExtra(MediaService.EXTRA_OP,     op)
                    putExtra(MediaService.EXTRA_PARAMS, paramsBundle)
                })
            }
            "refresh-ra-profile" -> {
                // Names profile/{user}.json and completion/{user}.json, so held
                // as a job id is.
                val user = uri.getQueryParameter("user")?.takeIf(Paths::isFileName)
                    ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_PROFILE)
                    putExtra(RaService.EXTRA_USER,   user)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "refresh-ra" -> {
                val gameId = uri.getQueryParameter("gameId")?.toIntOrNull() ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,    RaService.VERB_DETAIL)
                    putExtra(RaService.EXTRA_GAME_ID, gameId)
                    putExtra(RaService.EXTRA_JOB_ID,  jobId)
                })
            }
            "search-ra-games" -> {
                // Either a numeric consoleId or a Pegasus platform short name:
                // resolving the name here is what lets a theme carry no console
                // table of its own. Same contract as the daemon's /ra/search.
                val consoleId = uri.getQueryParameter("consoleId")?.toIntOrNull()
                    ?: uri.getQueryParameter("platform")?.let { RaConsoleMap.consoleId(it) }
                    ?: return stub(jobId, verb)
                if (consoleId <= 0) return stub(jobId, verb)
                val term = uri.getQueryParameter("term") ?: ""
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,       RaService.VERB_SEARCH_GAMES)
                    putExtra(RaService.EXTRA_CONSOLE_ID, consoleId)
                    putExtra(RaService.EXTRA_TERM,       term)
                    putExtra(RaService.EXTRA_WITH_ACH,   uri.getQueryParameter("withAchievements") == "1")
                    putExtra(RaService.EXTRA_LIMIT,
                             uri.getQueryParameter("limit")?.toIntOrNull() ?: 200)
                    putExtra(RaService.EXTRA_JOB_ID,     jobId)
                })
            }
            "match-ra" -> {
                val title = uri.getQueryParameter("title") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,     RaService.VERB_MATCH)
                    putExtra(RaService.EXTRA_TERM,     title)
                    putExtra(RaService.EXTRA_PLATFORM, uri.getQueryParameter("platform") ?: "")
                    uri.getQueryParameter("file")?.let { putExtra(RaService.EXTRA_FILE, it) }
                    putExtra(RaService.EXTRA_JOB_ID,   jobId)
                })
            }
            "ra-consoles" -> {
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CONSOLES)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "credentials-status" -> {
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CRED_STATUS)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            // Neither credential verb acts on an intent a browser sent: see
            // CredentialVerbs.admits. Dropped, with no pending and no done.
            "clear-credentials" -> {
                if (!CredentialVerbs.admits(verb, intent.categories)) return
                val block = uri.getQueryParameter("block") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CRED_CLEAR)
                    putExtra(RaService.EXTRA_BLOCK,  block)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "set-credentials" -> {
                if (!CredentialVerbs.admits(verb, intent.categories)) return
                // Every field is optional — the theme saves one settings entry at
                // a time and the service merges it into credentials.json. Reject
                // only a request that carries nothing that can be written: no
                // field, empty ones, or a steamId alone that is not 17 digits.
                // The steam key travels here as the RetroAchievements key does.
                // ActivityTaskManager logs the URI without its query (measured
                // on Android 13).
                val fields = CredentialVerbs.fields { uri.getQueryParameter(it) }
                if (fields.isEmpty()) return stub(jobId, verb)

                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB, RaService.VERB_SET_CREDENTIALS)
                    for ((name, value) in fields) putExtra(name, value)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            /** Proves the ScreenScraper credentials. Mirrors the daemon's /screenscraper/user. */
            "screenscraper-user" -> {
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra(MediaService.EXTRA_VERB,   MediaService.VERB_SS_USER)
                    putExtra(MediaService.EXTRA_JOB_ID, jobId)
                })
            }
            "search-video" -> {
                val query = uri.getQueryParameter("q") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, VideoService::class.java).apply {
                    putExtra(VideoService.EXTRA_VERB,   VideoService.VERB_SEARCH)
                    putExtra(VideoService.EXTRA_QUERY,  query)
                    putExtra(VideoService.EXTRA_JOB_ID, jobId)
                })
            }
            "play-video" -> {
                // Direct launch — VideoPlayerActivity reads `url` / `title` / `gameKey`
                // from the original URI via UriParams.fromIntentData.
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    data = uri
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                // No pending/done for play-video — it's a fire-and-forget UI launch.
            }
            "download-video" -> {
                val url     = uri.getQueryParameter("url")     ?: return stub(jobId, verb)
                val gameKey = uri.getQueryParameter("gameKey") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, VideoService::class.java).apply {
                    putExtra(VideoService.EXTRA_VERB,     VideoService.VERB_DOWNLOAD)
                    putExtra(VideoService.EXTRA_URL,      url)
                    putExtra(VideoService.EXTRA_GAME_KEY, gameKey)
                    putExtra(VideoService.EXTRA_JOB_ID,   jobId)
                })
            }
            // A Pegasus collection's launch line, for a folder of GameNative's
            // marker files. No pending and no done: nobody is waiting for an
            // answer, and what went wrong is said on the screen.
            "launch-gamenative" -> launchGameNative()
            /**
             * The verbs that reach into the user's own library — emulator
             * discovery, the two exports and the launch choice.
             *
             * Matched as a set rather than spelled out one branch at a time,
             * because every one of them forwards the same thing: the verb, the
             * job id and whatever else the URI carried. Listing twelve
             * identical branches would give somebody twelve places to forget.
             *
             * Their answers land in `pegasus/{jobId}.json`, not in `search-ra/`
             * or `scrape/`, and the done marker is written by the service.
             */
            in PegasusService.VERBS -> {
                startForegroundService(Intent(this, PegasusService::class.java).apply {
                    putExtra(PegasusService.EXTRA_VERB,   verb)
                    putExtra(PegasusService.EXTRA_JOB_ID, jobId)
                    for (name in uri.queryParameterNames) {
                        if (name == "jobId") continue
                        uri.getQueryParameter(name)?.let { putExtra(name, it) }
                    }
                })
            }
            else -> stub(jobId, verb)
        }
    }

    /**
     * Starts the GameNative game a marker file stands for.
     *
     * The file is named by the string extra `file`, which Pegasus fills with
     * `{file.path}`. What is done with it is GameNativeLaunch's to say, and
     * tested there: an intent a browser sent is dropped unread, the path is
     * held to being a marker in a folder of games, and the extra is read
     * inside a try, since a parcel written to hurt throws as it is opened.
     * Left to this are the two things only an activity has, the intent and
     * the screen.
     *
     * NEW_TASK and no other flag. A second launch while a game is running
     * reaches the running GameNative, which changes game by itself.
     */
    private fun launchGameNative() {
        val verdict = GameNativeLaunch.verdict(
            categories     = intent.categories,
            canReadStorage = hasAllFilesAccess(),
            readPath       = { intent.getStringExtra(EXTRA_FILE) })

        when (verdict) {
            is GameNativeLaunch.Verdict.Ignore ->
                Log.w(TAG, "launch-gamenative: dropped, it came through a browser")
            is GameNativeLaunch.Verdict.NoAccess -> {
                Log.w(TAG, "launch-gamenative: no all-files access")
                say(getString(R.string.gamenative_no_access))
            }
            is GameNativeLaunch.Verdict.NotAGame -> {
                Log.w(TAG, "launch-gamenative: refused, ${verdict.reason}")
                say(if (verdict.name.isEmpty()) getString(R.string.gamenative_not_a_game)
                    else getString(R.string.gamenative_not_a_game_named, verdict.name))
            }
            is GameNativeLaunch.Verdict.Start -> {
                // Named by its component, so no <queries> entry is needed to
                // reach it: that is for asking which packages there are.
                val start = Intent(GameNativeLaunch.ACTION)
                    .setClassName(GameNativeLaunch.PACKAGE, GameNativeLaunch.ACTIVITY)
                    .addCategory(GameNativeLaunch.CATEGORY)
                    // An Int and a String. An id sent as a String is read
                    // there as -1 and refused.
                    .putExtra(GameNativeLaunch.EXTRA_APP_ID, verdict.request.appId)
                    .putExtra(GameNativeLaunch.EXTRA_GAME_SOURCE, verdict.request.source.name)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(start)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "launch-gamenative: GameNative is not installed")
                    say(getString(R.string.gamenative_not_installed))
                } catch (e: SecurityException) {
                    Log.w(TAG, "launch-gamenative: GameNative would not be started")
                    say(getString(R.string.gamenative_not_started))
                }
            }
        }
    }

    /**
     * Whether the Bridge may read the storage the markers are on. From
     * Android 11 that is all-files access; before it, the storage permission.
     */
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // The application's context: this activity is finished before the toast is gone.
    private fun say(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Writes pending+done stub (no real work) so the theme's polling loop doesn't hang
     * on malformed URIs or truly unknown verbs.
     */
    private fun stub(jobId: String, verb: String) {
        Paths.ensureAll()
        val now = System.currentTimeMillis() / 1000L
        Paths.pending(jobId).writeText(
            JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("jobId",     jobId)
                .put("verb",      verb)
                .put("status",    "error")
                .put("error",     "verb not implemented or missing required params")
                .put("startedAt", now)
                .put("updatedAt", now)
                .toString()
        )
        Paths.markDone(jobId)
    }

    private companion object {
        const val TAG = "DataLayerRouter"
        const val EXTRA_FILE = "file"
    }
}
