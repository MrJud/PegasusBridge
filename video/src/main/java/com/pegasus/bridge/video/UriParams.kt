package com.pegasus.bridge.video

import android.net.Uri

/**
 * Parses the `url=` / `title=` / `gameKey=` params from either the legacy
 * `pegasus-video://play?…` URI or the new `pegasus-data://play-video?…` URI.
 *
 * Security: only HTTPS URLs are accepted.
 */
data class UriParams(
    val url: String,
    val title: String,
    val gameKey: String
) {
    companion object {
        private val ACCEPTED_SCHEMES = setOf("pegasus-video", "pegasus-data")

        fun fromIntentData(data: Uri?): UriParams? {
            if (data == null) return null
            if (data.scheme?.lowercase() !in ACCEPTED_SCHEMES) return null

            val url = data.getQueryParameter("url")?.trim().orEmpty()
            if (url.isEmpty()) return null
            if (!url.startsWith("https://", ignoreCase = true)) return null

            return UriParams(
                url     = url,
                title   = data.getQueryParameter("title").orEmpty(),
                gameKey = data.getQueryParameter("gameKey").orEmpty()
            )
        }
    }
}
