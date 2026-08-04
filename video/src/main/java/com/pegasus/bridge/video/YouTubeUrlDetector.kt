package com.pegasus.bridge.video

object YouTubeUrlDetector {

    private val YT_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtu.be",    "www.youtu.be",
        "music.youtube.com"
    )

    fun isYouTube(url: String): Boolean {
        if (url.isBlank()) return false
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        if (host !in YT_HOSTS) return false
        return when {
            host == "youtu.be" || host == "www.youtu.be" ->
                u.pathSegments?.isNotEmpty() == true
            u.path?.startsWith("/watch") == true ->
                u.getQueryParameter("v") != null
            u.path?.startsWith("/shorts/") == true -> true
            u.path?.startsWith("/embed/")  == true -> true
            else -> false
        }
    }
}
