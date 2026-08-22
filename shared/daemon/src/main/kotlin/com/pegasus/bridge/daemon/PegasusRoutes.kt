package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.daemon.MicroHttpServer.Request
import com.pegasus.bridge.daemon.MicroHttpServer.Response
import com.pegasus.bridge.pegasus.AssetLayout
import com.pegasus.bridge.pegasus.EmulatorDiscovery
import com.pegasus.bridge.pegasus.ExportManifest
import com.pegasus.bridge.pegasus.MediaExporter
import com.pegasus.bridge.pegasus.MetadataFile
import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The endpoints that reach into the user's library rather than the Bridge's own
 * data root.
 *
 * Kept apart from [BridgeRouter]'s other verbs on purpose. Everything else here
 * reads a remote API and writes inside `<dataRoot>`; these two write into
 * directories the user owns and edits by hand, which is a different risk and
 * deserves a different file to argue about.
 *
 * Two rules run through all of it:
 *
 * - **Nothing is written without being asked.** Discovery answers with
 *   candidates; a separate call applies one. Export reports what it *would* do
 *   when asked to.
 * - **Nothing is written that cannot be taken back.** Every file the Bridge puts
 *   in the library is recorded in [ExportManifest], and `revert` removes exactly
 *   those and nothing else.
 */
class PegasusRoutes(private val paths: BridgePaths) {

    private val manifestFile get() = File(paths.cache, ExportManifest.FILE_NAME)

    /** Reopened per request: the file is small, and a stale copy would lie about ownership. */
    private fun exporter() = MediaExporter(
        ExportManifest(manifestFile),
        { f, t -> BridgePaths.writeAtomic(f, t) },
        paths.replaced)

    fun handle(req: Request): Response? = when (req.path) {
        "/emulators"          -> emulators()
        "/emulators/apply"    -> applyEmulator(req)
        "/emulators/revert"   -> revertEmulator(req)
        "/collections"        -> collections(req)
        "/export/media"       -> exportMedia(req)
        "/export/status"      -> exportStatus()
        "/export/revert"      -> exportRevert(req)
        "/export/migrate"     -> exportMigrate(req)
        else                  -> null
    }

    // ── Emulator discovery ──────────────────────────────────────────────────

    /**
     * What is installed. Reads nothing of the user's and writes nothing at all.
     */
    private fun emulators(): Response = try {
        val found = EmulatorDiscovery.discover()
        val arr = JSONArray()
        for (c in found) {
            arr.put(JSONObject()
                .put("id", c.id)
                .put("displayName", c.displayName)
                .put("platforms", JSONArray(c.platforms))
                .put("executable", c.executable)
                .put("launchCommand", c.launchCommand)
                .put("kind", c.kind.name.lowercase())
                // The difference between "this binary told us what it is" and
                // "a file with the right name exists", which is what decides
                // whether a person should accept the proposal without checking.
                .put("verified", c.verified)
                .put("version", c.version)
                .put("confidence", c.confidence))
        }
        Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("count", arr.length())
            .put("emulators", arr)
            .toString())
    } catch (t: Throwable) {
        BridgeLog.e(TAG, "emulator discovery failed", t)
        Response.serverError(t.message ?: t.javaClass.simpleName)
    }

    /**
     * The collections under the given roots, and what each would be proposed.
     *
     * This is the review screen's data: the launch command a collection has now,
     * the one discovery suggests, and whether that suggestion was verified. It
     * writes nothing.
     */
    private fun collections(req: Request): Response {
        val roots = rootsOf(req) ?: return Response.badRequest("missing roots")
        val found = runCatching { EmulatorDiscovery.discover() }.getOrDefault(emptyList())

        val arr = JSONArray()
        for (c in MetadataFile.collectionsUnder(roots)) {
            val platform = c.shortName.ifEmpty { c.directory.name }
            val best = EmulatorDiscovery.bestFor(platform, found)
            arr.put(JSONObject()
                .put("name", c.name)
                .put("shortName", c.shortName)
                .put("directory", c.directory.absolutePath)
                .put("metadataFile", c.file.absolutePath)
                .put("extensions", JSONArray(c.extensions))
                .put("currentLaunch", c.launch)
                // The observation that motivated this whole endpoint: an `am start`
                // line on a desktop is a collection nothing can launch, and no
                // error anywhere says so.
                .put("launchRunsHere", launchLooksRunnable(c.launch))
                .put("mediaStyle", AssetLayout.detectStyle(c.directory).name.lowercase())
                .put("proposal", best?.let {
                    JSONObject()
                        .put("emulator", it.id)
                        .put("displayName", it.displayName)
                        .put("launchCommand", it.launchCommand)
                        .put("verified", it.verified)
                        .put("needsCore", it.launchCommand.contains("{core}"))
                } ?: JSONObject.NULL))
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("count", arr.length())
            .put("collections", arr)
            .toString())
    }

    /**
     * Whether a launch line could run on this machine at all.
     *
     * Only the obvious case is claimed: `am start` is Android's activity manager
     * and does not exist on a desktop. Anything else is reported as runnable
     * rather than second-guessed — a launch command can legitimately be a shell
     * pipeline, and declaring one broken because it looked odd would be worse
     * than saying nothing.
     */
    private fun launchLooksRunnable(launch: String): Boolean {
        if (launch.isBlank()) return false
        val first = launch.trimStart().substringBefore(' ').substringBefore('\n')
        val androidOnly = setOf("am", "monkey", "pm")
        val isAndroid = first in androidOnly
        return !(isAndroid && !System.getProperty("os.name").orEmpty().contains("Android"))
    }

    /**
     * Writes a Bridge-owned overlay for one collection.
     *
     * Requires the launch command explicitly. Discovery proposes; this applies
     * what a person chose, and re-deriving it here would quietly turn a review
     * step into an automatic one.
     *
     * ── Why an overlay alone is not enough ─────────────────
     *
     * The first version of this wrote the overlay beside the user's file and
     * called it done, on the assumption that a later file overrides an earlier
     * one. Reading Pegasus' parser settled it the other way:
     * `get_or_create_collection(name)` returns the **same** collection object for
     * both files and `setCommonLaunchCmd` overwrites, so the last one parsed
     * wins — and `find_metafiles_in` iterates with a bare `QDirIterator` and no
     * sort flag, so which one that is depends on the filesystem.
     *
     * Two files declaring `launch` for one collection is therefore a coin toss,
     * not an override. Exactly one declaration is the only defined state, so:
     *
     * - if the user's file sets no launch, the overlay alone is enough;
     * - if it does, this refuses, and says so, unless `standAside=1` — which
     *   backs the file up and comments out **only** its launch block, leaving
     *   everything else and every original line legible as a comment.
     */
    private fun applyEmulator(req: Request): Response {
        val dir = req.param("directory")?.let(::File)
            ?: return Response.badRequest("missing directory")
        if (!dir.isDirectory) return Response.badRequest("no such directory: $dir")
        val launch = req.param("launch")?.takeIf { it.isNotBlank() }
            ?: return Response.badRequest("missing launch — discovery proposes, it does not apply")
        if (launch.contains("{core}"))
            return Response.badRequest("the launch command still contains {core}: pick one first")

        val existing = MetadataFile.readCollection(dir)
        val name = req.param("name") ?: existing?.name ?: dir.name
        val shortName = req.param("shortName") ?: existing?.shortName.orEmpty()
        val target = File(dir, OVERLAY_FILE)

        val theirFile = existing?.file
        val conflicts = theirFile != null && theirFile != target &&
                        MetadataFile.declaresLaunch(theirFile)
        val standAside = req.param("standAside") == "1"

        if (conflicts && !standAside) {
            return Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "conflict")
                .put("collection", name)
                .put("conflictingFile", theirFile!!.absolutePath)
                .put("error",
                    "${theirFile.name} already sets a launch command for '$name'. Pegasus keeps " +
                    "whichever file it parses last and does not sort them, so writing an overlay " +
                    "beside it would take effect only sometimes.")
                .put("hint", "pass standAside=1 to back that file up and comment out its launch " +
                             "block, leaving one declaration; /emulators/revert undoes both")
                .toString())
        }

        return try {
            var commented = false
            if (conflicts) commented = MetadataFile.commentOutLaunch(theirFile!!)
            val text = MetadataFile.renderCollection(
                name = name, shortName = shortName, launch = launch,
                preserve = existing?.raw ?: emptyMap(),
                note = "Delete this file to go back to " +
                       (theirFile?.name ?: "no launch command") + ".")
            BridgePaths.writeAtomic(target, text)
            Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "ok")
                .put("written", target.absolutePath)
                .put("collection", name)
                .put("commentedOutLaunchIn", if (commented) theirFile!!.absolutePath else JSONObject.NULL)
                .put("backup", if (commented)
                    theirFile!!.path + MetadataFile.BACKUP_SUFFIX else JSONObject.NULL)
                .toString())
        } catch (t: Throwable) {
            BridgeLog.e(TAG, "could not write the overlay for $dir", t)
            Response.serverError(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Removes the overlay and puts back whatever it stood aside from. */
    private fun revertEmulator(req: Request): Response {
        val dir = req.param("directory")?.let(::File)
            ?: return Response.badRequest("missing directory")
        val overlay = File(dir, OVERLAY_FILE)
        val removed = overlay.isFile && overlay.delete()

        // The backup belongs to whichever file was stood aside from, which is
        // whatever is left once the overlay is gone.
        val theirs = MetadataFile.findIn(dir)
        val restored = theirs != null && MetadataFile.restoreBackup(theirs)

        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("removedOverlay", removed)
            .put("restored", if (restored) theirs!!.absolutePath else JSONObject.NULL)
            .toString())
    }

    // ── Media export ────────────────────────────────────────────────────────

    /**
     * Copies one fetched picture into the collection's media directory.
     *
     * `dryRun=1` answers with what it would do and touches nothing, which is what
     * a review screen wants before it offers a button.
     */
    private fun exportMedia(req: Request): Response {
        val source = req.param("source")?.let(::File)
            ?: return Response.badRequest("missing source")
        val romFile = req.param("file")?.let(::File)
            ?: return Response.badRequest("missing file")
        val kind = req.param("kind") ?: return Response.badRequest("missing kind")
        val variant = req.param("variant").orEmpty()
        val collectionDir = req.param("collection")?.let(::File)
            ?: romFile.parentFile
            ?: return Response.badRequest("cannot tell which collection this ROM is in")

        // `mirrorTo` writes the same tree somewhere else entirely. Pegasus will
        // not read it — nothing outside a configured game directory is searched —
        // and saying so is better than letting somebody discover it by wondering
        // why no covers appeared.
        val style = req.param("style")?.uppercase()?.let {
            runCatching { AssetLayout.Style.valueOf(it) }.getOrNull()
        } ?: AssetLayout.detectStyle(collectionDir)

        val mediaRoot = req.param("root")?.uppercase()?.let {
            runCatching { AssetLayout.Root.valueOf(it) }.getOrNull()
        } ?: AssetLayout.Root.BRIDGE
        // `skraper/` is read only by the Skraper provider. Writing there under
        // the native layout produces files nothing will ever look at, so it is
        // refused here rather than discovered as missing covers later.
        if (!mediaRoot.isReadBy(style))
            return Response.badRequest(
                "root=${mediaRoot.name.lowercase()} is not read by a collection using " +
                "the ${style.name.lowercase()} layout — use bridge or collection")

        val destination = req.param("mirrorTo")?.takeIf { it.isNotBlank() }
            ?.let { MediaExporter.Destination.Mirror(File(it)) }
            ?: MediaExporter.Destination.InCollection(collectionDir, mediaRoot)

        if (req.param("dryRun") == "1") {
            val assetKind = AssetLayout.kindOf(kind)
                ?: return Response.badRequest("no Pegasus asset slot for kind '$kind'")
            val base = AssetLayout.completeBaseName(romFile)
            val ext = source.extension.lowercase().ifEmpty { if (assetKind.video) "mp4" else "png" }
            val target = when (destination) {
                is MediaExporter.Destination.Mirror ->
                    AssetLayout.pathForBare(destination.root, style, assetKind, base, ext)
                is MediaExporter.Destination.InCollection ->
                    AssetLayout.pathFor(destination.collectionDir, style, assetKind, base, ext,
                                        mediaRoot = mediaRoot)
            }
            return Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "ok")
                .put("outcome", "dryRun")
                .put("target", target.absolutePath)
                .put("style", style.name.lowercase())
                .put("root", mediaRoot.name.lowercase())
                .put("readByPegasus", destination is MediaExporter.Destination.InCollection)
                .put("occupied", target.isFile && !ExportManifest(manifestFile).owns(target))
                .toString())
        }

        // Two states only, and neither destroys anything. The old
        // `replaceForeign=1` overwrote, which was the last destructive path in
        // the project: a hand-made box scan traded for a scraped one with no way
        // back. Replacing now means setting the original aside.
        val onConflict = if (req.param("replace") == "1")
            MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL
        else MediaExporter.Conflict.KEEP_THEIRS

        val ex = exporter()
        val outcome = ex.export(
            source = source, romFile = romFile, destination = destination,
            bridgeKind = kind, variant = variant, style = style,
            sourceName = req.param("provider") ?: "ss",
            onConflict = onConflict)
        ex.save()

        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("style", style.name.lowercase())
            .put("root", mediaRoot.name.lowercase())
            .put("readByPegasus", destination is MediaExporter.Destination.InCollection)
        return when (outcome) {
            is MediaExporter.Outcome.Written -> Response.json(payload
                .put("status", "ok").put("outcome", "written")
                .put("target", outcome.target.absolutePath)
                .put("bytes", outcome.bytes).toString())
            is MediaExporter.Outcome.Replaced -> Response.json(payload
                .put("status", "ok").put("outcome", "replaced")
                .put("target", outcome.target.absolutePath)
                .put("bytes", outcome.bytes)
                // Where the picture that was there has gone. Nothing was deleted.
                .put("preservedOriginal", outcome.preserved.absolutePath).toString())
            is MediaExporter.Outcome.UpToDate -> Response.json(payload
                .put("status", "ok").put("outcome", "upToDate")
                .put("target", outcome.target.absolutePath).toString())
            // 200 and not an error: the user's own file winning is the system
            // working, and a caller has to be able to offer them the choice.
            is MediaExporter.Outcome.Occupied -> Response.json(payload
                .put("status", "ok").put("outcome", "occupied")
                .put("target", outcome.target.absolutePath)
                .put("hint", "pass replace=1 to put ours here and keep theirs in " +
                             "the Bridge's replaced/ directory; revert undoes both")
                .toString())
            is MediaExporter.Outcome.Unsupported ->
                Response.badRequest("no Pegasus asset slot for kind '${outcome.kind}'")
            is MediaExporter.Outcome.Failed ->
                Response.serverError(outcome.reason)
        }
    }

    /**
     * Moves what has already been exported into a different media root.
     *
     * Exists so choosing the wrong one is a `mv` rather than sixty more API
     * requests. Only files in the manifest are touched, so nothing of the user's
     * can be caught up in it.
     */
    private fun exportMigrate(req: Request): Response {
        val to = req.param("root")?.uppercase()?.let {
            runCatching { AssetLayout.Root.valueOf(it) }.getOrNull()
        } ?: return Response.badRequest("missing or unknown root (bridge, collection, skraper)")

        val ex = exporter()
        val r = ex.migrate(to, req.param("collection"))
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("root", to.name.lowercase())
            .put("moved", r.moved)
            .put("alreadyThere", r.alreadyThere)
            .put("failed", r.failed)
            .toString())
    }

    private fun exportStatus(): Response {
        val m = ExportManifest(manifestFile)
        val byCollection = JSONObject()
        for (r in m.all()) {
            val n = byCollection.optInt(r.collection, 0)
            byCollection.put(r.collection, n + 1)
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("count", m.size)
            .put("replacedOriginals", m.all().count { it.replaced.isNotEmpty() })
            .put("byCollection", byCollection)
            // Files the Bridge wrote that somebody has since removed. Not an
            // error — worth showing, because it usually means a cleanup tool ran.
            .put("missing", m.missing().size)
            .toString())
    }

    private fun exportRevert(req: Request): Response {
        val ex = exporter()
        val r = ex.revert(req.param("collection"))
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("removed", r.removed)
            // Left alone: their bytes no longer match what was written, so
            // somebody replaced them deliberately.
            .put("keptBecauseChanged", r.changed)
            .put("alreadyGone", r.absent)
            // Pictures the export had displaced, now back where they were.
            .put("originalsRestored", r.restored)
            .toString())
    }

    private fun rootsOf(req: Request): List<File>? =
        req.param("roots")?.split('|', ',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(::File)
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "PegasusRoutes"
        /**
         * Sorted after `metadata.pegasus.txt`, and named so nobody has to guess
         * whose it is. Pegasus reads every `*.metadata.pegasus.txt` in a
         * directory, so this is an addition rather than a replacement.
         */
        const val OVERLAY_FILE = "zz-pegasusbridge.metadata.pegasus.txt"
    }
}
