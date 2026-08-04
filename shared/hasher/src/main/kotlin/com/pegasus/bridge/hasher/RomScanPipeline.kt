package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scans ROM directories, hashes what it finds and resolves each hash to a
 * RetroAchievements game.
 *
 * This is the pipeline that used to live inside `HasherService`, with the
 * Android shell removed: no Service, no wake lock, no notifications, no thermal
 * API. Throttling is now a caller-supplied hook, so Android can keep its thermal
 * back-off and the desktop daemon can simply not throttle.
 *
 * Shape: feeder → N hash producers → API workers → collector. Files unchanged
 * since the last scan, and platforms RetroAchievements does not cover, bypass
 * both hashing and the network.
 */
class RomScanPipeline(
    private val paths: BridgePaths,
    private val hasher: RomHasher,
    private val lookup: RaHashLookup,
    /** Milliseconds to pause after a hash. Android passes its thermal back-off. */
    private val throttleMs: () -> Long = { 0L },
    private val hashWorkers: Int = DEFAULT_HASH_WORKERS,
    private val apiWorkers: Int = DEFAULT_API_WORKERS
) {

    data class Progress(
        val processed: Int,
        val total: Int,
        val currentFile: String,
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int
    ) {
        val fraction: Double get() = if (total <= 0) 0.0 else processed.toDouble() / total
    }

    data class Summary(
        val total: Int,
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int,
        val indexed: Int
    )

    suspend fun scan(
        roots: List<String>,
        onProgress: (Progress) -> Unit = {}
    ): Summary = coroutineScope {
        paths.ensureAll()

        val files = RomScanner.scan(roots)
        val total = files.size
        BridgeLog.i(TAG, "found $total ROM files under ${roots.size} root(s)")
        if (total == 0) return@coroutineScope Summary(0, 0, 0, 0, writeDiscoveryIndex())

        val metaCache = preloadMetadataCache()
        BridgeLog.i(TAG, "loaded ${metaCache.size} cached entries for incremental scan")

        val fileQueue    = Channel<File>(capacity = 64)
        val hashQueue    = Channel<HashJob>(capacity = 32)
        val resultQueue  = Channel<ResultJob>(capacity = 128)
        val hashDedup    = mutableMapOf<String, GameMetadata?>()

        val feeder = launch(Dispatchers.IO) {
            for (f in files) fileQueue.send(f)
            fileQueue.close()
        }

        val producers = List(hashWorkers.coerceAtMost(Runtime.getRuntime().availableProcessors())) {
            launch(Dispatchers.Default) {
                for (file in fileQueue) {
                    if (!isActive) break
                    processFile(file, metaCache, hashQueue, resultQueue)
                }
            }
        }

        val workers = List(apiWorkers) {
            launch(Dispatchers.IO) {
                for (job in hashQueue) {
                    // One network call per distinct hash, however many files share it.
                    val meta = synchronized(hashDedup) { hashDedup[job.hash.hash] }
                        ?: lookup.lookup(job.hash.hash).also {
                            synchronized(hashDedup) { hashDedup[job.hash.hash] = it }
                        }
                    resultQueue.send(ResultJob(job, meta))
                }
            }
        }

        launch {
            feeder.join()
            producers.forEach { it.join() }
            hashQueue.close()
            workers.forEach { it.join() }
            resultQueue.close()
        }

        var processed = 0; var newEntries = 0; var cached = 0; var skipped = 0
        val step = (total / 50).coerceAtLeast(1)
        for (r in resultQueue) {
            when {
                r.skipped -> skipped++
                r.cached  -> cached++
                // A usable match needs a title, not just an id. RA's dorequest can
                // answer Success with an id the Web API does not know — a Virtual
                // Console dump of Metroid returns 1100001487, for which
                // API_GetGameExtended returns []. Writing that produced a junk
                // metadata file the index then discarded, and inflated the count.
                // The same guard also covers a transient metadata failure, which
                // simply gets retried on the next scan.
                r.meta != null && r.meta.gameId > 0 && r.meta.title.isNotEmpty() -> {
                    writeMetadata(r.job, r.meta); newEntries++
                }
            }
            processed++
            if (processed % step == 0 || processed == total) {
                onProgress(Progress(processed, total, r.job.file.name, newEntries, cached, skipped))
            }
        }

        val indexed = writeDiscoveryIndex()
        BridgeLog.i(TAG, "scan complete: $processed processed, $newEntries new, " +
                         "$cached cached, $skipped skipped, $indexed indexed")
        Summary(total, newEntries, cached, skipped, indexed)
    }

    private suspend fun processFile(
        file: File,
        metaCache: Map<String, CachedMeta>,
        hashQueue: Channel<HashJob>,
        resultQueue: Channel<ResultJob>
    ) {
        val rawPlatform = file.parentFile?.name ?: "unknown"
        val platform    = FuzzyMatch.normalizePlatform(rawPlatform)

        if (platform in UNSUPPORTED_PLATFORMS) {
            resultQueue.send(ResultJob(HashJob(file, "", HashResult("", 0), rawPlatform, 0, 0),
                                       null, skipped = true))
            return
        }

        val cacheKey = FuzzyMatch.makeCacheKey(file.nameWithoutExtension, rawPlatform)
        val size     = file.length()
        val modified = file.lastModified()

        // Unchanged since the last scan: the metadata is already on disk and the
        // index rebuild will pick it up, so skip both hashing and the network.
        val known = metaCache[cacheKey]
        if (known != null && known.hash.isNotEmpty() &&
            known.fileSize == size && known.lastModified == modified) {
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult(known.hash, 0), rawPlatform, size, modified),
                null, cached = true))
            return
        }

        val result = try { withContext(Dispatchers.IO) { hasher.hash(file.absolutePath) } }
                     catch (e: Exception) { BridgeLog.w(TAG, "hash failed: ${file.name}", e); null }

        if (result == null) {
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult("", 0), rawPlatform, size, modified), null))
            return
        }

        throttleMs().takeIf { it > 0 }?.let { delay(it) }
        hashQueue.send(HashJob(file, cacheKey, result, rawPlatform, size, modified))
    }

    private fun writeMetadata(job: HashJob, meta: GameMetadata) {
        val now = BridgePaths.epochSeconds()
        val json = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("gameId",   meta.gameId)
            .put("title",    meta.title)
            .put("platform", FuzzyMatch.normalizePlatform(job.platform))
            .put("cacheKey", job.cacheKey)
            .put("ra", JSONObject()
                .put("points", 0).put("progress", 0.0).put("unlocked", 0)
                .put("total", meta.numAchievements)
                .put("imageIcon", meta.imageIcon)
                .put("fetchedAt", now))
            .put("rom", JSONObject()
                .put("hash", job.hash.hash)
                .put("fileSize", job.fileSize)
                .put("lastModified", job.lastModified))
            .put("fetchedAt", now)
        BridgePaths.writeAtomic(paths.metadata(meta.gameId.toString()), json.toString(2))
    }

    /**
     * Rebuilds `metadata/_index.json` from every per-game file: `games[]` for the
     * discovered-games list, `byKey{}` for reverse lookup from a ROM cache key.
     */
    private fun writeDiscoveryIndex(): Int {
        val files = paths.metadata.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return 0

        val games = JSONArray()
        val byKey = JSONObject()
        for (f in files) {
            try {
                val j        = JSONObject(f.readText())
                val ra       = j.optJSONObject("ra") ?: continue
                val gameId   = j.optInt("gameId")
                val title    = j.optString("title")
                if (gameId <= 0 || title.isEmpty()) continue

                val entry = JSONObject()
                    .put("gameId", gameId)
                    .put("title", title)
                    .put("platform", j.optString("platform"))
                    .put("total", ra.optInt("total"))
                    .put("imageIcon", ra.optString("imageIcon"))
                games.put(entry)

                j.optString("cacheKey").takeIf { it.isNotEmpty() }?.let { byKey.put(it, entry) }
            } catch (e: Exception) {
                BridgeLog.w(TAG, "index skipped ${f.name}: ${e.message}")
            }
        }

        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("fetchedAt", BridgePaths.epochSeconds())
            .put("count", games.length())
            .put("games", games)
            .put("byKey", byKey)
        BridgePaths.writeAtomic(paths.discoveryIndex, payload.toString(2))
        return games.length()
    }

    private fun preloadMetadataCache(): Map<String, CachedMeta> {
        val map = HashMap<String, CachedMeta>()
        val files = paths.metadata.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return map
        for (f in files) {
            try {
                val j   = JSONObject(f.readText())
                val rom = j.optJSONObject("rom") ?: continue
                val key = j.optString("cacheKey")
                if (key.isEmpty()) continue
                map[key] = CachedMeta(rom.optString("hash"), rom.optLong("fileSize"),
                                      rom.optLong("lastModified"))
            } catch (_: Exception) {}
        }
        return map
    }

    private data class HashJob(
        val file: File, val cacheKey: String, val hash: HashResult,
        val platform: String, val fileSize: Long, val lastModified: Long
    )
    private data class ResultJob(
        val job: HashJob, val meta: GameMetadata?,
        val cached: Boolean = false, val skipped: Boolean = false
    )
    private data class CachedMeta(val hash: String, val fileSize: Long, val lastModified: Long)

    companion object {
        private const val TAG = "RomScanPipeline"
        const val DEFAULT_HASH_WORKERS = 4
        const val DEFAULT_API_WORKERS  = 8

        /** Platforms RetroAchievements does not cover — skipped before any I/O. */
        val UNSUPPORTED_PLATFORMS = setOf(
            "switch", "psvita", "wiiu", "pc", "windows", "android", "ios", "3ds", "n3ds"
        )
    }
}
