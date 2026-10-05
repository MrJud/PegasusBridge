package com.pegasus.bridge.contract

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// A scan as the ReStory theme reads it: a port of the theme's own code, so a test
// can ask what the popup would show for a given state of the data root or of the
// daemon. What a scan writes is then measured against the reader that exists,
// not against a description of it.
//
// Two files of the theme are involved, and the line numbers below are theirs:
//
//   components/ui/RetroAchievementsHub.qml keeps the popup's state. Every two
//   seconds while a scan is on screen _readHasherProgress fills it (2993-3044),
//   and after a reload _activeScanJobId decides whether there is a scan to come
//   back to (2879-2895). Those are ThemeScanReader.
//
//   components/data/BridgeApi.js answers pendingJob and jobDone for them: from
//   files on Android, from the daemon over HTTP on the desktop. Those are
//   ThemeFiles and ThemeHttp.
//
// Ported as written, oddities included: the question a test puts to this is never
// what a reader ought to make of a record. Where JavaScript and Kotlin part ways
// on a value the Bridge can write — what counts as empty, parseInt, the regular
// expression — the JavaScript rule is the one spelled out here. What the Bridge
// does not write is left out: a record that is JSON but not an object, a
// `message` that is not text, a `progress` that is not a number.

/** The two questions the hub asks BridgeApi.js about a job. */
interface ThemeBridge {
    /** pendingJob(jobId, attempt): the job's record, or null when there is none to read. */
    fun pendingJob(jobId: String): JSONObject?

    /** jobDone(jobId, attempt). */
    fun jobDone(jobId: String): Boolean

    /**
     * The time between two runs of the hub's code, when the answers to requests
     * it has sent come in. Nothing happens here on Android, where every read has
     * finished by the time it returns.
     */
    fun betweenTurns() {}
}

/**
 * The Android transport: files under the data root (BridgeApi.js:163-166, 432, 498-508).
 *
 * The theme reads them with a synchronous XMLHttpRequest on a file:// URL, and
 * Qt gives that no way to tell a missing file from an empty one: both come back
 * with no body, and only a file with bytes in it reports 200 (604-616). So a
 * record reads as nothing until it is a whole JSON document, and a done marker
 * counts only when it has content.
 *
 * `attempt` is not here. It makes each read a different URL so Qt does not serve
 * the first copy for ever, and a File has no such cache.
 */
class ThemeFiles(private val dataRoot: File) : ThemeBridge {

    // _readJson (591-603): a body that does not parse is swallowed and reads as no record.
    override fun pendingJob(jobId: String): JSONObject? =
        runCatching { JSONObject(File(dataRoot, "pending/$jobId.json").readText()) }.getOrNull()

    // _exists: status 200, which is "exists and is not empty".
    override fun jobDone(jobId: String): Boolean =
        File(dataRoot, "done/$jobId.done").let { it.isFile && it.length() > 0 }
}

/**
 * The desktop transport: the daemon's `/jobs/{id}` (BridgeApi.js:420-431, 449-480).
 *
 * Nothing waits for an answer. A poll sends a request and returns what an earlier
 * one brought back, so the hub is always a poll behind the daemon, and the first
 * poll of a job has nothing to return. The answer is stored when it arrives,
 * which is after the function that asked has finished: [betweenTurns].
 *
 * [get] is the request: path and query in, the body out whatever the status,
 * null when nobody answered. It is made at once, so the daemon is read at the
 * moment the theme asked, and its answer is held back until the turn is over —
 * what a loopback daemon polled every two seconds amounts to.
 */
class ThemeHttp(private val get: (pathAndQuery: String) -> String?) : ThemeBridge {

    private val results = HashMap<String, JSONObject>()   // _results
    private val daemonJobs = HashSet<String>()            // _daemonJobs
    private val inFlight = ArrayList<() -> Unit>()

    /**
     * requestScan (257-259) through _requestJob: the id is the caller's, and the
     * daemon's answer is kept only when it is a refusal.
     */
    fun requestScan(roots: String, jobId: String): String {
        daemonJobs += jobId
        httpAsync("/scan?roots=${encodeURIComponent(roots)}&jobId=${encodeURIComponent(jobId)}") { resp ->
            if (resp.opt("status") == "error") results[jobId] = resp
        }
        return jobId
    }

    // _pollJob. An error that finds nothing stored is taken for the 404 of a
    // daemon that restarted and forgot the job, and stored as that — also when
    // it is the job's own error, arriving before any poll had seen the job run.
    // The next answer finds that stored and replaces it with what the daemon said.
    override fun pendingJob(jobId: String): JSONObject? {
        daemonJobs += jobId
        httpAsync("/jobs/" + encodeURIComponent(jobId)) { p ->
            if (p.opt("status") == "error" && results[jobId] == null)
                results[jobId] = JSONObject().put("status", "error").put("error", "job unknown to the bridge")
            else results[jobId] = p
        }
        return results[jobId]
    }

    override fun jobDone(jobId: String): Boolean {
        val c = results[jobId]
        if (jobId in daemonJobs) return c != null && truthy(c.opt("status")) && c.opt("status") != "running"
        return c != null
    }

    override fun betweenTurns() {
        val arrived = inFlight.toList()
        inFlight.clear()
        arrived.forEach { it() }
    }

    // _httpAsync (538-557): a body that is not JSON is a daemon that did not answer.
    private fun httpAsync(pathAndQuery: String, onDone: (JSONObject) -> Unit) {
        val payload = get(pathAndQuery)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().put("status", "error").put("error", "no response from bridge daemon")
        inFlight += { onDone(payload) }
    }
}

/**
 * The hub's side: the properties its popup is bound to, and the two functions
 * that set them. Each call is a run of the hub's code on its own, as the timers
 * that make them are.
 */
class ThemeScanReader(private val bridge: ThemeBridge) {

    // root._hasherJobId and the rest, without the prefix.
    var jobId = ""; private set
    var pollCount = 0; private set
    /** "" until the first record is read, then "running", "error" or "done". */
    var status = ""; private set
    var percent = 0; private set
    var processed = 0; private set
    var total = 0; private set
    /** The line under the bar: a file name while it runs, the message or the error at the end. */
    var currentFile = ""; private set
    var newEntries = 0; private set
    var cached = 0; private set

    /** api.memory's `hasher_active_jobId`, the one thing that outlives a reload of the theme. */
    var activeJobId = ""; private set

    /** What _launchExternalHasher does with the id requestScan returned (2948-2964). */
    fun launched(jobId: String) {
        this.jobId = jobId
        pollCount = 0
        activeJobId = jobId
        status = ""
        processed = 0
        total = 0
        percent = 0
        currentFile = "Starting..."
        newEntries = 0
    }

    /** _readHasherProgress: one tick of the poll timer. */
    fun readHasherProgress() {
        bridge.betweenTurns()
        if (jobId.isEmpty()) return
        pollCount++

        val data = bridge.pendingJob(jobId)

        if (data != null) {
            percent = Math.round(number(data.opt("progress")) * 100).toInt()
            val message = data.opt("message") as? String ?: ""
            currentFile = message
            // "[X/Y] filename.nes" gives the two counters and the name.
            PROGRESS.find(message)?.let { m ->
                processed = m.groupValues[1].toInt()
                total = m.groupValues[2].toInt()
                currentFile = m.groupValues[3]
            }
            if (data.has("newEntries")) newEntries = parseIntOrZero(data.opt("newEntries"))
            if (data.has("cachedHits")) cached = parseIntOrZero(data.opt("cachedHits"))
            if (data.opt("status") == "error") {
                status = "error"
                currentFile = listOf(data.opt("error"), data.opt("message")).firstOrNull(::truthy)?.toString()
                    ?: "Scan failed"
                activeJobId = ""
                return
            }
            if (data.opt("status") == "running") {
                status = "running"
                return
            }
        }

        // The marker, or no record at all once the request has had a few polls to
        // become one. A record that is neither running nor an error — "done" — is
        // not finished by itself: it waits here for the marker.
        val finished = bridge.jobDone(jobId) || (data == null && pollCount > 4)
        if (finished) {
            status = "done"
            percent = 100
            activeJobId = ""
        }
    }

    /** _activeScanJobId: the remembered scan if it is still running, else "" and it is forgotten. */
    fun activeScanJobId(): String {
        bridge.betweenTurns()
        val saved = activeJobId
        if (saved.isEmpty()) return ""
        if (bridge.jobDone(saved)) {
            activeJobId = ""
            return ""
        }
        val d = bridge.pendingJob(saved)
        if (d != null && d.opt("status") == "running") return saved
        activeJobId = ""
        return ""
    }

    private companion object {
        // What JavaScript's \s matches and Java's does not: the no-break space and
        // the other Unicode ones.
        const val SPACE = """[\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]"""

        // /^\[(\d+)\/(\d+)\]\s*(.*)$/ — with no flags, so `$` is the end of the
        // text and nothing else, which Java spells \z, and `.` stops at the four
        // line ends JavaScript knows.
        val PROGRESS = Regex("""^\[(\d+)/(\d+)]""" + SPACE + """*([^\n\r\u2028\u2029]*)\z""")
    }
}

/** What `a || b` tests: null, false, 0, NaN and "" do not count. */
private fun truthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL -> false
    is Boolean -> v
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> v.isNotEmpty()
    else -> true
}

/** `data.progress || 0`, for the numbers the Bridge writes. */
private fun number(v: Any?): Double = if (v is Number && truthy(v)) v.toDouble() else 0.0

/** `parseInt(v, 10) || 0`: the digits the value's text begins with, 0 when it begins with none. */
private fun parseIntOrZero(v: Any?): Int =
    Regex("""^\s*([+-]?\d+)""").find(v.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0

/** encodeURIComponent: no `+` for a space, and `! ' ( ) ~` left as they are. */
private fun encodeURIComponent(s: String): String =
    URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
