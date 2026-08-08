package com.pegasus.bridge.core

import java.io.File

/**
 * Where the Bridge keeps its data. The Android build passes `/sdcard/PegasusData`;
 * the desktop daemon passes an XDG location. Nothing below this class should ever
 * name a directory itself.
 *
 * Replaces the old `object Paths`, which hardcoded the Android path and so could
 * not exist on desktop at all.
 */
class BridgePaths(val root: File) {

    val config     = File(root, "config")
    val metadata   = File(root, "metadata")
    val media      = File(root, "media")
    val search     = File(root, "search")
    val searchRa   = File(root, "search-ra")
    val scrape     = File(root, "scrape")
    val download   = File(root, "download")
    val pending    = File(root, "pending")
    val done       = File(root, "done")
    val profile    = File(root, "profile")
    val completion = File(root, "completion")
    /** Answers worth keeping but safe to lose — nothing here is user data. */
    val cache      = File(root, "cache")
    /**
     * Pictures the Bridge fetched on the theme's behalf.
     *
     * Separate from [media], which holds *descriptions* of media as JSON. These are the
     * bytes, and they exist because some sources authenticate their media URLs: a
     * ScreenScraper picture URL carries the developer password in its query string, so
     * the URL can never cross into the theme and a file path goes instead.
     */
    val artwork    = File(root, "artwork")

    val credentials = File(config, "credentials.json")

    fun metadata(gameId: String)   = File(metadata,   "$gameId.json")
    fun media(gameId: String)      = File(media,      "$gameId.json")
    fun search(jobId: String)      = File(search,     "$jobId.json")
    fun searchRa(jobId: String)    = File(searchRa,   "$jobId.json")
    fun scrape(jobId: String)      = File(scrape,     "$jobId.json")
    fun download(jobId: String)    = File(download,   "$jobId.json")
    fun pending(jobId: String)     = File(pending,    "$jobId.json")
    fun done(jobId: String)        = File(done,       "$jobId.done")
    fun profile(user: String)      = File(profile,    "$user.json")
    fun completion(user: String)   = File(completion, "$user.json")
    fun cache(name: String)        = File(cache,      name)
    fun artwork(name: String)      = File(artwork,    name)

    /** The discovery index the hasher builds: games[] plus a byKey{} reverse map. */
    val discoveryIndex = File(metadata, "_index.json")

    fun ensureAll() {
        listOf(config, metadata, media, search, searchRa, scrape, download,
               pending, done, profile, completion, cache, artwork).forEach { it.mkdirs() }
    }

    /**
     * Marks a job finished.
     *
     * The marker carries content on purpose. Qt's QML `XMLHttpRequest` cannot
     * distinguish a missing file from an empty one over `file://` — both report
     * status 0 with an empty body, and only a non-empty file reports 200. The
     * original implementation created the marker with `createNewFile()`, so the
     * theme's completion check could never see it and every job looked finished
     * immediately. Writing a byte or two makes the check work.
     */
    fun markDone(jobId: String) {
        done.mkdirs()
        writeAtomic(done(jobId), """{"jobId":"$jobId","finishedAt":${epochSeconds()}}""")
    }

    companion object {
        fun epochSeconds(): Long = System.currentTimeMillis() / 1000L

        /** Write via temp + rename so a reader never sees a half-written file. */
        fun writeAtomic(target: File, content: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }
}
