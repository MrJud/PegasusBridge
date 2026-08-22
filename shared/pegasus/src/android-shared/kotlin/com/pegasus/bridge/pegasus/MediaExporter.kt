package com.pegasus.bridge.pegasus

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
 * 2. **Never overwrite what the Bridge did not write.** A hand-placed cover is
 *    the more considered of the two. Ownership is decided by [ExportManifest],
 *    not by guessing from a filename.
 * 3. **Write atomically.** A half-copied picture at the target path is
 *    indistinguishable from a good one on the next run, and the theme would show
 *    a broken image with nothing anywhere saying why.
 */
class MediaExporter(
    private val manifest: ExportManifest,
    private val writeAtomic: (File, String) -> Unit
) {

    sealed interface Outcome {
        /** Copied. [target] is where Pegasus will now find it. */
        data class Written(val target: File, val bytes: Long) : Outcome

        /** Already there, already correct, already ours. Nothing was done. */
        data class UpToDate(val target: File) : Outcome

        /**
         * Something is already at that path that the Bridge did not put there.
         *
         * Not an error — it is the user's file, and it wins. Reported so a UI can
         * offer to replace it, which is a decision for a person.
         */
        data class Occupied(val target: File) : Outcome

        data class Failed(val target: File, val reason: String) : Outcome

        /** The Bridge kind has no Pegasus asset slot. */
        data class Unsupported(val kind: String) : Outcome
    }

    /**
     * One asset into one collection.
     *
     * [romFile] decides the name: Pegasus matches the ROM's own base name, so
     * this is taken from the file rather than from the title, which is the whole
     * defect this exists to fix.
     */
    fun export(
        source: File,
        romFile: File,
        collectionDir: File,
        bridgeKind: String,
        variant: String,
        style: AssetLayout.Style = AssetLayout.detectStyle(collectionDir),
        sourceName: String = "ss",
        replaceForeign: Boolean = false
    ): Outcome {
        val kind = AssetLayout.kindOf(bridgeKind) ?: return Outcome.Unsupported(bridgeKind)
        if (!source.isFile || source.length() == 0L)
            return Outcome.Failed(source, "nothing to copy from ${source.name}")

        val romBaseName = AssetLayout.completeBaseName(romFile)
        val ext = source.extension.lowercase().ifEmpty { if (kind.video) "mp4" else "png" }
        val target = AssetLayout.pathFor(collectionDir, style, kind, romBaseName, ext)

        if (manifest.isCurrent(target, variant, source.length()))
            return Outcome.UpToDate(target)

        // Somebody else's file. Includes one a previous scraping tool left, and
        // one the user placed by hand — the Bridge cannot tell them apart and
        // must not try.
        if (target.isFile && target.length() > 0 && !manifest.owns(target) && !replaceForeign)
            return Outcome.Occupied(target)

        return try {
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
                collection = collectionDir.name,
                romBaseName = romBaseName,
                kind = kind.name,
                variant = variant,
                bytes = target.length(),
                writtenAt = System.currentTimeMillis() / 1000L
            ))
            Outcome.Written(target, target.length())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            BridgeLog.e(TAG, "could not export ${source.name} to ${target.path}", t)
            Outcome.Failed(target, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Removes every file this export put in the library, and forgets them.
     *
     * By manifest and never by pattern: a glob over `box2dfront` would
     * take the user's own pictures with it, and the whole point of recording what
     * was written is that taking it back can be exact.
     *
     * A file that has been *changed* since the Bridge wrote it is left alone —
     * somebody replaced it deliberately, and that is a decision, not a leftover.
     */
    fun revert(collection: String? = null): Reverted {
        var removed = 0; var changed = 0; var absent = 0
        for (r in manifest.all()) {
            if (collection != null && r.collection != collection) continue
            val f = File(r.target)
            when {
                !f.isFile -> { absent++; manifest.remove(r.target) }
                f.length() != r.bytes -> changed++
                f.delete() -> { removed++; manifest.remove(r.target) }
                else -> BridgeLog.w(TAG, "could not remove ${f.path}")
            }
        }
        manifest.save(writeAtomic)
        return Reverted(removed, changed, absent)
    }

    /**
     * [changed] were left in place: their bytes no longer match what was written,
     * so somebody replaced them on purpose.
     */
    data class Reverted(val removed: Int, val changed: Int, val absent: Int)

    fun save() = manifest.save(writeAtomic)

    private companion object { const val TAG = "MediaExporter" }
}
