package com.pegasus.bridge.video

import java.net.URI

/**
 * Recognises the YouTube URLs that need stream resolution before playback.
 *
 * Was built on `android.net.Uri`; now on `java.net.URI`, which exists on both
 * platforms. Note the behavioural difference that matters here: `java.net.URI`
 * throws on a malformed input where `android.net.Uri` returns a mostly-empty
 * object, so parsing is wrapped.
 */
object YouTubeUrlDetector {

    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtu.be", "www.youtu.be",
        "music.youtube.com"
    )

    fun isYouTube(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in HOSTS) return false

        val path = uri.path.orEmpty()
        return when {
            host == "youtu.be" || host == "www.youtu.be" -> path.trim('/').isNotEmpty()
            path.startsWith("/watch")  -> queryParam(uri.rawQuery, "v") != null
            path.startsWith("/shorts/") -> true
            path.startsWith("/embed/")  -> true
            else -> false
        }
    }

    /** The eleven-character video id, or null if this is not a video URL. */
    fun videoId(url: String): String? {
        if (!isYouTube(url)) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> path.trim('/').substringBefore('/')
            path.startsWith("/watch")   -> queryParam(uri.rawQuery, "v")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
            path.startsWith("/embed/")  -> path.removePrefix("/embed/").substringBefore('/')
            else -> null
        }
        return id?.takeIf { it.matches(ID_PATTERN) }
    }

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    private fun queryParam(rawQuery: String?, key: String): String? =
        rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
}
