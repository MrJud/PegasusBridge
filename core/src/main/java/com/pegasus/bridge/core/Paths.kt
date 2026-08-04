package com.pegasus.bridge.core

import java.io.File

object Paths {
    private val ROOT = File("/sdcard/PegasusData")

    val CONFIG        = File(ROOT, "config")
    val METADATA      = File(ROOT, "metadata")
    val MEDIA         = File(ROOT, "media")
    val SEARCH        = File(ROOT, "search")
    val SEARCH_RA     = File(ROOT, "search-ra")
    val SCRAPE        = File(ROOT, "scrape")
    val DOWNLOAD      = File(ROOT, "download")
    val PENDING       = File(ROOT, "pending")
    val DONE          = File(ROOT, "done")
    val PROFILE       = File(ROOT, "profile")
    val COMPLETION    = File(ROOT, "completion")

    /** What the ROM scan found, keyed by title+platform. Same name as the daemon's. */
    val discoveryIndex = File(METADATA, "_index.json")

    val CREDENTIALS    = File(CONFIG,   "credentials.json")
    val LEGACY_HASHER  = File("/sdcard/ReStory", "hasher_config.json")

    fun metadata(gameId: String)  = File(METADATA,   "$gameId.json")
    fun media(gameId: String)     = File(MEDIA,      "$gameId.json")
    fun search(jobId: String)     = File(SEARCH,     "$jobId.json")
    fun searchRa(jobId: String)   = File(SEARCH_RA,  "$jobId.json")
    fun scrape(jobId: String)     = File(SCRAPE,     "$jobId.json")
    fun download(jobId: String)   = File(DOWNLOAD,   "$jobId.json")
    fun pending(jobId: String)    = File(PENDING,    "$jobId.json")
    fun done(jobId: String)       = File(DONE,       "$jobId.done")

    /**
     * Marks a job finished.
     *
     * The content matters: a theme detects the marker with an XMLHttpRequest on
     * a file:// URL, and Qt only reports 200 for a file that has bytes in it. An
     * empty marker is indistinguishable from a missing one, so the caller waits
     * out its timeout on a job that already finished.
     */
    fun markDone(jobId: String) = done(jobId).writeText("done")

    fun profile(user: String)     = File(PROFILE,    "$user.json")
    fun completion(user: String)  = File(COMPLETION, "$user.json")

    fun ensureAll() {
        listOf(CONFIG, METADATA, MEDIA, SEARCH, SEARCH_RA, SCRAPE, DOWNLOAD,
               PENDING, DONE, PROFILE, COMPLETION).forEach { it.mkdirs() }
    }
}
