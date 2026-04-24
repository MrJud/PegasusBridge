package com.pegasus.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.hasher.HasherService
import com.pegasus.bridge.media.MediaService
import com.pegasus.bridge.ra.RaService
import org.json.JSONObject

// Thin activity senza UI — riceve tutti gli intent URI e fa dispatch al Service corretto.
// Regola contratto: scrive pending/{jobId}.json + done/{jobId}.done prima di finire.
class DataLayerRouter : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        dispatch(uri)
        finish()
    }

    private fun dispatch(uri: Uri) {
        // Normalizza alias legacy → verb canonico
        val verb = when (uri.scheme) {
            "rahasher"      -> mapLegacyRaHasher(uri)
            "pegasus-video" -> mapLegacyVideo(uri)
            else            -> uri.host // pegasus-data://<verb>?...
        }

        val jobId = uri.getQueryParameter("jobId") ?: java.util.UUID.randomUUID().toString()

        when (verb) {
            "scan" -> {
                // Supporta sia pegasus-data://scan?roots=csv sia il legacy rahasher://scan?rom_dirs=csv
                val roots = uri.getQueryParameter("roots")
                    ?: uri.getQueryParameter("rom_dirs")
                    ?: return stub(jobId, "scan")
                startForegroundService(Intent(this, HasherService::class.java).apply {
                    putExtra(HasherService.EXTRA_ROOTS,  roots)
                    putExtra(HasherService.EXTRA_JOB_ID, jobId)
                })
            }
            "scrape-media" -> {
                val gameId   = uri.getQueryParameter("gameId")   ?: return stub(jobId, verb)
                val title    = uri.getQueryParameter("title")    ?: return stub(jobId, verb)
                val platform = uri.getQueryParameter("platform") ?: ""
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra("gameId",   gameId)
                    putExtra("title",    title)
                    putExtra("platform", platform)
                    putExtra("jobId",    jobId)
                })
            }
            "refresh-ra-profile" -> {
                val user = uri.getQueryParameter("user") ?: return stub(jobId, verb)
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
            // Stub: search-video, play-video, download-video (fasi future)
            else -> stub(jobId, verb ?: "unknown")
        }
    }

    // Scrive pending + done stub (nessun lavoro reale) — permette al tema di fare il polling
    // anche per i verb non ancora implementati, senza bloccarsi.
    private fun stub(jobId: String, verb: String) {
        Paths.ensureAll()
        val now = System.currentTimeMillis() / 1000L
        Paths.pending(jobId).writeText(
            JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("jobId",     jobId)
                .put("verb",      verb)
                .put("status",    "error")
                .put("error",     "verb not yet implemented")
                .put("startedAt", now)
                .put("updatedAt", now)
                .toString()
        )
        Paths.done(jobId).createNewFile()
    }

    // rahasher://scan?rom_dirs=...  →  verb "scan"
    private fun mapLegacyRaHasher(uri: Uri): String = "scan"

    // pegasus-video://search?q=  →  "search-video"
    // pegasus-video://play?url=  →  "play-video"
    // pegasus-video://download?  →  "download-video"
    private fun mapLegacyVideo(uri: Uri): String = when (uri.host) {
        "search"   -> "search-video"
        "play"     -> "play-video"
        "download" -> "download-video"
        else       -> uri.host ?: "unknown"
    }
}
