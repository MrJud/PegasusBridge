package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.ArtifactKey
import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.hasher.PlainRomHasher
import com.pegasus.bridge.hasher.RomIdentity
import com.pegasus.bridge.hasher.withHashes
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import org.json.JSONArray
import org.json.JSONObject

// Port delle 16 funzioni di CoverScraperService.js:
//   SGDB: search / grids / logos / heroes / screenshots
//   IGN:  search / details / images
//   Steam: search / assets
//   IGDB: token / search / details / covers / screenshots / artworks
//
// Il tema invia pegasus-data://scrape-source?source=X&op=Y&jobId=Z&…
// Noi restituiamo un JSONObject con { status, results, … } che MediaService
// scriverà in /sdcard/PegasusData/scrape/{jobId}.json.
class ScrapeSourceDispatcher(
    private val config: Config,
    /**
     * Where fetched pictures and the system table are kept. Optional only because the
     * Android shell builds this class without one; the `ss` ops say so plainly rather
     * than writing to a guessed location.
     */
    private val paths: BridgePaths? = null
) {

    /**
     * Esegue l'operazione richiesta e ritorna il JSON payload completo.
     * Non scrive su file: è MediaService a farlo (per mantenere unica la logica di persistenza).
     *
     * @throws IllegalArgumentException se source/op non sono supportati o mancano credenziali/param
     * @throws Exception                su errori di rete o parsing
     */
    fun run(source: String, op: String, params: Map<String, String>): Result {
        return when (source) {
            "sgdb"  -> dispatchSgdb(op, params)
            "ign"   -> dispatchIgn(op, params)
            "steam" -> dispatchSteam(op, params)
            "igdb"  -> dispatchIgdb(op, params)
            "ss"    -> dispatchScreenScraper(op, params)
            "romm"  -> dispatchRomm(op, params)
            "steam-account" -> dispatchSteamAccount(op, params)
            else    -> throw IllegalArgumentException("unknown source: $source")
        }
    }

    data class Result(val results: Any) {
        fun isEmpty(): Boolean = when (results) {
            is JSONArray  -> results.length() == 0
            is JSONObject -> results.length() == 0
            else          -> false
        }
    }

    // ── SGDB ────────────────────────────────────────────────────────────────

    private fun sgdbKey(): String =
        config.load().steamGridDb?.apiKey
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("missing steamGridDb.apiKey in credentials.json")

    private fun dispatchSgdb(op: String, params: Map<String, String>): Result {
        val key = sgdbKey()
        return when (op) {
            "search"      -> {
                val term = params["term"] ?: throw IllegalArgumentException("missing term")
                Result(sgdbGamesToJson(SteamGridDbClient.search(term, key).getOrThrow()))
            }
            "grids"       -> Result(sgdbItemsToJson(SteamGridDbClient.getGrids(paramInt(params, "gameId"), key).getOrThrow()))
            "logos"       -> Result(sgdbItemsToJson(SteamGridDbClient.getLogos(paramInt(params, "gameId"), key).getOrThrow()))
            "heroes"      -> Result(sgdbItemsToJson(SteamGridDbClient.getHeroes(paramInt(params, "gameId"), key).getOrThrow()))
            "screenshots" -> Result(sgdbItemsToJson(SteamGridDbClient.getScreenshots(paramInt(params, "gameId"), key).getOrThrow()))
            else          -> throw IllegalArgumentException("sgdb: unknown op '$op'")
        }
    }

    private fun sgdbGamesToJson(list: List<SteamGridDbClient.SgdbGame>): JSONArray {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject()
                .put("id",       g.id)
                .put("name",     g.name)
                .put("types",    JSONArray(g.types))
                .put("verified", g.verified))
        }
        return arr
    }

    private fun sgdbItemsToJson(list: List<SteamGridDbClient.SgdbItem>): JSONArray {
        val arr = JSONArray()
        for (it in list) {
            arr.put(JSONObject()
                .put("id",     it.id)
                .put("url",    it.url)
                .put("thumb",  it.thumb)
                .put("width",  it.width)
                .put("height", it.height)
                .put("style",  it.style)
                .put("author", it.author))
        }
        return arr
    }

    // ── IGN ─────────────────────────────────────────────────────────────────

    private fun dispatchIgn(op: String, params: Map<String, String>): Result = when (op) {
        "search" -> {
            val term = params["term"] ?: throw IllegalArgumentException("missing term")
            Result(ignGamesToJson(IgnClient.search(term).getOrThrow()))
        }
        "details" -> {
            val slug = params["slug"] ?: throw IllegalArgumentException("missing slug")
            Result(ignDetailsToJson(IgnClient.getDetails(slug).getOrThrow()))
        }
        "images" -> {
            val slug = params["slug"] ?: throw IllegalArgumentException("missing slug")
            Result(JSONArray(IgnClient.getImages(slug).getOrThrow()))
        }
        else -> throw IllegalArgumentException("ign: unknown op '$op'")
    }

    private fun ignGamesToJson(list: List<IgnClient.IgnGame>): JSONArray {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject()
                .put("title",    g.title)
                .put("slug",     g.slug)
                .put("id",       g.id)
                .put("coverUrl", g.coverUrl)
                .put("platforms", JSONArray(g.platforms)))
        }
        return arr
    }

    private fun ignDetailsToJson(d: IgnClient.IgnDetails): JSONObject = JSONObject()
        .put("title",       d.title)
        .put("coverUrl",    d.coverUrl)
        .put("description", d.description)
        .put("genres",      JSONArray(d.genres))
        .put("score",       d.score ?: JSONObject.NULL)

    // ── Steam ───────────────────────────────────────────────────────────────

    private fun dispatchSteam(op: String, params: Map<String, String>): Result = when (op) {
        "search" -> {
            val term = params["term"] ?: throw IllegalArgumentException("missing term")
            Result(steamGamesToJson(SteamStoreClient.search(term).getOrThrow()))
        }
        "assets" -> {
            val appId = paramInt(params, "appId", "appid")
            Result(steamAssetsToJson(SteamStoreClient.getAssets(appId).getOrThrow()))
        }
        else -> throw IllegalArgumentException("steam: unknown op '$op'")
    }

    private fun steamGamesToJson(list: List<SteamStoreClient.SteamGame>): JSONArray {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject()
                .put("appid",      g.appId)
                .put("name",       g.name)
                .put("tiny_image", g.tinyImage))
        }
        return arr
    }

    private fun steamAssetsToJson(a: SteamStoreClient.SteamAssets): JSONObject {
        val screenshots = JSONArray()
        for (s in a.screenshots) {
            screenshots.put(JSONObject()
                .put("id",    s.id)
                .put("thumb", s.thumb)
                .put("full",  s.full))
        }
        val movies = JSONArray()
        for (m in a.movies) {
            movies.put(JSONObject()
                .put("id",        m.id)
                .put("name",      m.name)
                .put("thumbnail", m.thumbnail)
                .put("mp4",       m.mp4)
                .put("hls",       m.hls)
                .put("dash",      m.dash)
                .put("mp4_480",   m.mp4_480)
                .put("mp4_max",   m.mp4_max))
        }
        return JSONObject()
            .put("appid",          a.appId)
            .put("name",           a.name)
            .put("header_image",   a.headerImage)
            .put("background_raw", a.backgroundRaw)
            .put("screenshots",    screenshots)
            .put("movies",         movies)
    }

    // ── IGDB ────────────────────────────────────────────────────────────────

    private fun igdbCreds(): Pair<String, String> {
        val creds = config.load().igdb
            ?: throw IllegalStateException("missing igdb block in credentials.json")
        if (creds.clientId.isEmpty() || creds.clientSecret.isEmpty())
            throw IllegalStateException("missing igdb.clientId/clientSecret")
        return creds.clientId to creds.clientSecret
    }

    private fun dispatchIgdb(op: String, params: Map<String, String>): Result {
        val (clientId, clientSecret) = igdbCreds()
        // Ogni op IGDB richiede un token valido, "token" compresa: ensureToken()
        // riusa quello in cache finche' e' valido e lo rinnova solo se serve.
        val token = IgdbClient.ensureToken(config, clientId, clientSecret).getOrThrow()

        return when (op) {
            "token" -> Result(JSONObject().put("token", token))
            "search" -> {
                val term = params["term"] ?: throw IllegalArgumentException("missing term")
                Result(igdbGamesToJson(IgdbClient.search(term, clientId, token).getOrThrow()))
            }
            "details"     -> Result(igdbDetailsToJson(IgdbClient.getDetails(paramInt(params, "gameId"), clientId, token).getOrThrow()))
            "covers"      -> Result(igdbImagesToJson(IgdbClient.getCovers(paramInt(params, "gameId"), clientId, token).getOrThrow()))
            "screenshots" -> Result(igdbImagesToJson(IgdbClient.getScreenshots(paramInt(params, "gameId"), clientId, token).getOrThrow()))
            "artworks"    -> Result(igdbImagesToJson(IgdbClient.getArtworks(paramInt(params, "gameId"), clientId, token).getOrThrow()))
            else -> throw IllegalArgumentException("igdb: unknown op '$op'")
        }
    }

    private fun igdbGamesToJson(list: List<IgdbClient.IgdbGame>): JSONArray {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject()
                .put("id",       g.id)
                .put("name",     g.name)
                .put("coverUrl", g.coverUrl)
                .put("summary",  g.summary)
                .put("genres",   JSONArray(g.genres)))
        }
        return arr
    }

    // Contratto field-compatibile col JS: style="" e author="IGDB".
    private fun igdbImagesToJson(list: List<IgdbClient.IgdbImage>): JSONArray {
        val arr = JSONArray()
        for (it in list) {
            arr.put(JSONObject()
                .put("id",     it.id)
                .put("url",    it.url)
                .put("thumb",  it.thumb)
                .put("width",  it.width)
                .put("height", it.height)
                .put("style",  "")
                .put("author", "IGDB"))
        }
        return arr
    }

    private fun igdbDetailsToJson(d: IgdbClient.IgdbDetails): JSONObject = JSONObject()
        .put("title",       d.title)
        .put("description", d.description)
        .put("genres",      JSONArray(d.genres))
        .put("score",       d.score ?: JSONObject.NULL)
        .put("developer",   d.developer)
        .put("publisher",   d.publisher)
        .put("releaseYear", d.releaseYear ?: JSONObject.NULL)
        .put("gameModes",   JSONArray(d.gameModes))
        .put("coverUrl",    "")

    // ── ScreenScraper ───────────────────────────────────────────────────────
    //
    // The odd one out, and deliberately so: every other source here is asked "which
    // game is called this?" and hands back a list to be matched by title. This one is
    // asked "what is this file?" and hands back one game or none. There is nothing to
    // match, which is the entire reason it belongs in a retro library — a title match
    // refuses `DuckTales` because IGN calls it `Disney's DuckTales`, and a digest does
    // not have opinions.
    //
    // Three ops:
    //   game    identify a ROM; returns the metadata and *which* kinds of art exist
    //   media   fetch one of those kinds to a local file; returns the path
    //   systems the API's own system table, which is what keeps the id map honest
    //
    // `media` does **not** ask the API again. One `jeuInfos` answer already lists every
    // picture for the game, so a second call for the wheel after the cover would double
    // the quota spent per game for no new information — hence [ssCache]. Rebuilding the
    // request would also mean re-reading and re-hashing the ROM, which for a 40 MB
    // archive is not free either.

    /**
     * The last few identified ROMs.
     *
     * Small and in memory on purpose: it exists to stop one game's four contents
     * costing four requests, not to remember a library. Persisting it would mean
     * promising the database never gains a game, and a wrong "not found" that survives
     * a restart is the shape of bug this project has already paid for once.
     *
     * ── What the key has to carry, and why each part is there ──
     *
     * It used to be `platform|path`, which was wrong in three separate ways and every
     * one of them was reachable from the theme:
     *
     * - **Language.** The synopsis and the genre names are per-language, so asking for
     *   the same ROM in Italian after English returned the English answer. The cache
     *   was keyed on the question's subject and not on the question.
     * - **The file.** A ROM replaced at the same path — a better dump, a patch — kept
     *   the old identity for as long as the entry lived.
     * - **`systemeid`.** The override parameter exists precisely so a caller can ask
     *   the same file under a different system; keyed without it, the second answer was
     *   the first one.
     *
     * So the key is the [RomIdentity] signature — path, size, mtime — plus language and
     * the system id actually sent.
     */
    private val ssCache = object : LinkedHashMap<String, Identified>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Identified>?
        ): Boolean = size > SS_CACHE_ENTRIES
    }

    /**
     * One answer: the game, and the ROM the question was about.
     *
     * They travel together because both halves are needed downstream and the second
     * is expensive. `op=media` names its file from the ROM's digests, and recomputing
     * them would mean hashing a 40 MB archive again to name a picture that has already
     * been fetched.
     */
    private data class Identified(
        val game: ScreenScraperClient.Game,
        val identity: RomIdentity
    )

    /**
     * The identification currently in flight for a key, so a second caller waits
     * for it instead of repeating it.
     *
     * The daemon serves requests on a worker pool, and the theme's game screen asks
     * for a cover, a wheel, a wallpaper and a video at once. Without this, four
     * threads miss the cache together and issue four `jeuInfos` calls for one game —
     * against an account the API reports as `maxThreads: 1`. [ScreenScraperClient]'s
     * gate serialises them, so they do not fail; they just cost four times the quota
     * and take four times as long.
     *
     * A plain `LinkedHashMap` was also being read and written from those threads with
     * no synchronisation at all, which is a data race on a structure that rebalances
     * on *read* (access-order LRU).
     */
    private val ssInFlight = HashMap<String, CompletableFuture<Identified>>()

    /** Guards [ssCache] and [ssInFlight]. Never held across hashing or HTTP. */
    private val ssLock = Any()

    /**
     * Held by the one caller that is fetching the system table, while the others that
     * have to fetch it wait.
     *
     * The table is fetched by whoever finds it missing, stale or without the platform
     * it was asked about, and the four requests of a game screen find that together.
     * Left to themselves they fetch it four times, write the one file four times and
     * read it back four times. That is three requests wasted, and it can cost one
     * more. [BridgePaths.writeAtomic] keeps a half-written file from a reader only
     * while one caller writes: two share its temporary file, and the one whose rename
     * finds that file already gone writes the target in place. A caller that reads the
     * table back at that moment can find it empty. It then takes the platform for
     * unknown and sends no `systemeid`, so its question goes out under another
     * [SsRequest.cacheKey] than everybody else's about the same ROM, and the ROM is
     * asked about twice.
     *
     * Whoever fetches the table takes this lock, `op=systems` included, since each of
     * them writes the one file. Nobody takes it to read a table that is good.
     *
     * Unlike [ssLock] this one is held across the request, and that is the point of
     * it: a caller that waits here has nothing to do until the table is there, and
     * what it did instead of waiting was a `systemesListe` of its own.
     */
    private val ssSystemsLock = Any()

    private fun dispatchScreenScraper(op: String, params: Map<String, String>): Result = when (op) {
        "game"    -> Result(ssGameToJson(ssIdentify(params)))
        "media"   -> Result(ssFetchMedia(params))
        "systems" -> Result(ssSystemsOp(refresh = params["refresh"] == "1"))
        else      -> throw IllegalArgumentException("ss: unknown op '$op'")
    }

    /**
     * `op=systems`. A table on disk that is good is the answer, read with no lock, so
     * that asking for it does not wait for a fetch another caller is making. A refresh,
     * or a table that is not good, is a fetch and a write, and takes [ssSystemsLock].
     * Unless it was asked to refresh, [ssSystems] looks at the disk again under the
     * lock, so a caller that waited there for somebody else's fetch answers with the
     * table that one wrote and asks nothing.
     */
    private fun ssSystemsOp(refresh: Boolean): JSONArray {
        if (!refresh) cachedSystems()?.let { return systemsToJson(it) }
        return synchronized(ssSystemsLock) { ssSystems(force = refresh) }
    }

    /**
     * The system table, refreshed from the API and kept on disk between runs.
     *
     * Called with [ssSystemsLock] held, because it writes the table.
     */
    private fun ssSystems(force: Boolean = false): JSONArray {
        if (!force) {
            cachedSystems()?.let { return systemsToJson(it) }
        }
        val list = ScreenScraperClient.systems(config).getOrThrow()
        paths?.let { p ->
            try {
                BridgePaths.writeAtomic(p.cache(SS_SYSTEMS_FILE),
                                        ScreenScraperSystemMap.toJson(list))
            } catch (t: Throwable) {
                // A cache that cannot be written costs a request next time, nothing more.
                BridgeLog.w(TAG, "could not cache the ScreenScraper systems: ${t.message}")
            }
        }
        return systemsToJson(list)
    }

    private fun systemsToJson(list: List<ScreenScraperClient.SsSystem>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject()
                .put("id", s.id)
                .put("names", JSONArray(s.names))
                .put("extensions", JSONArray(s.extensions)))
        }
        return arr
    }

    /**
     * The table on disk, if it is still worth trusting.
     *
     * Null means "ask the API": the file is missing, unreadable, written by an older
     * schema, or older than [SS_SYSTEMS_TTL_SECONDS]. It was previously kept forever,
     * so a system ScreenScraper added after the first fetch could never be found and
     * the failure looked exactly like a ROM the database does not have.
     *
     * Thirty days is chosen against what the table actually is: a list of consoles.
     * It gains an entry a few times a year, and re-fetching it more often spends quota
     * on an answer that has not changed.
     */
    private fun cachedSystems(): List<ScreenScraperClient.SsSystem>? {
        val p = paths ?: return null
        val f = p.cache(SS_SYSTEMS_FILE)
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (ScreenScraperSystemMap.schemaVersionOf(text) != ScreenScraperSystemMap.SCHEMA_VERSION) {
            BridgeLog.i(TAG, "the cached ScreenScraper system table is from an older schema")
            return null
        }
        val age = BridgePaths.epochSeconds() - ScreenScraperSystemMap.fetchedAtOf(text)
        if (age > SS_SYSTEMS_TTL_SECONDS) {
            BridgeLog.i(TAG, "the cached ScreenScraper system table is ${age / 86_400} days old")
            return null
        }
        return ScreenScraperSystemMap.fromJson(text).takeIf { it.isNotEmpty() }
    }

    /**
     * The cached table, fetched when it is missing, stale or does not know [platform].
     *
     * Empty is a usable answer: without it a hash lookup still works, and only a
     * `romnom` one — arcade — actually needs an id. A refresh that *fails* keeps
     * whatever was on disk rather than falling back to nothing, because a stale id is
     * still overwhelmingly likely to be right and no id at all is certainly not.
     *
     * A table that is there and knows the platform is read with no lock. One that has
     * to be fetched is fetched under [ssSystemsLock], by a second pass through this
     * function with [fetching] set: it looks at the disk again first, and a caller
     * that waited for the lock finds there the table it was about to ask for.
     */
    private fun ssSystemIndex(platform: String = "", fetching: Boolean = false): Map<String, Int> {
        val p = paths ?: return emptyMap()
        val f = p.cache(SS_SYSTEMS_FILE)

        val fresh = cachedSystems()
        if (fresh != null) {
            val index = ScreenScraperSystemMap.index(fresh)
            // A platform the table does not cover is the other refresh trigger: it is
            // indistinguishable from a stale table, and asking once is cheap.
            if (platform.isEmpty() || ScreenScraperSystemMap.systemeId(platform, index) > 0)
                return index
            if (fetching)
                BridgeLog.i(TAG, "no ScreenScraper system for '$platform'; refreshing the table")
        }

        if (!fetching)
            return synchronized(ssSystemsLock) { ssSystemIndex(platform, fetching = true) }

        return try {
            ssSystems(force = true)
            val refreshed = if (f.isFile) ScreenScraperSystemMap.fromJson(f.readText()) else emptyList()
            ScreenScraperSystemMap.index(refreshed)
        } catch (t: Throwable) {
            BridgeLog.w(TAG, "could not refresh the ScreenScraper system table: ${t.message}")
            // Keep the stale one. Losing the whole table because a refresh timed out
            // would take arcade lookups down with it, and those cannot work without an id.
            val stale = if (f.isFile) ScreenScraperSystemMap.fromJson(f.readText()) else emptyList()
            if (stale.isEmpty()) emptyMap() else ScreenScraperSystemMap.index(stale)
        }
    }

    /** What a caller asked, resolved: the ROM, the language and the system id sent. */
    private data class SsRequest(
        val identity: RomIdentity,
        val lang: String,
        val systemeId: Int
    ) {
        val cacheKey: String get() = "${identity.signature}|$lang|$systemeId"
    }

    private fun ssRequestOf(params: Map<String, String>): SsRequest {
        val path = params["file"].orEmpty()
        if (path.isEmpty()) throw IllegalArgumentException("missing file")
        val platform = params["platform"].orEmpty()
        val lang = params["lang"]?.takeIf { it.isNotEmpty() } ?: "en"
        val byName = ScreenScraperSystemMap.matchedByName(platform)
        // An explicit id overrides the table. It exists because the table's arcade entry
        // could only ever be settled by measurement — the API publishes sixty-odd boards
        // that all answer to "arcade" — and a probe needs to be able to ask "which of
        // these actually answers for pacman.zip?" without editing and rebuilding the
        // daemon between guesses.
        val systemeId = params["systemeid"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: ScreenScraperSystemMap.systemeId(platform, ssSystemIndex(platform))
        return SsRequest(RomIdentity.of(path, platform, byName), lang, systemeId)
    }

    /**
     * Identifies a ROM, at most once per distinct question however many callers ask.
     *
     * The lock covers the cache and the in-flight map and nothing else: the hashing
     * and the HTTP call happen outside it, so a 40 MB archive being digested does not
     * block a second thread's cache hit. Followers await the owner's future.
     */
    private fun ssIdentify(params: Map<String, String>): Identified {
        val req = ssRequestOf(params)
        val key = req.cacheKey

        var mine: CompletableFuture<Identified>? = null
        val waitOn = synchronized(ssLock) {
            ssCache[key]?.let { return it }
            ssInFlight[key] ?: CompletableFuture<Identified>().also { mine = it; ssInFlight[key] = it }
        }

        val owned = mine
        if (owned == null) {
            // Someone else is already asking. Their failure is ours too — which is
            // right: two threads asking the same question of a source that has just
            // refused should not both spend an attempt discovering it.
            return try {
                waitOn.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        return try {
            val answer = ssFetchIdentity(req)
            synchronized(ssLock) { ssCache[key] = answer; ssInFlight.remove(key) }
            owned.complete(answer)
            answer
        } catch (t: Throwable) {
            // Nothing is cached: a refusal must be asked again next time. Only the
            // in-flight claim is released.
            synchronized(ssLock) { ssInFlight.remove(key) }
            owned.completeExceptionally(t)
            throw t
        }
    }

    /** The actual call. Outside the lock, on purpose — it hashes and it does HTTP. */
    private fun ssFetchIdentity(req: SsRequest): Identified {
        val id = req.identity
        if (id.matchedByName) {
            // Arcade: the romset name is the identity and the hashes would describe the
            // wrong thing entirely — a MAME zip holds a pile of separately-dumped chips,
            // so neither the archive's digest nor its largest entry's means anything to
            // the database. Sending them alongside the name is not a belt-and-braces
            // fallback, it is a worse request.
            val game = ScreenScraperClient.jeuInfos(
                config, romName = id.romName, systemeId = req.systemeId, lang = req.lang).getOrThrow()
            return Identified(game, id)
        }
        val tempDir = paths?.cache ?: File(System.getProperty("java.io.tmpdir"), "pegasus-bridge")
        val h = PlainRomHasher.hash(id.canonicalPath, tempDir)
            ?: throw IllegalStateException("could not read ${id.romName}")
        val game = ScreenScraperClient.jeuInfos(
            config, md5 = h.md5, crc = h.crc32, size = h.size,
            romName = h.name, systemeId = req.systemeId, lang = req.lang).getOrThrow()
        // The digests come back with the answer so an artwork key can use them.
        return Identified(game, id.withHashes(h))
    }

    /**
     * The identified game, plus which kinds of art it actually has.
     *
     * `kinds` is the field that makes one request serve the whole screen: without it the
     * theme would have to ask for a wheel to discover there is no wheel, and every
     * "does this exist" question here costs quota.
     *
     * The shapes are the ones the theme's `ScraperMatch.metaFieldsFromRemote` already
     * reads — `developer`, `publisher`, `genres`, `releaseYear`, `gameModes`, `score` —
     * so this source needs no mapping of its own on the far side.
     */
    private fun ssGameToJson(found: Identified): JSONObject {
        val g = found.game
        val kinds = JSONObject()
        for (kind in MEDIA_KINDS)
            kinds.put(kind, ScreenScraperClient.pickMedia(g.media, kind, found.identity.romName) != null)

        val media = JSONArray()
        // Types and regions only. The URLs are deliberately not here: a ScreenScraper
        // media URL carries devid, devpassword and sspassword in its query string, and
        // handing one to the theme would write the credentials into an override map on
        // disk. `op=media` returns a file path instead.
        for (m in g.media)
            media.put(JSONObject().put("type", m.type).put("region", m.region).put("format", m.format))

        return JSONObject()
            .put("id", g.id)
            .put("title", g.title)
            .put("developer", g.developer)
            .put("publisher", g.publisher)
            .put("genres", JSONArray(g.genres))
            .put("releaseYear", g.releaseYear)
            // The theme joins `gameModes` into its players field. ScreenScraper reports
            // a range ("1-2"), which is one value, not a list of modes.
            .put("gameModes", JSONArray(listOf(g.players).filter { it.isNotEmpty() }))
            .put("description", g.description)
            .put("score", ScreenScraperClient.scoreOutOf20(g.rating))
            .put("kinds", kinds)
            .put("media", media)
            .put("coverUrl", "")
    }

    /**
     * Downloads the best media of one kind and answers with where it landed.
     *
     * ── Why the name is a digest ───────────────────────────────
     *
     * It used to be `ss-<gameId>-<kind>.<ext>`, and the comment above it claimed the
     * name was a digest. It was not, and the difference is a wrong picture on screen:
     * `Contra (USA).nes` and `Contra (Japan).nes` resolve to one ScreenScraper game id
     * and select *different* box art, so the second file found the first one's path
     * already occupied, skipped its download and displayed the American cover for the
     * Japanese release — and because the skip is also the cache, it would never
     * correct itself.
     *
     * The key now mixes the selected media's URL, kind, type, region, format and the
     * ROM's own fingerprint, so the two get different files. The URL goes in as a
     * digest, never as text: it carries `devid`, `devpassword` and `sspassword`.
     *
     * Reuse survives, and is now actually sound: the same ROM asking for the same kind
     * a second time produces the same key and the existing bytes are returned. A
     * *changed* selected URL produces a different key, so a picture ScreenScraper has
     * replaced is fetched rather than served stale from a name that no longer describes
     * it.
     */
    private fun ssFetchMedia(params: Map<String, String>): JSONObject {
        val p = paths ?: throw IllegalStateException("no data root: cannot store fetched media")
        val kind = params["kind"] ?: throw IllegalArgumentException("missing kind")
        val found = ssIdentify(params)
        val game = found.game
        val identity = found.identity

        val media = ScreenScraperClient.pickMedia(game.media, kind, identity.romName)
            ?: return JSONObject().put("localPath", "").put("kind", kind)

        val ext = media.format.ifEmpty { if (kind == "video") "mp4" else "png" }
        val id = game.id.ifEmpty { ArtifactKey.sanitize(identity.romName) }
        val variant = ArtifactKey.variant(
            source = "ss", mediaUrl = media.url, kind = kind, type = media.type,
            region = media.region, format = media.format,
            romFingerprint = identity.fingerprint())
        val target = p.artwork(ArtifactKey.fileName("ss", id, kind, variant, ext))

        if (!target.isFile || target.length() == 0L)
            ScreenScraperClient.fetchMedia(config, media, target).getOrThrow()

        return JSONObject()
            .put("localPath", target.absolutePath)
            .put("kind", kind)
            .put("type", media.type)
            .put("region", media.region)
            // The variant digest, so a caller can tell two regional covers of one game
            // apart without being handed the credential-bearing URL that distinguishes
            // them. This is also what the Pegasus media export keys its copies on.
            .put("variant", variant)
            .put("gameId", game.id)
            .put("bytes", target.length())
    }

    // ── RomM ────────────────────────────────────────────────────────────────
    //
    // The odd one out for the opposite reason to ScreenScraper: this source has
    // *already* scraped. One record carries IGDB, MobyGames, ScreenScraper,
    // LaunchBox and RetroAchievements metadata together, and it is keyed by the
    // same rcheevos hash the Bridge computes — so a lookup here is a join rather
    // than a search.
    //
    // Everything leaving these ops is redacted first. RomM hands out its own
    // instance's ScreenScraper devpassword inside `ss_metadata.box2d_url`, which
    // was measured on the public demo rather than guessed at, and writing one
    // into a cache file would put a third party's password on the user's disk.

    private fun rommCreds(): RommClient.Credentials {
        val c = config.load().romm
            ?: throw IllegalStateException("missing romm block in credentials.json")
        if (c.baseUrl.isBlank())
            throw IllegalStateException("missing romm.baseUrl")
        return RommClient.Credentials(c.baseUrl, c.token)
    }

    private fun dispatchRomm(op: String, params: Map<String, String>): Result {
        val c = rommCreds()
        return when (op) {
            // Proves the server and the token in one cheap call, and reports which
            // metadata sources that instance has enabled — which is what decides
            // whether preferring it over ScreenScraper is worth doing at all.
            "heartbeat" -> Result(RommClient.redactedMetadata(
                RommClient.heartbeat(c).getOrThrow()))
            "platforms" -> {
                val arr = JSONArray()
                for (p in RommClient.platforms(c).getOrThrow()) {
                    arr.put(JSONObject()
                        .put("id", p.id).put("slug", p.slug).put("name", p.name)
                        .put("romCount", p.romCount)
                        .put("igdbId", p.igdbId)
                        .put("screenScraperId", p.screenScraperId)
                        .put("retroAchievementsId", p.retroAchievementsId))
                }
                Result(arr)
            }
            "roms" -> {
                val page = RommClient.roms(
                    c,
                    platformId = params["platformId"]?.toIntOrNull() ?: 0,
                    limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
                    offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                ).getOrThrow()
                Result(JSONObject()
                    .put("total", page.total)
                    .put("limit", page.limit)
                    .put("offset", page.offset)
                    .put("hasMore", page.hasMore)
                    .put("items", JSONArray().also { arr ->
                        for (r in page.items) arr.put(rommRomToJson(c, r))
                    }))
            }
            else -> throw IllegalArgumentException("romm: unknown op '$op'")
        }
    }

    /**
     * One RomM ROM, in the shape the rest of this dispatcher speaks.
     *
     * The hashes lead because they are the join: a caller matches these against
     * what the scan already wrote into `rom.hash`, `rom.fileMd5` and
     * `rom.fileCrc32` and needs no title comparison at all.
     */
    private fun rommRomToJson(c: RommClient.Credentials, r: RommClient.Rom): JSONObject =
        JSONObject()
            .put("id", r.id)
            .put("title", r.name)
            .put("fileName", r.fsName)
            .put("platform", r.platformSlug)
            .put("description", r.summary)
            .put("genres", JSONArray(r.genres))
            .put("developer", r.companies.firstOrNull().orEmpty())
            .put("publisher", r.companies.getOrNull(1).orEmpty())
            .put("hashes", JSONObject()
                .put("raHash", r.raHash)
                .put("md5", r.md5)
                .put("crc32", r.crc32)
                .put("sha1", r.sha1))
            .put("ids", JSONObject()
                .put("retroAchievements", r.raId)
                .put("igdb", r.igdbId)
                .put("screenScraper", r.screenScraperId))
            .put("coverUrl", RommClient.mediaUrl(c, r.coverLargePath))
            .put("videoUrl", RommClient.mediaUrl(c, r.videoPath))
            .put("screenshots", JSONArray(r.screenshotPaths.map { RommClient.mediaUrl(c, it) }))

    // ── Steam account ───────────────────────────────────────────────────────
    //
    // Separate from `steam`, which is the public store and needs no credentials.
    // This one answers only about the signed-in user, and its refusals include
    // one that is not a failure: a private profile is a setting to change, not an
    // error to retry and not an empty result to cache.

    private fun steamCreds(): SteamAccountClient.Credentials {
        val c = config.load().steam
            ?: throw IllegalStateException("missing steam block in credentials.json")
        if (c.apiKey.isBlank() || c.steamId.isBlank())
            throw IllegalStateException("missing steam.apiKey/steamId")
        return SteamAccountClient.Credentials(c.apiKey, c.steamId)
    }

    private fun dispatchSteamAccount(op: String, params: Map<String, String>): Result = when (op) {
        "library" -> {
            val arr = JSONArray()
            for (g in SteamAccountClient.ownedGames(steamCreds()).getOrThrow()) {
                arr.put(JSONObject()
                    .put("appId", g.appId)
                    .put("name", g.name)
                    .put("playtimeMinutes", g.playtimeMinutes)
                    .put("playtimeLast2WeeksMinutes", g.playtimeLast2WeeksMinutes)
                    .put("iconUrl", g.iconUrl)
                    .put("lastPlayedAt", g.lastPlayedAt)
                    .put("launchUri", SteamAccountClient.desktopLaunchUri(g.appId)))
            }
            Result(arr)
        }
        "achievements" -> {
            val p = SteamAccountClient.achievements(
                steamCreds(), paramInt(params, "appId", "appid")).getOrThrow()
            val arr = JSONArray()
            for (a in p.achievements) {
                arr.put(JSONObject()
                    .put("id", a.apiName)
                    .put("title", a.displayName.ifEmpty { a.apiName })
                    .put("description", a.description)
                    .put("unlocked", a.unlocked)
                    .put("unlockedAt", a.unlockedAt)
                    .put("iconUrl", if (a.unlocked) a.iconUrl else a.iconGrayUrl)
                    .put("hidden", a.hidden))
            }
            Result(JSONObject()
                .put("appId", p.appId)
                .put("gameName", p.gameName)
                .put("unlocked", p.unlocked)
                .put("total", p.total)
                .put("progress", p.fraction)
                // Named so nothing downstream is tempted to add it to a
                // RetroAchievements total. Two accounts, two catalogues; a
                // combined number would describe nothing that exists.
                .put("namespace", "steam")
                .put("achievements", arr))
        }
        "resolve" -> {
            val name = params["vanity"] ?: throw IllegalArgumentException("missing vanity")
            val key = config.load().steam?.apiKey
                ?: throw IllegalStateException("missing steam.apiKey")
            Result(JSONObject().put("steamId",
                SteamAccountClient.resolveVanity(key, name).getOrThrow()))
        }
        else -> throw IllegalArgumentException("steam-account: unknown op '$op'")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun paramInt(params: Map<String, String>, vararg keys: String): Int {
        for (k in keys) {
            val v = params[k]?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        throw IllegalArgumentException("missing numeric param: ${keys.joinToString("|")}")
    }

    private companion object {
        const val TAG = "ScrapeSourceDispatcher"
        const val SS_SYSTEMS_FILE = "screenscraper_systems.json"

        /** Enough for one screen's worth of games, far short of a library. */
        const val SS_CACHE_ENTRIES = 64

        /**
         * How long the system table is trusted: thirty days.
         *
         * It is a list of consoles. It gains an entry a few times a year, and
         * re-fetching it more often spends quota on an answer that has not changed —
         * but keeping it *forever*, which is what it did, means a system added after
         * the first fetch can never be found, and that failure is indistinguishable
         * from a ROM the database genuinely lacks.
         */
        const val SS_SYSTEMS_TTL_SECONDS = 30L * 24 * 60 * 60

        val MEDIA_KINDS = listOf("cover", "wheel", "wallpaper", "screenshot", "video")
    }
}
