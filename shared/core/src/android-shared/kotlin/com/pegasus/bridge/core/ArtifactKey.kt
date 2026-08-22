package com.pegasus.bridge.core

import java.security.MessageDigest

/**
 * Names a downloaded file after *what it is*, not after what asked for it.
 *
 * The rule this replaces was `ss-<gameId>-<kind>.<ext>`, which conflates two
 * different pictures: a US and a European dump resolve to one ScreenScraper game
 * id and select different box art, so the second file found the first one's path
 * already occupied, skipped its download, and displayed the wrong cover — and,
 * because the skip is also the cache, would never correct itself.
 *
 * The key mixes the selected media's URL, the kind, the region, the format and
 * the ROM's own fingerprint. The URL goes in as a **digest**, never as text: a
 * ScreenScraper media URL carries `devid`, `devpassword` and `sspassword` in its
 * query string, so writing it into a filename or a JSON response would put the
 * credentials on disk in the one place nothing ever thinks to redact.
 *
 * A readable prefix survives — source, id, kind — because the artwork directory
 * is something a person browses when a cover looks wrong, and a directory of
 * bare digests answers no question at all. None of it is secret; all three are
 * already in the response.
 */
object ArtifactKey {

    /** Lowercase hex SHA-256. */
    fun sha256(value: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * The variant digest for one selected media.
     *
     * [mediaUrl] is hashed before it joins the tuple, so no caller can leak one
     * by accident. [romFingerprint] is what makes two regional dumps of one game
     * distinct; passing an empty string is allowed and degrades to keying on the
     * media alone, which is still correct — it is only less specific.
     */
    fun variant(
        source: String,
        mediaUrl: String,
        kind: String,
        type: String,
        region: String,
        format: String,
        romFingerprint: String
    ): String = sha256(
        listOf(source, sha256(mediaUrl), kind, type, region, format, romFingerprint)
            .joinToString("|")
    )

    /**
     * A filename: readable prefix, variant digest, extension.
     *
     * [digestChars] is 16 by default — 64 bits of a SHA-256. A library of a few
     * thousand artworks is nowhere near where that starts to collide, and a
     * shorter name is one a person can compare by eye against a response.
     */
    fun fileName(
        source: String,
        id: String,
        kind: String,
        variant: String,
        extension: String,
        digestChars: Int = 16
    ): String {
        val safeId = sanitize(id).ifEmpty { "unknown" }
        val safeKind = sanitize(kind).ifEmpty { "media" }
        val ext = sanitize(extension).ifEmpty { "bin" }
        return "${sanitize(source)}-$safeId-$safeKind-${variant.take(digestChars)}.$ext"
    }

    /**
     * Everything outside `[A-Za-z0-9._-]` removed.
     *
     * Names reach here from an API response and from ROM filenames, so they can
     * hold slashes, `..`, NUL and every kind of Unicode. This is the boundary
     * where a remote string stops being able to name a path.
     */
    fun sanitize(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._-]"), "").trimStart('.').take(80)
}
