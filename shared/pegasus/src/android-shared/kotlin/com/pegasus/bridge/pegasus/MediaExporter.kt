package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.ArtifactKey
import com.pegasus.bridge.core.BridgeLog
import java.io.File

/**
 * Copies a picture the Bridge fetched into the place Pegasus will look for it.
 *
 * The Bridge's `artwork/` directory is content-addressed — a file is named after
 * the picture it holds, so two regional covers of one game cannot collide. That
 * is the right shape for a cache and the wrong shape for Pegasus, which finds an
 * asset by the ROM's name and nothing else. This is the translation between
 * them, and it is the only place in the project that writes into the user's
 * library.
 *
 * Three rules, and they are the reason this is a separate class rather than four
 * lines inside the scraper:
 *
 * 1. **Copy, never move.** `artwork/` stays the cache. If the export is deleted,
 *    re-running it costs a file copy rather than the API quota to fetch again.
 * 2. **Never destroy a picture somebody else put there.** Not "do not overwrite
 *    by default" — never, on any path. See [Conflict].
 * 3. **Write atomically.** A half-copied picture at the target path is
 *    indistinguishable from a good one on the next run, and the theme would show
 *    a broken image with nothing anywhere saying why.
 */
class MediaExporter(
    private val manifest: ExportManifest,
    private val writeAtomic: (File, String) -> Unit,
    /**
     * Where a replaced original is kept.
     *
     * In the Bridge's data root rather than beside the file it came from: the
     * point of the export is a tidy media directory, and leaving
     * `Contra (USA).png.original` next to `Contra (USA).png` would undo that
     * while also giving Pegasus a file to think about.
     */
    private val quarantine: File
) {

    /**
     * What to do about a picture that is already there and is not ours.
     *
     * There is deliberately no "overwrite" here. The first version of this had
     * one, behind a `replaceForeign=1` flag, and it was the only destructive path
     * left in the project — a hand-made box scan traded for a scraped one with no
     * way back. A picture is a few hundred kilobytes; keeping it costs nothing
     * that matters and keeping it is the only version of "replace" a person can
     * change their mind about.
     */
    enum class Conflict {
        /** Leave theirs. The default, and what a first run should do. */
        KEEP_THEIRS,

        /**
         * Move theirs into [quarantine], then write ours.
         *
         * Recorded as a pair, so `revert` deletes ours and puts theirs back where
         * it was, byte for byte.
         */
        REPLACE_KEEPING_ORIGINAL
    }

    /**
     * Where the media tree is built.
     *
     * [InCollection] is the only one Pegasus reads. [Mirror] writes the same tree
     * somewhere else entirely and is honest that nothing will pick it up — it
     * exists so a person can look at what an export *would* place, or keep a copy
     * outside a library they would rather not have written to at all.
     */
    sealed interface Destination {
        /**
         * Inside the collection, in one of Pegasus' three media roots.
         *
         * [mediaRoot] defaults to [AssetLayout.Root.BRIDGE] — `.media/` — which
         * both providers read and both read *last*. That is what makes the
         * collision question go away rather than being answered: the user's own
         * `media/` keeps precedence, nothing of theirs is moved or renamed, and
         * the Bridge's pictures fill only the gaps.
         */
        data class InCollection(
            val collectionDir: File,
            val mediaRoot: AssetLayout.Root = AssetLayout.Root.BRIDGE
        ) : Destination

        /**
         * An arbitrary directory, which **Pegasus will not read**.
         *
         * Only `skraper/`, `media/` and `.media/` are searched, and only inside a
         * configured game directory. A folder called anything else is a private
         * copy — useful for looking at, useless for showing.
         */
        data class Mirror(val root: File) : Destination
    }

    sealed interface Outcome {
        /** Copied. [target] is where Pegasus will now find it. */
        data class Written(val target: File, val bytes: Long) : Outcome

        /**
         * Copied, and the picture that was there is now in [preserved].
         *
         * Both paths are in the manifest, so this is undoable as one action.
         */
        data class Replaced(val target: File, val bytes: Long, val preserved: File) : Outcome

        /** Already there, already correct, already ours. Nothing was done. */
        data class UpToDate(val target: File) : Outcome

        /**
         * Something is already at that path that the Bridge did not put there.
         *
         * Not an error — it is the user's file, and by default it wins. Reported
         * so a UI can offer the choice, which is a decision for a person.
         */
        data class Occupied(val target: File) : Outcome

        data class Failed(val target: File, val reason: String) : Outcome

        /** The Bridge kind has no Pegasus asset slot. */
        data class Unsupported(val kind: String) : Outcome
    }

    /**
     * One asset into one destination.
     *
     * [romFile] decides the name: Pegasus matches the ROM's own base name, so
     * this is taken from the file rather than from the title, which is the whole
     * defect this exists to fix.
     */
    fun export(
        source: File,
        romFile: File,
        destination: Destination,
        bridgeKind: String,
        variant: String,
        style: AssetLayout.Style? = null,
        sourceName: String = "ss",
        onConflict: Conflict = Conflict.KEEP_THEIRS
    ): Outcome {
        val kind = AssetLayout.kindOf(bridgeKind) ?: return Outcome.Unsupported(bridgeKind)
        if (!source.isFile || source.length() == 0L)
            return Outcome.Failed(source, "nothing to copy from ${source.name}")

        val root = when (destination) {
            is Destination.InCollection -> destination.collectionDir
            is Destination.Mirror -> destination.root
        }
        val mediaRoot = when (destination) {
            is Destination.InCollection -> destination.mediaRoot
            // A mirror is not a Pegasus root at all, so the tree is built bare
            // under it rather than nested inside another directory name.
            is Destination.Mirror -> AssetLayout.Root.COLLECTION
        }
        val layout = style ?: when (destination) {
            is Destination.InCollection -> AssetLayout.detectStyle(destination.collectionDir)
            // Nothing reads a mirror, so the only thing that makes one useful is
            // being able to compare it against the collection it shadows.
            is Destination.Mirror -> AssetLayout.Style.SKRAPER
        }

        val romBaseName = AssetLayout.completeBaseName(romFile)
        val ext = source.extension.lowercase().ifEmpty { if (kind.video) "mp4" else "png" }
        val target = if (destination is Destination.Mirror)
            AssetLayout.pathForBare(root, layout, kind, romBaseName, ext)
        else AssetLayout.pathFor(root, layout, kind, romBaseName, ext, mediaRoot = mediaRoot)
        val collectionName = when (destination) {
            is Destination.InCollection -> destination.collectionDir.name
            is Destination.Mirror -> romFile.parentFile?.name.orEmpty()
        }

        if (manifest.isCurrent(target, variant, source.length()))
            return Outcome.UpToDate(target)

        // Somebody else's file. Includes one a previous scraping tool left, and
        // one the user placed by hand — the Bridge cannot tell them apart and
        // must not try.
        val foreign = target.isFile && target.length() > 0 && !manifest.owns(target)
        if (foreign && onConflict == Conflict.KEEP_THEIRS) return Outcome.Occupied(target)

        return try {
            var preserved: File? = null
            if (foreign) {
                preserved = setAside(target, collectionName)
                    ?: return Outcome.Failed(target, "could not set the existing picture aside")
            }

            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            manifest.put(ExportManifest.Record(
                target = target.absolutePath,
                source = sourceName,
                collection = collectionName,
                romBaseName = romBaseName,
                kind = kind.name,
                variant = variant,
                bytes = target.length(),
                writtenAt = System.currentTimeMillis() / 1000L,
                replaced = preserved?.absolutePath.orEmpty()
            ))
            preserved?.let { Outcome.Replaced(target, target.length(), it) }
                ?: Outcome.Written(target, target.length())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            BridgeLog.e(TAG, "could not export ${source.name} to ${target.path}", t)
            Outcome.Failed(target, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Moves [original] into the quarantine and answers where it went.
     *
     * The structure under the quarantine mirrors the library's, so a person
     * looking in there can tell what a file was without consulting the manifest.
     * A name already taken gains a counter rather than overwriting — the one
     * thing this function exists to prevent is a picture being lost, and losing
     * it inside the safety net would be the worst version of that.
     */
    private fun setAside(original: File, collection: String): File? = try {
        val dir = File(File(quarantine, ArtifactKey.sanitize(collection)),
                       original.parentFile?.name?.let(ArtifactKey::sanitize).orEmpty())
        dir.mkdirs()
        var dest = File(dir, original.name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, "${original.nameWithoutExtension} ($n).${original.extension}")
            n++
        }
        // Copy-then-delete rather than rename: the library and the data root are
        // very often different filesystems, and `renameTo` across one silently
        // fails rather than throwing.
        original.copyTo(dest, overwrite = false)
        if (dest.length() != original.length()) {
            dest.delete()
            null
        } else if (original.delete()) dest else { dest.delete(); null }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.e(TAG, "could not set aside ${original.path}", t)
        null
    }

    /**
     * Removes every file this export put in the library, and puts back whatever
     * it displaced.
     *
     * By manifest and never by pattern: a glob over `box2dfront` would take the
     * user's own pictures with it, and the whole point of recording what was
     * written is that taking it back can be exact.
     *
     * A file that has been *changed* since the Bridge wrote it is left alone —
     * somebody replaced it deliberately, and that is a decision, not a leftover.
     * Its preserved original stays in the quarantine rather than being restored
     * over the top of that decision.
     */
    fun revert(collection: String? = null): Reverted {
        var removed = 0; var changed = 0; var absent = 0; var restored = 0
        for (r in manifest.all()) {
            if (collection != null && r.collection != collection) continue
            val f = File(r.target)
            val gone = when {
                !f.isFile -> { absent++; true }
                f.length() != r.bytes -> { changed++; false }
                f.delete() -> { removed++; true }
                else -> { BridgeLog.w(TAG, "could not remove ${f.path}"); false }
            }
            if (!gone) continue
            if (r.replaced.isNotEmpty() && restoreOriginal(File(r.replaced), f)) restored++
            manifest.remove(r.target)
        }
        manifest.save(writeAtomic)
        return Reverted(removed, changed, absent, restored)
    }

    private fun restoreOriginal(preserved: File, target: File): Boolean = try {
        if (!preserved.isFile) false
        else {
            target.parentFile?.mkdirs()
            preserved.copyTo(target, overwrite = true)
            preserved.delete()
            true
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.e(TAG, "could not restore ${preserved.path}", t)
        false
    }

    /**
     * [changed] were left in place: their bytes no longer match what was written,
     * so somebody replaced them on purpose. [restored] counts the originals put back where they were.
     */
    data class Reverted(val removed: Int, val changed: Int, val absent: Int, val restored: Int)

    /**
     * Moves everything already exported into a different media root.
     *
     * Costs no quota — the pictures are already on disk, and re-fetching sixty of
     * them to change which directory they sit in would spend an API's goodwill on
     * a `mv`. Each record is updated in step with its file, so an interrupted
     * migration leaves the manifest describing what is actually there.
     */
    fun migrate(toRoot: AssetLayout.Root, collection: String? = null): Migrated {
        var moved = 0; var alreadyThere = 0; var failed = 0
        for (r in manifest.all()) {
            if (collection != null && r.collection != collection) continue
            val from = File(r.target)
            // <collection>/<root>/<...>: two levels up from a Skraper path, three
            // from a native one. Derived from the record rather than guessed, so a
            // path the Bridge did not write cannot be moved by accident.
            val currentRoot = from.parentFile?.parentFile ?: continue
            if (currentRoot.name == toRoot.dirName) { alreadyThere++; continue }
            val collectionDir = currentRoot.parentFile ?: continue
            val to = File(File(collectionDir, toRoot.dirName), 
                          from.parentFile.name + File.separator + from.name)
            try {
                if (!from.isFile) { failed++; continue }
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
                if (to.length() != from.length()) { to.delete(); failed++; continue }
                from.delete()
                manifest.remove(r.target)
                manifest.put(r.copy(target = to.absolutePath))
                moved++
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                BridgeLog.e(TAG, "could not move ${from.path} to ${to.path}", t)
                failed++
            }
        }
        manifest.save(writeAtomic)
        return Migrated(moved, alreadyThere, failed)
    }

    data class Migrated(val moved: Int, val alreadyThere: Int, val failed: Int)

    fun save() = manifest.save(writeAtomic)

    private companion object { const val TAG = "MediaExporter" }
}
