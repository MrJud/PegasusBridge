package com.pegasus.bridge.video

/**
 * A validated request to play or download a video.
 *
 * The Android version parsed this out of a `pegasus-video://` or
 * `pegasus-data://` Intent URI with `android.net.Uri`. Under the HTTP contract
 * the caller supplies the fields directly, so this is now just validation —
 * which keeps the security rule in one place rather than at each call site.
 */
data class VideoRequest(
    val url: String,
    val title: String,
    val gameKey: String
) {
    companion object {
        /** Only HTTPS is accepted, so a request can never downgrade the transport. */
        fun of(url: String?, title: String? = null, gameKey: String? = null): VideoRequest? {
            val u = url?.trim().orEmpty()
            if (u.isEmpty()) return null
            if (!u.startsWith("https://", ignoreCase = true)) return null
            return VideoRequest(u, title.orEmpty(), gameKey.orEmpty())
        }

        /**
         * Reduces a game key to something safe to use as a filename.
         * Mirrors the theme's `Utils.gameKey()` normalisation.
         */
        fun sanitizeGameKey(raw: String?): String {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s.length > 200) return ""
            return s.lowercase().replace(Regex("[^a-z0-9|]"), "").replace('|', '_')
        }
    }
}
