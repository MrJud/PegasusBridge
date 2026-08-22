package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every file the Bridge has put into somebody else's directory.
 *
 * The media directory belongs to the user. They put covers there by hand, a
 * scraping tool put others there before this one existed, and the Bridge is now
 * going to add more. Without a record of which is which there is no way to
 * answer the two questions that decide whether writing there is safe at all:
 *
 * - *May I overwrite this?* Only if the Bridge wrote it. A hand-placed cover is
 *   the more considered of the two and outranks a scraped one every time.
 * - *May I take it back?* Only for files listed here. An "undo" that deleted by
 *   pattern would take the user's files with it.
 *
 * So an export writes nothing it cannot later account for, and the manifest is
 * the account. It lives in the Bridge's own data root, never in the library.
 */
class ExportManifest(private val file: File) {

    /**
     * [variant] is the digest from `ArtifactKey`, which identifies the *picture*
     * rather than the game — two regional covers of one title have different
     * ones. It is what makes "this file is already the right bytes" answerable
     * without re-reading the file.
     */
    data class Record(
        val target: String,
        val source: String,
        val collection: String,
        val romBaseName: String,
        val kind: String,
        val variant: String,
        val bytes: Long,
        val writtenAt: Long
    )

    private val records = LinkedHashMap<String, Record>()

    init { load() }

    val size: Int get() = synchronized(records) { records.size }

    private fun load() {
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText())
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) {
                // Deliberately *not* discarded like a cache would be. These entries
                // are the only claim of ownership over files in the user's library,
                // and forgetting them would make every one of those files
                // untouchable — which is the safe failure, but a permanent one.
                BridgeLog.w(TAG, "manifest schema ${root.optInt("schemaVersion")} is not " +
                                 "$SCHEMA_VERSION; entries are kept but treated as read-only")
            }
            val arr = root.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val target = o.optString("target")
                if (target.isEmpty()) continue
                records[target] = Record(
                    target = target,
                    source = o.optString("source"),
                    collection = o.optString("collection"),
                    romBaseName = o.optString("romBaseName"),
                    kind = o.optString("kind"),
                    variant = o.optString("variant"),
                    bytes = o.optLong("bytes"),
                    writtenAt = o.optLong("writtenAt")
                )
            }
        } catch (t: Throwable) {
            BridgeLog.w(TAG, "manifest unreadable: ${t.message}")
        }
    }

    /** True when the Bridge wrote the file at this path, and may replace it. */
    fun owns(target: File): Boolean =
        synchronized(records) { records.containsKey(target.absolutePath) }

    fun recordOf(target: File): Record? =
        synchronized(records) { records[target.absolutePath] }

    /**
     * Whether the file at [target] is already exactly what would be written.
     *
     * Compares the variant digest and the byte count rather than re-hashing: the
     * digest already identifies the picture, and the size catches a copy that was
     * interrupted.
     */
    fun isCurrent(target: File, variant: String, bytes: Long): Boolean {
        val r = recordOf(target) ?: return false
        return r.variant == variant && r.bytes == bytes &&
               target.isFile && target.length() == bytes
    }

    fun put(record: Record) {
        synchronized(records) { records[record.target] = record }
    }

    fun remove(target: String) {
        synchronized(records) { records.remove(target) }
    }

    fun all(): List<Record> = synchronized(records) { records.values.toList() }

    /** Files this manifest claims that are no longer on disk — somebody removed them. */
    fun missing(): List<Record> = all().filter { !File(it.target).isFile }

    fun save(writeAtomic: (File, String) -> Unit) {
        val arr = JSONArray()
        for (r in all()) {
            arr.put(JSONObject()
                .put("target", r.target)
                .put("source", r.source)
                .put("collection", r.collection)
                .put("romBaseName", r.romBaseName)
                .put("kind", r.kind)
                .put("variant", r.variant)
                .put("bytes", r.bytes)
                .put("writtenAt", r.writtenAt))
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("updatedAt", System.currentTimeMillis() / 1000L)
            .put("count", arr.length())
            .put("files", arr)
        runCatching { writeAtomic(file, payload.toString(2)) }
            .onFailure { BridgeLog.e(TAG, "could not write the export manifest", it) }
    }

    companion object {
        private const val TAG = "ExportManifest"
        const val FILE_NAME = "export-manifest.json"
        const val SCHEMA_VERSION = 1
    }
}
