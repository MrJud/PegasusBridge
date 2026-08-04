package com.pegasus.bridge.video

import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONObject
import java.io.File

/**
 * Builds and atomically writes JSON callback files for download-video verb.
 * Written to `/sdcard/PegasusData/download/{jobId}.json`.
 *
 * Schema:
 *   { "schemaVersion": 1, "action": "download",
 *     "status":    "ok|error|progress",
 *     "localPath": "/.../trailers/<key>.mp4",   // "ok" only
 *     "progress":  0.75,                        // "progress" only
 *     "error":     "message" }                  // "error" only
 */
object DownloadCallback {

    fun progressJson(progress: Float): String =
        JSONObject().apply {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("action", "download")
            put("status", "progress")
            put("progress", progress.coerceIn(0f, 1f).toDouble())
        }.toString()

    fun okJson(localPath: String): String =
        JSONObject().apply {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("action", "download")
            put("status", "ok")
            put("localPath", localPath)
        }.toString()

    fun errorJson(message: String): String =
        JSONObject().apply {
            put("schemaVersion", SchemaVersion.CURRENT)
            put("action", "download")
            put("status", "error")
            put("error", message.take(500))
        }.toString()

    /** Atomic write via temp-file rename — prevents QML from reading a partial JSON. */
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
