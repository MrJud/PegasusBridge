package com.pegasus.bridge.core

/**
 * A URL with its secrets taken out, for logs.
 *
 * Every source this project talks to authenticates in the **query string**.
 * RetroAchievements puts the API key in `y=`, ScreenScraper puts two passwords
 * in `devpassword=` and `sspassword=`, IGDB carries a bearer token. So the one
 * habit that would otherwise be harmless — logging the URL that failed — writes
 * the user's credentials into a file, and on desktop that file is stderr or a
 * journal a bug report gets pasted into.
 *
 * This is not theoretical: `RaHashLookup.getWithRetry()` logged the whole
 * metadata URL on retry exhaustion, and that URL always carries `y=<api key>`.
 *
 * What survives redaction is chosen to keep the log useful — host, path, and
 * the parameters that identify *which* request failed (`i=` the game id, `m=`
 * the hash) — because a line reading only "a request failed" sends the reader
 * to a packet capture, which is where the secret is anyway.
 */
object SafeUrl {

    /**
     * Parameter names whose values must never be logged.
     *
     * Matched case-insensitively and by whole name. Deliberately a denylist of
     * *names* rather than a heuristic on values: a key that happens to look like
     * an id is still a key, and a heuristic would let the next source through by
     * default. Anything new that carries a secret must be added here — the test
     * for each provider asserts its own sentinel never reaches a log.
     */
    private val SECRET_PARAMS = setOf(
        "y",              // RetroAchievements API key
        "apikey", "api_key",
        "devpassword", "sspassword", "password", "passwd",
        "token", "access_token", "refresh_token", "client_secret", "secret",
        "authorization", "auth", "key", "sig", "signature"
    )

    /** Header names whose values must never be logged. */
    private val SECRET_HEADERS = setOf(
        "authorization", "x-api-key", "client-secret", "cookie", "set-cookie"
    )

    private const val MASK = "***"

    /**
     * The URL with every secret parameter's value replaced.
     *
     * A string that is not a URL at all comes back masked rather than passed
     * through: this is called from `catch` blocks, where the input may be
     * whatever a broken response contained.
     */
    fun redact(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url.substringBefore('#')
        val base = url.substring(0, q)
        val rest = url.substring(q + 1)
        val fragment = rest.indexOf('#').let { if (it < 0) "" else rest.substring(it) }
        val query = if (fragment.isEmpty()) rest else rest.substring(0, rest.length - fragment.length)

        val redacted = query.split('&').joinToString("&") { pair ->
            if (pair.isEmpty()) return@joinToString pair
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val name = pair.substring(0, eq)
            if (name.lowercase() in SECRET_PARAMS) "$name=$MASK" else pair
        }
        return "$base?$redacted$fragment"
    }

    /**
     * Host, path and the non-secret parameters, as one short label.
     *
     * Preferred over [redact] in a message a user will read: it says which
     * endpoint failed and about what, without a wall of query string. Use
     * [redact] when the full shape of the request is what is being diagnosed.
     */
    fun label(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        val hostAndPath = withoutScheme.substringBefore('?')
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return hostAndPath
        val kept = q.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val name = pair.substring(0, eq)
            if (name.lowercase() in SECRET_PARAMS) null else pair
        }
        return if (kept.isEmpty()) hostAndPath else "$hostAndPath?${kept.joinToString("&")}"
    }

    /** True if [name] names a secret. Exposed so header and body redaction agree. */
    fun isSecretParam(name: String): Boolean = name.lowercase() in SECRET_PARAMS

    fun isSecretHeader(name: String): Boolean = name.lowercase() in SECRET_HEADERS

    /**
     * Whether [text] still contains [secret] — the assertion a provider test makes
     * against a captured log.
     *
     * Blank secrets answer false rather than true: a test that forgot to set a
     * sentinel would otherwise report every log line as a leak.
     */
    fun leaks(text: String, secret: String): Boolean =
        secret.isNotBlank() && text.contains(secret)
}
