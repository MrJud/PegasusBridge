package com.pegasus.bridge.video

import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds the search-video JSON callback consumed by QML via `PegasusData.jobDone(jobId)`.
 * Writes to `/sdcard/PegasusData/search/{jobId}.json` atomically.
 *
 * Schema:
 *   { "schemaVersion": 1, "query": "...", "status": "ok|no_results|error",
 *     "results": [ { videoId, title, author, durationSec, thumbUrl, ytPageUrl }, ... ],
 *     "error": "..." (only on error) }
 */
object SearchCallback {

    fun okJson(query: String, items: List<YouTubeSearcher.Result>): String {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(JSONObject().apply {
                put("videoId", r.videoId)
                put("title", r.title)
                put("author", r.author)
                put("durationSec", r.durationSec)
                put("thumbUrl", r.thumbUrl)
                put("ytPageUrl", "https://www.youtube.com/watch?v=${r.videoId}")
            })
        }
        val status = if (items.isEmpty()) "no_results" else "ok"
        return JSONObject().apply {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("query", query)
            put("status", status)
            put("results", arr)
        }.toString()
    }

    fun errorJson(query: String, message: String): String =
        JSONObject().apply {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("query", query)
            put("status", "error")
            put("error", message.take(500))
            put("results", JSONArray())
        }.toString()

    /** Atomic write: write tmp → rename. Prevents QML from reading a partial JSON. */
    fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
