package com.pegasus.bridge.pegasus

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.pegasus.bridge.core.BridgeLog

/**
 * Which installed apps claim to open a given kind of ROM.
 *
 * Asking the system this was tried once and abandoned as useless — the probe
 * asked about a bare `file://` path with no MIME type and got nothing back on a
 * device that in fact had several emulators able to take one. The probe was
 * wrong, not the idea: an Android intent filter is matched on **scheme, MIME
 * type and path together**, and a `content://` URI carrying a type matches a
 * great deal that a bare path does not.
 *
 * ## Why the answer needs a control
 *
 * Asked properly, the question over-answers. On the tablet this was written
 * against, `application/zip` returns fifteen activities and eleven of them are
 * file managers, archivers, an APK splitter, an ebook reader and a 3D modelling
 * app. A raw list is not an answer; it is a heap.
 *
 * The heap is separable, and cheaply. An app that declares
 * `pathPattern=".*\\.dsk"` answers for a `.dsk` and not for a `.wxyz`; an app
 * that declares `mimeType="application/octet-stream"` with no pattern answers
 * for both, because it is not answering about the extension at all. So every
 * query is run twice — once for the real extension and once for [CONTROL], an
 * extension nothing can plausibly have claimed — and the control's answers are
 * **subtracted**. What remains asked for this extension by name.
 *
 * Measured, on the same device, in the same minute:
 *
 * | query | before | after |
 * | --- | --- | --- |
 * | `.dsk` + octet-stream | 24 | ColEm |
 * | `.iso` + octet-stream | 24 | PPSSPP |
 * | `.dsk`, no MIME | 6 | Azimuth |
 * | `.iso`, no MIME | 6 | VLC |
 * | `.adf` + octet-stream | 23 | nothing |
 *
 * Four emulators and no file managers, from a heap of twenty-four.
 *
 * ## What it still cannot tell you
 *
 * Two things, and both matter enough to be returned rather than hidden.
 *
 * An app that declares a MIME type broadly is **in** the control set and cannot
 * be separated from a file manager by this method at all. MAME4droid and CPCemu
 * are exactly that: they take `application/zip` and any path, so they arrive
 * alongside RAR and Samsung My Files and there is no signal here to rank them
 * apart. They are reported as [Candidate.specific] = false, and a caller that
 * shows them must say what it is showing.
 *
 * And a manifest states that a door exists, never what to say at it. Nothing
 * here establishes which of `{file.path}`, `{file.uri}` or `{file.documenturi}`
 * an app actually wants — that is a property only trying it establishes, and
 * ColEm is the standing reminder: it takes `{file.uri}` and answers a wrong one
 * by opening its own demo ROM rather than by failing.
 *
 * So this is a generator of candidates. It proposes; it never adds.
 */
object IntentDiscovery {

    /**
     * The extension used to measure how much of an answer was not about the
     * extension.
     *
     * Deliberately absurd and deliberately fixed: a random one per call would
     * make two runs disagree for no reason a reader could see, and this is
     * short enough to appear in a log without explanation being needed.
     */
    const val CONTROL = "wxyzcontrol"

    /** The MIME types worth asking under, plus the no-type question. */
    private val TYPES = listOf(null, "application/octet-stream", "application/zip")

    /** One activity that answered, flattened out of the framework's ResolveInfo. */
    data class Handler(
        val packageName: String,
        val activity: String,
        val label: String = ""
    )

    data class Candidate(
        val packageName: String,
        val activity: String,
        val label: String,
        /** The extension that produced it. */
        val extension: String,
        /** The MIME type it answered under, or null for the typeless question. */
        val mimeType: String?,
        /**
         * Whether it survived the control subtraction.
         *
         * True: it asked for this extension by name. False: it answers for
         * anything of this type, which is what a file manager does and also
         * what MAME4droid does.
         */
        val specific: Boolean,
        /** Already in the emulator table, built-in or from `emulators.json`. */
        val known: Boolean,
        val because: String
    )

    data class Result(
        val candidates: List<Candidate>,
        /** How many activities the control question returned, per query. */
        val controlSize: Map<String, Int>,
        val note: String
    )

    /**
     * Ask the package manager which activities would open each of [extensions].
     *
     * [knownPackages] are reported with `known = true` rather than dropped:
     * "the system agrees with the table" is worth seeing, and it is also the
     * only evidence that the query works at all on a device where nothing new
     * turns up.
     */
    fun discover(
        pm: PackageManager,
        extensions: List<String>,
        knownPackages: Set<String> = emptySet()
    ): Result = discover(extensions, knownPackages) { ext, type ->
        resolve(pm, ext, type).map {
            Handler(it.activityInfo.packageName, it.activityInfo.name,
                    runCatching { it.loadLabel(pm).toString() }.getOrDefault(""))
        }
    }

    /**
     * The same thing against an injected [ask], so the subtraction can be
     * tested without a device.
     *
     * The framework half — building the intent, and asking under a `content://`
     * URI rather than a bare path — is the part that was wrong the first time
     * and is worth keeping separable from the part that decides what the answer
     * means.
     */
    fun discover(
        extensions: List<String>,
        knownPackages: Set<String> = emptySet(),
        ask: (ext: String, type: String?) -> List<Handler>
    ): Result {
        val wanted = extensions.map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() && it != CONTROL }
            .distinct()
        val found = mutableListOf<Candidate>()
        val controlSize = mutableMapOf<String, Int>()

        for (type in TYPES) {
            val typeLabel = type ?: "no type"
            val control = ask(CONTROL, type).map { it.key() }.toSet()
            controlSize[typeLabel] = control.size

            for (ext in wanted) {
                for (h in ask(ext, type)) {
                    val pkg = h.packageName
                    val isSpecific = h.key() !in control
                    found += Candidate(
                        packageName = pkg,
                        activity    = h.activity,
                        label       = h.label,
                        extension   = ext,
                        mimeType    = type,
                        specific    = isSpecific,
                        known       = pkg in knownPackages,
                        because     = if (isSpecific)
                            "declares a filter for '.$ext' under $typeLabel — it did not answer " +
                            "for '.$CONTROL', so it asked for this extension by name"
                        else
                            "answers for anything under $typeLabel, including '.$CONTROL' — " +
                            "this is what a file manager looks like, and also what an emulator " +
                            "that declares only a MIME type looks like"
                    )
                }
            }
        }

        // One row per package per extension. The same activity answering under
        // two types is one fact, and the specific answer is the one to keep —
        // ColEm matches `.dsk` both as a pattern and as octet-stream, and
        // reporting it twice, once as a finding and once as noise, would be
        // worse than reporting either alone.
        val out = found
            .groupBy { it.packageName to it.extension }
            .map { (_, rows) -> rows.firstOrNull { it.specific } ?: rows.first() }

        val specifics = out.count { it.specific }
        return Result(
            // Specific first, then by package, so a review screen reads
            // top-down from "asked for this" to "takes anything".
            candidates = out.sortedWith(compareByDescending<Candidate> { it.specific }
                                            .thenBy { it.packageName }),
            controlSize = controlSize,
            note = "$specifics of ${out.size} survived the control subtraction. " +
                   "A surviving candidate declares a filter for the extension; it does not " +
                   "establish which placeholder it wants, which only launching it can."
        )
    }

    private fun resolve(pm: PackageManager, ext: String, type: String?): List<ResolveInfo> {
        // A content:// URI and not a file:// path: the first attempt at this
        // asked with a bare path, matched almost nothing, and the idea was
        // written off on the strength of it.
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/probe%3Aprobe.$ext")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (type != null) setDataAndType(uri, type) else data = uri
        }
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            BridgeLog.w(TAG, "query for .$ext (${type ?: "no type"}) failed: ${t.message}")
            emptyList()
        }
    }

    private fun Handler.key() = "$packageName/$activity"

    private const val TAG = "IntentDiscovery"
}
