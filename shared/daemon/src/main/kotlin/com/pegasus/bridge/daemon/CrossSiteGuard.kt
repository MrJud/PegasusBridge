package com.pegasus.bridge.daemon

/**
 * Keeps a web page from using the daemon, by refusing every request a browser
 * makes.
 *
 * Listening on loopback keeps other machines out and does nothing about a web
 * page open in a browser on this one. The browser is a local process, and it
 * sends a GET to 127.0.0.1 for any page that asks for one, as a picture, a
 * script, a frame or a fetch. Every route here answers a GET and several of
 * them write, `/emulators/apply` a command Pegasus later runs, so a page had
 * only to know the port, which `--on-demand` fixes, at 38700 by default.
 *
 * Nothing here can stop the browser sending the request. What it can do is
 * read the headers a browser writes and a page's script cannot write, change
 * or take away, and refuse before a handler runs.
 *
 * The daemon's own callers are not browsers and send none of those headers.
 * The theme's QML XMLHttpRequest sends `Host`, `Connection`, `Accept-Encoding`,
 * `Accept-Language` and `User-Agent` and nothing else, the same from Qt 5.15
 * and from Qt 6, and curl sends `Host`, `User-Agent` and `Accept`. So a request
 * that carries one of them is refused whatever the header says, and that costs
 * the theme and curl nothing.
 *
 * Whatever it says, because no value of them is safe to serve.
 * `Sec-Fetch-Site: none` reads as the person at the browser, who typed the
 * address, and an `Origin` on this machine reads as a neighbour. Chrome 155
 * was given a daemon that served both and wrote through each: a page that asks
 * the browser to prefetch a link has it fetched as `none`; a site the person
 * does open, by typing its address, and that answers with a redirect to the
 * daemon is followed there as `none`; and a page served from another port of
 * this machine opens a WebSocket, whose handshake is a GET with that page's
 * loopback `Origin` and no `Sec-Fetch-Site` at all. These headers say who
 * started a request, not who chose where it goes.
 *
 * For the same reason none of this is a barrier to a process on this machine,
 * which sends whatever headers it likes. It is about browsers and nothing
 * else, and about the ones that send these headers: see CONTEXT.md for what an
 * old one still gets through.
 */
internal object CrossSiteGuard {

    /** What a refused request is told, and what is kept of it for the log. */
    data class Refusal(val message: String, val header: String, val value: String)

    /**
     * Why a request with these headers is refused, or null when it may go on
     * to a handler. The names in [headers] are in lower case, as the server's
     * parser leaves them.
     */
    fun refusal(headers: Map<String, String>): Refusal? {
        // A page that has pointed its own name at 127.0.0.1 (DNS rebinding) is,
        // to the browser, a page talking to its own site, and on plain http
        // under a name of its own the browser adds none of the headers the
        // rule below looks for. The name is still in Host, because that is
        // what the browser was asked for.
        //
        // No Host at all passes. An HTTP/1.0 client sends none, and a browser
        // always sends one, so a request without it is not what this rule is
        // looking for. A Host that is there and empty names nothing and is
        // refused with the rest.
        headers["host"]?.let {
            if (!LOOPBACK_HOST.matches(it.lowercase()))
                return Refusal(NOT_THIS_MACHINE, "host", it)
        }

        // `Origin` goes with a fetch to another origin, a form that posts and
        // every WebSocket handshake. It is refused whatever it names: `null`,
        // which a sandboxed frame and a page opened from a file send, and an
        // origin on this machine, which is somebody else's page on another
        // port. The daemon serves no page, so there is no origin of its own.
        //
        // `Sec-` begins the names a browser keeps to itself. `Sec-Fetch-Site`
        // is on every request a current browser makes to this address but a
        // WebSocket handshake, which has `Sec-WebSocket-Key` instead, and a
        // prefetch has `Sec-Purpose` as well. The whole prefix rather than a
        // list, so that the next such header is refused before anyone has
        // heard of it.
        val told = TELLING.firstOrNull { it in headers }
            ?: headers.keys.filter { it.startsWith(BROWSERS_OWN) }.minOrNull()
        if (told != null) return Refusal(A_BROWSER, told, headers.getValue(told))

        return null
    }

    private const val NOT_THIS_MACHINE =
        "refused: this server answers only as 127.0.0.1, localhost or [::1]"
    private const val A_BROWSER =
        "refused: this server does not answer a web browser, ask it with curl"

    private const val BROWSERS_OWN = "sec-"

    // Of the headers a refused request may carry, the one to put in the log:
    // these two say the most about where it came from.
    private val TELLING = listOf("sec-fetch-site", "origin")

    // The three names of this machine and no others: 127.0.0.1 is where the
    // socket is bound, and 0.0.0.0, which Linux also delivers there, is a name
    // a page has used to get past exactly this kind of list.
    //
    // Any port, not the one that is bound. Under socket activation the client
    // talks to the port systemd holds and systemd-socket-proxyd passes its
    // bytes on unchanged, so Host names a port this process never opened.
    private val LOOPBACK_HOST = Regex("""(127\.0\.0\.1|localhost|\[::1\])(:\d{1,5})?""")
}
