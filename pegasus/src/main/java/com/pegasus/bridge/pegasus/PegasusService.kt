package com.pegasus.bridge.pegasus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The verbs that reach into the user's library rather than the Bridge's own
 * data root — the Android half of the daemon's `PegasusRoutes`.
 *
 * Kept apart from the other services for the reason that file gives: everything
 * else here reads a remote API and writes inside `/sdcard/PegasusData`, and
 * these write into directories the user owns and edits by hand. That is a
 * different risk and deserves a different place to argue about it.
 *
 * The two rules carry over unchanged:
 *
 * - **Nothing is written without being asked.** Discovery answers with
 *   candidates; a separate call applies one. Export reports what it *would* do
 *   when asked to.
 * - **Nothing is written that cannot be taken back.** Every file the Bridge puts
 *   in the library is recorded in [ExportManifest], and `revert` removes exactly
 *   those and nothing else.
 *
 * ── What differs from the daemon, and why ──
 *
 * The daemon answers an HTTP request; this writes `pegasus/{jobId}.json` and
 * marks the job done, because that is the only channel a Pegasus theme has on
 * Android. The payloads are identical field for field — deliberately, and the
 * shared `EmulatorCatalogue` is what enforces it — so a theme reads one shape
 * whichever shell answered.
 *
 * One endpoint could not be carried over literally. `/export/metadata` takes its
 * game list in the request body, and an intent URI has no body and a length
 * limit besides. So `export-metadata` takes `gamesFile=`, a path to the same
 * JSON. The theme already writes files under `PegasusData/`, so this costs it
 * nothing, and a 700-game collection would not have fitted in a URI.
 */
class PegasusService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Reading the library…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verb  = intent?.getStringExtra(EXTRA_VERB)  ?: run { stopSelf(startId); return START_NOT_STICKY }
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            Paths.ensureAll()
            try {
                val p = params(intent)
                val answer = when (verb) {
                    VERB_EMULATORS        -> emulators(p)
                    VERB_EMULATORS_APPLY  -> applyEmulator(p)
                    VERB_EMULATORS_REVERT -> revertEmulator(p)
                    VERB_COLLECTIONS      -> collections(p)
                    VERB_EXPORT_MEDIA     -> exportMedia(p)
                    VERB_EXPORT_STATUS    -> exportStatus()
                    VERB_EXPORT_REVERT    -> exportRevert(p)
                    VERB_EXPORT_MIGRATE   -> exportMigrate(p)
                    VERB_EXPORT_METADATA  -> exportMetadata(p)
                    VERB_LAUNCH_OPTIONS   -> launchOptions(p)
                    VERB_LAUNCH_SELECT    -> launchSelect(p)
                    VERB_LAUNCH_CLEAR     -> launchClear(p)
                    else                  -> error("verb not implemented: $verb")
                }
                write(jobId, answer)
            } catch (e: Exception) {
                Log.e(TAG, "PegasusService failed for verb=$verb", e)
                write(jobId, error(e.message ?: e.javaClass.simpleName))
            } finally {
                Paths.markDone(jobId)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun params(intent: Intent): Map<String, String> {
        val out = HashMap<String, String>()
        val extras = intent.extras ?: return out
        for (k in extras.keySet()) extras.getString(k)?.let { out[k] = it }
        return out
    }

    private fun write(jobId: String, payload: JSONObject) =
        Paths.writeAtomic(Paths.pegasus(jobId), payload.toString())

    private fun ok() = JSONObject()
        .put("schemaVersion", SchemaVersion.CURRENT).put("status", "ok")

    private fun error(message: String) = JSONObject()
        .put("schemaVersion", SchemaVersion.CURRENT).put("status", "error")
        .put("error", message)

    private val manifestFile get() = Paths.cache(ExportManifest.FILE_NAME)

    /**
     * Where a chosen emulator is remembered.
     *
     * Under `config/` and not `cache/`: a cache may be deleted to reclaim space,
     * and this holds decisions a person made.
     */
    private val preferencesFile get() = File(Paths.CONFIG, LaunchPreferences.FILE_NAME)

    private fun preferences() = LaunchPreferences(preferencesFile)

    private fun savePreferences(p: LaunchPreferences) = p.save(Paths::writeAtomic)

    /** Reopened per request: the file is small, and a stale copy would lie about ownership. */
    private fun exporter() =
        MediaExporter(ExportManifest(manifestFile), Paths::writeAtomic, Paths.REPLACED)

    private fun rootsOf(p: Map<String, String>): List<File>? =
        p["roots"]?.split('|', ',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(::File)
            ?.takeIf { it.isNotEmpty() }

    private fun discover(platform: String? = null) =
        AndroidEmulators.discover(packageManager, platform)

    /** Whether a launch line would do anything on *this* device. */
    private fun launchCheck(launch: String) = LaunchCheck.check(
        launch,
        isAndroid = true,
        installedPackage = { pkg -> AndroidEmulators.versionOf(packageManager, pkg) != null })

    // ── Emulator discovery ──────────────────────────────────────────────────

    /** What is installed. Reads nothing of the user's and writes nothing at all. */
    private fun emulators(p: Map<String, String>): JSONObject {
        val found = discover(p["platform"])
        val arr = JSONArray()
        for (c in found) arr.put(c.toListJson())
        return ok().put("count", arr.length()).put("emulators", arr)
    }

    /**
     * The collections under the given roots, and what each would be proposed.
     *
     * This is the review screen's data: the launch command a collection has now,
     * the one discovery suggests, and whether that suggestion was verified. It
     * writes nothing.
     */
    private fun collections(p: Map<String, String>): JSONObject {
        val roots = rootsOf(p) ?: return error("missing roots")
        val found = runCatching { discover() }.getOrDefault(emptyList())

        val arr = JSONArray()
        for (c in MetadataFile.collectionsUnder(roots)) {
            val platform = c.shortName.ifEmpty { c.directory.name }
            val ranked = EmulatorRanking.rankedFor(platform, found)
            val best = ranked.firstOrNull()
            val check = launchCheck(c.launch)
            arr.put(JSONObject()
                .put("name", c.name)
                .put("shortName", c.shortName)
                .put("directory", c.directory.absolutePath)
                .put("metadataFile", c.file.absolutePath)
                .put("launchFile", c.launchFile?.absolutePath ?: JSONObject.NULL)
                .put("launchIsAmbiguous", c.ambiguousLaunch)
                .put("extensions", JSONArray(c.extensions))
                .put("currentLaunch", c.launch)
                // The Android mirror of the observation that motivated this
                // endpoint: a `flatpak run` line on a tablet is a collection
                // nothing can launch, and neither is an `am start` naming an
                // emulator that has been uninstalled — which is the common case.
                .put("launchRunsHere", check.runnable)
                .put("launchVerdict", check.verdict.name.lowercase())
                .put("launchProblem", if (check.runnable) JSONObject.NULL else check.detail)
                .put("mediaStyle", AssetLayout.detectStyle(c.directory).name.lowercase())
                .put("proposals", JSONArray().also { out ->
                    ranked.forEachIndexed { i, e -> out.put(e.toProposalJson(i, ranked)) }
                })
                .put("proposal", best?.toProposalJson(0, ranked) ?: JSONObject.NULL))
        }
        return ok().put("count", arr.length()).put("collections", arr)
    }

    /**
     * Writes a Bridge-owned overlay for one collection.
     *
     * Requires the launch command explicitly. Discovery proposes; this applies
     * what a person chose, and re-deriving it here would quietly turn a review
     * step into an automatic one.
     *
     * The overlay-versus-conflict reasoning is the daemon's, unchanged, and it
     * is not a desktop concern: Pegasus' parser is the same program here, so two
     * files declaring `launch` for one collection is a coin toss on a tablet too.
     */
    private fun applyEmulator(p: Map<String, String>): JSONObject {
        val dir = p["directory"]?.let(::File) ?: return error("missing directory")
        if (!dir.isDirectory) return error("no such directory: $dir")
        val launch = p["launch"]?.takeIf { it.isNotBlank() }
            ?: return error("missing launch — discovery proposes, it does not apply")
        if (launch.contains("{core}"))
            return error("the launch command still contains {core}: pick one first")

        val existing = MetadataFile.readCollection(dir)
        val name = p["name"] ?: existing?.name ?: dir.name
        val shortName = p["shortName"] ?: existing?.shortName.orEmpty()
        val target = File(dir, OVERLAY_FILE)

        val theirFile = existing?.launchFile ?: existing?.file
        val conflicts = theirFile != null && theirFile != target &&
                        MetadataFile.declaresLaunch(theirFile)
        val standAside = p["standAside"] == "1"

        if (conflicts && !standAside) {
            val theirs = launchCheck(existing?.launch.orEmpty())
            return JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "conflict")
                .put("collection", name)
                .put("conflictingFile", theirFile!!.absolutePath)
                .put("currentLaunch", existing?.launch.orEmpty())
                .put("currentLaunchRunsHere", theirs.runnable)
                .put("currentLaunchProblem", if (theirs.runnable) JSONObject.NULL else theirs.detail)
                .put("error",
                    "${theirFile.name} already sets a launch command for '$name'. Pegasus keeps " +
                    "whichever file it parses last and does not sort them, so writing an overlay " +
                    "beside it would take effect only sometimes.")
                .put("hint", "pass standAside=1 to back that file up and comment out its launch " +
                             "block, leaving one declaration; revert-emulator undoes both")
        }

        // Remembered as well as written: the metadata export regenerates this
        // overlay, and without the choice recorded it would come back with no
        // launch at all.
        p["emulator"]?.takeIf { it.isNotBlank() }?.let { id ->
            val prefs = preferences()
            prefs.setCollection(dir, id)
            savePreferences(prefs)
        }

        var commented = false
        if (conflicts) commented = MetadataFile.commentOutLaunch(theirFile!!)
        val text = MetadataFile.renderCollection(
            name = name, shortName = shortName, launch = launch,
            preserve = existing?.raw ?: emptyMap(),
            note = "Delete this file to go back to " +
                   (theirFile?.name ?: "no launch command") + ".")
        Paths.writeAtomic(target, text)
        return ok()
            .put("written", target.absolutePath)
            .put("collection", name)
            .put("commentedOutLaunchIn", if (commented) theirFile!!.absolutePath else JSONObject.NULL)
            .put("backup", if (commented)
                theirFile!!.path + MetadataFile.BACKUP_SUFFIX else JSONObject.NULL)
    }

    /** Removes the overlay and puts back whatever it stood aside from. */
    private fun revertEmulator(p: Map<String, String>): JSONObject {
        val dir = p["directory"]?.let(::File) ?: return error("missing directory")
        val overlay = File(dir, OVERLAY_FILE)
        val removed = overlay.isFile && overlay.delete()
        val theirs = MetadataFile.findIn(dir)
        val restored = theirs != null && MetadataFile.restoreBackup(theirs)
        return ok()
            .put("removedOverlay", removed)
            .put("restored", if (restored) theirs!!.absolutePath else JSONObject.NULL)
    }

    // ── Choosing an emulator ────────────────────────────────────────────────

    /**
     * What could run this game or this collection, and what is chosen now.
     *
     * Takes `file=` for one game or `directory=` for a whole collection. For a
     * game it reports the collection's choice as well, so a UI can show
     * "inherited" as something different from "chosen here" — which is what makes
     * a *clear* action meaningful.
     */
    private fun launchOptions(p: Map<String, String>): JSONObject {
        val rom = p["file"]?.let(::File)
        val dir = p["directory"]?.let(::File) ?: rom?.parentFile
            ?: return error("missing file or directory")
        if (!dir.isDirectory) return error("no such directory: $dir")

        val collection = MetadataFile.readCollection(dir)
        val platform = collection?.shortName?.ifEmpty { null } ?: dir.name
        val ranked = EmulatorRanking.rankedFor(
            platform, runCatching { discover(platform) }.getOrDefault(emptyList()))

        val prefs = preferences()
        val chosenForCollection = prefs.forCollection(dir)
        val chosenForGame = rom?.let { prefs.forGame(it) }
        val effective = chosenForGame ?: chosenForCollection ?: ranked.firstOrNull()?.id

        val arr = JSONArray()
        ranked.forEachIndexed { i, e ->
            arr.put(e.toProposalJson(i, ranked)
                .put("selected", e.id == effective)
                // Where the selection came from, so a UI can render three states
                // rather than two: chosen here, inherited, or merely the default.
                .put("selectedBy", when {
                    e.id != effective -> JSONObject.NULL
                    chosenForGame == e.id -> "game"
                    chosenForCollection == e.id -> "collection"
                    else -> "default"
                }))
        }

        // A preference naming something no longer installed. Reported rather than
        // silently ignored: the emulator id is stored, not the command, precisely
        // so this is visible instead of becoming a launch line that fails.
        val orphaned = listOfNotNull(chosenForGame, chosenForCollection)
            .filter { id -> ranked.none { it.id == id } }.distinct()

        return ok()
            .put("platform", platform)
            .put("collection", collection?.name ?: dir.name)
            .put("directory", dir.absolutePath)
            .put("file", rom?.absolutePath ?: JSONObject.NULL)
            .put("chosenForGame", chosenForGame ?: JSONObject.NULL)
            .put("chosenForCollection", chosenForCollection ?: JSONObject.NULL)
            .put("effective", effective ?: JSONObject.NULL)
            .put("notInstalled", JSONArray(orphaned))
            .put("options", arr)
    }

    /**
     * Records a choice. Writes nothing into the library by itself.
     *
     * Separate from applying, deliberately: choosing is cheap and reversible,
     * writing into somebody's collection is neither.
     */
    private fun launchSelect(p: Map<String, String>): JSONObject {
        val emulator = p["emulator"]?.takeIf { it.isNotBlank() }
            ?: return error("missing emulator")
        val rom = p["file"]?.let(::File)
        val dir = p["directory"]?.let(::File) ?: rom?.parentFile
            ?: return error("missing file or directory")

        val known = runCatching { discover() }.getOrDefault(emptyList())
        if (known.none { it.id == emulator })
            return error("no installed emulator with id '$emulator' — emulators lists what there is")

        val prefs = preferences()
        if (rom != null) prefs.setGame(rom, emulator) else prefs.setCollection(dir, emulator)
        savePreferences(prefs)

        return ok()
            .put("scope", if (rom != null) "game" else "collection")
            .put("emulator", emulator)
            .put("target", (rom ?: dir).absolutePath)
            .put("hint", "run export-metadata for this collection to write it into Pegasus")
    }

    /** Forgets a choice. A game falls back to its collection's. */
    private fun launchClear(p: Map<String, String>): JSONObject {
        val rom = p["file"]?.let(::File)
        val dir = p["directory"]?.let(::File) ?: rom?.parentFile
            ?: return error("missing file or directory")
        val prefs = preferences()
        val cleared = if (rom != null) prefs.clearGame(rom) else prefs.clearCollection(dir)
        savePreferences(prefs)
        return ok().put("cleared", cleared).put("scope", if (rom != null) "game" else "collection")
    }

    /** The launch command for an emulator id, or empty if it is not installed. */
    private fun commandFor(emulatorId: String?, known: List<EmulatorCandidate>): String =
        emulatorId?.let { id -> known.firstOrNull { it.id == id }?.launchCommand }.orEmpty()

    // ── Media export ────────────────────────────────────────────────────────

    /**
     * Copies one fetched picture into the collection's media directory.
     *
     * `dryRun=1` answers with what it would do and touches nothing, which is what
     * a review screen wants before it offers a button.
     */
    private fun exportMedia(p: Map<String, String>): JSONObject {
        val source = p["source"]?.let(::File) ?: return error("missing source")
        val romFile = p["file"]?.let(::File) ?: return error("missing file")
        val kind = p["kind"] ?: return error("missing kind")
        val variant = p["variant"].orEmpty()
        val collectionDir = p["collection"]?.let(::File) ?: romFile.parentFile
            ?: return error("cannot tell which collection this ROM is in")

        val style = p["style"]?.uppercase()?.let {
            runCatching { AssetLayout.Style.valueOf(it) }.getOrNull()
        } ?: AssetLayout.detectStyle(collectionDir)

        val mediaRoot = p["root"]?.uppercase()?.let {
            runCatching { AssetLayout.Root.valueOf(it) }.getOrNull()
        } ?: AssetLayout.Root.BRIDGE
        if (!mediaRoot.isReadBy(style))
            return error("root=${mediaRoot.name.lowercase()} is not read by a collection using " +
                         "the ${style.name.lowercase()} layout — use bridge or collection")

        val destination = p["mirrorTo"]?.takeIf { it.isNotBlank() }
            ?.let { MediaExporter.Destination.Mirror(File(it)) }
            ?: MediaExporter.Destination.InCollection(collectionDir, mediaRoot)

        if (p["dryRun"] == "1") {
            val assetKind = AssetLayout.kindOf(kind)
                ?: return error("no Pegasus asset slot for kind '$kind'")
            val base = AssetLayout.completeBaseName(romFile)
            val ext = source.extension.lowercase().ifEmpty { if (assetKind.video) "mp4" else "png" }
            val target = when (destination) {
                is MediaExporter.Destination.Mirror ->
                    AssetLayout.pathForBare(destination.root, style, assetKind, base, ext)
                is MediaExporter.Destination.InCollection ->
                    AssetLayout.pathFor(destination.collectionDir, style, assetKind, base, ext,
                                        mediaRoot = mediaRoot)
            }
            return ok()
                .put("outcome", "dryRun")
                .put("target", target.absolutePath)
                .put("style", style.name.lowercase())
                .put("root", mediaRoot.name.lowercase())
                .put("readByPegasus", destination is MediaExporter.Destination.InCollection)
                .put("occupied", target.isFile && !ExportManifest(manifestFile).owns(target))
        }

        // Two states only, and neither destroys anything. Replacing means
        // setting the original aside, not overwriting it.
        val onConflict = if (p["replace"] == "1")
            MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL
        else MediaExporter.Conflict.KEEP_THEIRS

        val ex = exporter()
        val outcome = ex.export(
            source = source, romFile = romFile, destination = destination,
            bridgeKind = kind, variant = variant, style = style,
            sourceName = p["provider"] ?: "ss",
            onConflict = onConflict)
        ex.save()

        val payload = ok()
            .put("style", style.name.lowercase())
            .put("root", mediaRoot.name.lowercase())
            .put("readByPegasus", destination is MediaExporter.Destination.InCollection)
        return when (outcome) {
            is MediaExporter.Outcome.Written -> payload
                .put("outcome", "written")
                .put("target", outcome.target.absolutePath).put("bytes", outcome.bytes)
            is MediaExporter.Outcome.Replaced -> payload
                .put("outcome", "replaced")
                .put("target", outcome.target.absolutePath).put("bytes", outcome.bytes)
                // Where the picture that was there has gone. Nothing was deleted.
                .put("preservedOriginal", outcome.preserved.absolutePath)
            is MediaExporter.Outcome.UpToDate -> payload
                .put("outcome", "upToDate").put("target", outcome.target.absolutePath)
            // "ok" and not an error: the user's own file winning is the system
            // working, and a caller has to be able to offer them the choice.
            is MediaExporter.Outcome.Occupied -> payload
                .put("outcome", "occupied")
                .put("target", outcome.target.absolutePath)
                .put("hint", "pass replace=1 to put ours here and keep theirs in " +
                             "the Bridge's replaced/ directory; revert undoes both")
            is MediaExporter.Outcome.Unsupported ->
                error("no Pegasus asset slot for kind '${outcome.kind}'")
            is MediaExporter.Outcome.Failed -> error(outcome.reason)
        }
    }

    private fun exportStatus(): JSONObject {
        val m = ExportManifest(manifestFile)
        val byCollection = JSONObject()
        for (r in m.all()) byCollection.put(r.collection, byCollection.optInt(r.collection, 0) + 1)
        return ok()
            .put("count", m.size)
            .put("replacedOriginals", m.all().count { it.replaced.isNotEmpty() })
            .put("byCollection", byCollection)
            // Files the Bridge wrote that somebody has since removed. Not an
            // error — worth showing, because it usually means a cleanup tool ran.
            .put("missing", m.missing().size)
    }

    private fun exportRevert(p: Map<String, String>): JSONObject {
        val r = exporter().revert(p["collection"])
        return ok()
            .put("removed", r.removed)
            // Left alone: their bytes no longer match what was written, so
            // somebody replaced them deliberately.
            .put("keptBecauseChanged", r.changed)
            .put("alreadyGone", r.absent)
            .put("originalsRestored", r.restored)
    }

    /**
     * Moves what has already been exported into a different media root.
     *
     * Only files in the manifest are touched, so nothing of the user's can be
     * caught up in it.
     */
    private fun exportMigrate(p: Map<String, String>): JSONObject {
        val to = p["root"]?.uppercase()?.let {
            runCatching { AssetLayout.Root.valueOf(it) }.getOrNull()
        } ?: return error("missing or unknown root (bridge, collection, skraper)")
        val r = exporter().migrate(to, p["collection"])
        return ok().put("root", to.name.lowercase())
            .put("moved", r.moved).put("alreadyThere", r.alreadyThere).put("failed", r.failed)
    }

    /**
     * Writes the scraped metadata for a collection into the Bridge's overlay.
     *
     * The other half of the export: the media directory gives Pegasus the
     * pictures, and without this it still has no description, no genre, no
     * developer and no year, and calls every game after its file — which on a
     * No-Intro library means `Contra (USA)`.
     *
     * Takes the entries from `gamesFile=` rather than the request, for the
     * reason in this class's comment: an intent URI has no body.
     */
    private fun exportMetadata(p: Map<String, String>): JSONObject {
        val dir = p["directory"]?.let(::File) ?: return error("missing directory")
        if (!dir.isDirectory) return error("no such directory: $dir")

        val body = p["gamesFile"]?.let(::File)?.let { f ->
            if (!f.isFile) return error("no such gamesFile: $f")
            f.readText()
        } ?: p["games"] ?: return error("missing gamesFile (or inline games)")

        val entries = try {
            parseGameEntries(body)
        } catch (e: Exception) {
            return error("games must be a JSON array: ${e.message}")
        }
        if (entries.isEmpty()) return error("no games supplied")

        val target = File(dir, OVERLAY_FILE)
        val existing = MetadataFile.readCollection(dir)

        // A ROM another metafile already declares cannot be declared here:
        // `game:` creates a new object every time, and Pegasus refuses a file
        // that belongs to a different one.
        val claimed = GameEntry.claimedElsewhere(dir, ignore = target)
        val (writable0, skipped) = entries.partition { it.fileName !in claimed }

        val prefs = preferences()
        val known = runCatching { discover() }.getOrDefault(emptyList())
        val collectionChoice = prefs.forCollection(dir)
        val perGame = prefs.gamesIn(dir)
        val writable = writable0.map { e ->
            val launch = LaunchPreferences.gameLaunch(
                perGame[e.fileName], collectionChoice) { commandFor(it, known) }
            if (launch.isEmpty()) e else e.copy(launch = launch)
        }

        // A recorded choice is the more recent statement of intent and has to
        // win over the line already in the overlay.
        val launch = LaunchPreferences.collectionLaunch(
            collectionChoice,
            { commandFor(it, known) },
            if (existing?.launchFile?.absolutePath == target.absolutePath) existing.launch else "")

        val text = MetadataFile.renderCollectionWithGames(
            name = existing?.name ?: dir.name,
            shortName = existing?.shortName.orEmpty(),
            launch = launch,
            games = writable,
            preserve = existing?.raw ?: emptyMap(),
            note = "Metadata scraped by PegasusBridge.")

        val payload = ok()
            .put("written", writable.count { it.render().isNotEmpty() })
            .put("nothingToSay", writable.count { it.render().isEmpty() })
            // Named, because "3 skipped" without saying which is not actionable.
            .put("skippedAlreadyClaimed", JSONArray(skipped.map { it.fileName }))
            .put("keptLaunch", launch.isNotEmpty())
            .put("perGameLaunches", writable.count { it.launch.isNotEmpty() })

        if (p["dryRun"] == "1")
            return payload.put("outcome", "dryRun").put("preview", text)

        Paths.writeAtomic(target, text)
        return payload.put("outcome", "written").put("target", target.absolutePath)
    }

    /** The `games` array of [body], in the shape `scrape` answers with. */
    private fun parseGameEntries(body: String): List<GameEntry> {
        if (body.isBlank()) return emptyList()
        val root = JSONObject(body)
        val arr = root.optJSONArray("games") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("fileName").ifEmpty { o.optString("file") }
            if (file.isEmpty()) return@mapNotNull null
            val genres = o.optJSONArray("genres")?.let { g ->
                (0 until g.length()).map { g.optString(it) }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val players = o.optJSONArray("gameModes")?.let { m ->
                (0 until m.length()).map { m.optString(it) }.firstOrNull { it.isNotEmpty() }
            } ?: o.optString("players")
            GameEntry(
                title = o.optString("title"),
                fileName = File(file).name,
                developer = o.optString("developer"),
                publisher = o.optString("publisher"),
                genres = genres,
                description = o.optString("description"),
                players = players.orEmpty(),
                release = GameEntry.normaliseRelease(o.optString("releaseYear")
                    .ifEmpty { o.optString("release") }),
                rating = GameEntry.normaliseRating(o.optString("score")
                    .ifEmpty { o.optString("rating") }),
                source = o.optString("source").takeIf { it.isNotEmpty() }
                    ?.let { "from $it" }.orEmpty()
            )
        }
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Library", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()

    companion object {
        private const val TAG = "PegasusService"
        private const val CHANNEL_ID = "pegasus_library"
        private const val NOTIFICATION_ID = 5

        /**
         * Sorted after `metadata.pegasus.txt`, and named so nobody has to guess
         * whose it is. Pegasus reads every `*.metadata.pegasus.txt` in a
         * directory, so this is an addition rather than a replacement.
         */
        const val OVERLAY_FILE = "zz-pegasusbridge.metadata.pegasus.txt"

        const val EXTRA_VERB   = "verb"
        const val EXTRA_JOB_ID = "jobId"

        const val VERB_EMULATORS        = "emulators"
        const val VERB_EMULATORS_APPLY  = "apply-emulator"
        const val VERB_EMULATORS_REVERT = "revert-emulator"
        const val VERB_COLLECTIONS      = "collections"
        const val VERB_EXPORT_MEDIA     = "export-media"
        const val VERB_EXPORT_STATUS    = "export-status"
        const val VERB_EXPORT_REVERT    = "export-revert"
        const val VERB_EXPORT_MIGRATE   = "export-migrate"
        const val VERB_EXPORT_METADATA  = "export-metadata"
        const val VERB_LAUNCH_OPTIONS   = "launch-options"
        const val VERB_LAUNCH_SELECT    = "launch-select"
        const val VERB_LAUNCH_CLEAR     = "launch-clear"

        /** Every verb this service answers — the router's dispatch table. */
        val VERBS = setOf(
            VERB_EMULATORS, VERB_EMULATORS_APPLY, VERB_EMULATORS_REVERT, VERB_COLLECTIONS,
            VERB_EXPORT_MEDIA, VERB_EXPORT_STATUS, VERB_EXPORT_REVERT, VERB_EXPORT_MIGRATE,
            VERB_EXPORT_METADATA, VERB_LAUNCH_OPTIONS, VERB_LAUNCH_SELECT, VERB_LAUNCH_CLEAR)
    }
}
