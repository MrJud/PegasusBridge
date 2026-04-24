package com.pegasus.bridge.core

import java.io.File

object Paths {
    private val ROOT = File("/sdcard/PegasusData")

    val CONFIG        = File(ROOT, "config")
    val METADATA      = File(ROOT, "metadata")
    val MEDIA         = File(ROOT, "media")
    val SEARCH        = File(ROOT, "search")
    val DOWNLOAD      = File(ROOT, "download")
    val PENDING       = File(ROOT, "pending")
    val DONE          = File(ROOT, "done")
    val PROFILE       = File(ROOT, "profile")
    val COMPLETION    = File(ROOT, "completion")

    val CREDENTIALS    = File(CONFIG,   "credentials.json")
    val LEGACY_HASHER  = File("/sdcard/ReStory", "hasher_config.json")

    fun metadata(gameId: String)  = File(METADATA,   "$gameId.json")
    fun media(gameId: String)     = File(MEDIA,      "$gameId.json")
    fun search(jobId: String)     = File(SEARCH,     "$jobId.json")
    fun download(jobId: String)   = File(DOWNLOAD,   "$jobId.json")
    fun pending(jobId: String)    = File(PENDING,    "$jobId.json")
    fun done(jobId: String)       = File(DONE,       "$jobId.done")
    fun profile(user: String)     = File(PROFILE,    "$user.json")
    fun completion(user: String)  = File(COMPLETION, "$user.json")

    fun ensureAll() {
        listOf(CONFIG, METADATA, MEDIA, SEARCH, DOWNLOAD, PENDING, DONE, PROFILE, COMPLETION)
            .forEach { it.mkdirs() }
    }
}
