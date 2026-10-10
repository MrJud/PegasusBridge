package com.pegasus.bridge.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the Bridge keeps its data. The Android build passes `/sdcard/PegasusData`;
 * the desktop daemon passes an XDG location. Nothing below this class should ever
 * name a directory itself.
 *
 * Android keeps its `object Paths`, which hardcodes that path and so could not
 * exist on desktop at all, and its services go on naming directories through it.
 * The code both shells compile takes one of these instead; on Android that is
 * `Paths.bridge`, built over the same root.
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

    /**
     * Pictures the Bridge moved out of the way, never ones it deleted.
     *
     * Replacing a cover in the user's library means setting theirs aside, not
     * destroying it. An image costs a few hundred kilobytes; a box scan somebody
     * made themselves does not come back. So "replace" is a move, this is where
     * it moves to, and [com.pegasus.bridge.pegasus.ExportManifest] remembers
     * which original belongs to which replacement so a revert can undo the pair.
     */
    val replaced   = File(root, "replaced")

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
               pending, done, profile, completion, cache, artwork, replaced).forEach { it.mkdirs() }
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

        /**
         * Write via temp + rename so a reader never sees a half-written file,
         * and a process killed in the middle leaves the old file or the new
         * one, whole.
         *
         * By a move that is asked to be atomic and to replace. `File.renameTo`
         * alone, which this was, does not replace an existing file on
         * Windows: every write but the first fell through to rewriting the
         * target in place, where a kill leaves half a file. That mattered
         * less while a file was written once as a job ended; the scan's
         * ledger is now rewritten while the scan runs.
         *
         * What follows a move that fails is what there was before, for a
         * filesystem that cannot do one: the plain rename, then the rewrite
         * in place.
         */
        fun writeAtomic(target: File, content: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            try {
                Files.move(tmp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                if (!tmp.renameTo(target)) {
                    target.writeText(content)
                    tmp.delete()
                }
            }
        }
    }
}
