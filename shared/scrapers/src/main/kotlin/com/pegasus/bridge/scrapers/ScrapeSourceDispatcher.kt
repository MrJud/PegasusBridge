package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.hasher.PlainRomHasher
import java.io.File
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
     * The last few identified ROMs, keyed by file path.
     *
     * Small and in memory on purpose: it exists to stop one game's four contents
     * costing four requests, not to remember a library. Persisting it would mean
     * promising the database never gains a game, and a wrong "not found" that survives
     * a restart is the shape of bug this project has already paid for once.
     */
    private val ssCache = object : LinkedHashMap<String, ScreenScraperClient.Game>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ScreenScraperClient.Game>?
        ): Boolean = size > 64
    }

    private fun dispatchScreenScraper(op: String, params: Map<String, String>): Result = when (op) {
        "game"    -> Result(ssGameToJson(ssIdentify(params)))
        "media"   -> Result(ssFetchMedia(params))
        "systems" -> Result(ssSystems())
        else      -> throw IllegalArgumentException("ss: unknown op '$op'")
    }

    /** The system table, refreshed from the API and kept on disk between runs. */
    private fun ssSystems(): JSONArray {
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
     * The cached table, fetched once if it is not there.
     *
     * Empty is a usable answer: without it a hash lookup still works, and only a
     * `romnom` one — arcade — actually needs an id.
     */
    private fun ssSystemIndex(): Map<String, Int> {
        val p = paths ?: return emptyMap()
        val f = p.cache(SS_SYSTEMS_FILE)
        if (f.isFile) {
            val cached = ScreenScraperSystemMap.fromJson(f.readText())
            if (cached.isNotEmpty()) return ScreenScraperSystemMap.index(cached)
        }
        return try {
            ssSystems()
            val refreshed = if (f.isFile) ScreenScraperSystemMap.fromJson(f.readText()) else emptyList()
            ScreenScraperSystemMap.index(refreshed)
        } catch (t: Throwable) {
            BridgeLog.w(TAG, "no ScreenScraper system table: ${t.message}")
            emptyMap()
        }
    }

    private fun ssIdentify(params: Map<String, String>): Pair<ScreenScraperClient.Game, String> {
        val path = params["file"].orEmpty()
        if (path.isEmpty()) throw IllegalArgumentException("missing file")
        val platform = params["platform"].orEmpty()
        val lang = params["lang"]?.takeIf { it.isNotEmpty() } ?: "en"

        val cached = ssCache[cacheKey(path, platform)]
        if (cached != null) return cached to path

        val byName = ScreenScraperSystemMap.matchedByName(platform)
        // An explicit id overrides the table. It exists because the table's arcade entry
        // could only ever be settled by measurement — the API publishes sixty-odd boards
        // that all answer to "arcade" — and a probe needs to be able to ask "which of
        // these actually answers for pacman.zip?" without editing and rebuilding the
        // daemon between guesses.
        val systemeId = params["systemeid"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: ScreenScraperSystemMap.systemeId(platform, ssSystemIndex())
        val name = File(path).name

        val game = if (byName) {
            // Arcade: the romset name is the identity and the hashes would describe the
            // wrong thing entirely — a MAME zip holds a pile of separately-dumped chips,
            // so neither the archive's digest nor its largest entry's means anything to
            // the database. Sending them alongside the name is not a belt-and-braces
            // fallback, it is a worse request.
            //
            // No system id is required, and for the MAME family there is none to give:
            // ScreenScraper models the *boards*, sixty-odd of them, and every one calls
            // itself `arcade`. `systemeId` refuses to pick one for that reason, so this
            // sends 0 and lets the romset name — which is unique across boards — do the
            // work it is already doing. Neo Geo is a real single system and does get one.
            ScreenScraperClient.jeuInfos(config, romName = name, systemeId = systemeId, lang = lang)
        } else {
            val tempDir = paths?.cache ?: File(System.getProperty("java.io.tmpdir"), "pegasus-bridge")
            val h = PlainRomHasher.hash(path, tempDir)
                ?: throw IllegalStateException("could not read $name")
            ScreenScraperClient.jeuInfos(
                config, md5 = h.md5, crc = h.crc32, size = h.size,
                romName = h.name, systemeId = systemeId, lang = lang)
        }.getOrThrow()

        ssCache[cacheKey(path, platform)] = game
        return game to path
    }

    private fun cacheKey(path: String, platform: String) = "$platform|$path"

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
    private fun ssGameToJson(pair: Pair<ScreenScraperClient.Game, String>): JSONObject {
        val (g, path) = pair
        val kinds = JSONObject()
        for (kind in listOf("cover", "wheel", "wallpaper", "screenshot", "video"))
            kinds.put(kind, ScreenScraperClient.pickMedia(g.media, kind, File(path).name) != null)

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
     * Named by digest rather than by title: two ROMs of the same game in different
     * regions carry different art, and a title-keyed file would let the second overwrite
     * the first. Already-fetched files are reused — the picture cannot change under a
     * digest, so re-downloading it would spend quota to get the same bytes.
     */
    private fun ssFetchMedia(params: Map<String, String>): JSONObject {
        val p = paths ?: throw IllegalStateException("no data root: cannot store fetched media")
        val kind = params["kind"] ?: throw IllegalArgumentException("missing kind")
        val (game, path) = ssIdentify(params)
        val romName = File(path).name

        val media = ScreenScraperClient.pickMedia(game.media, kind, romName)
            ?: return JSONObject().put("localPath", "").put("kind", kind)

        val ext = media.format.ifEmpty { if (kind == "video") "mp4" else "png" }
        val id = game.id.ifEmpty { romName.replace(Regex("[^A-Za-z0-9]"), "") }
        val target = p.artwork("ss-$id-$kind.$ext")
        if (!target.isFile || target.length() == 0L)
            ScreenScraperClient.fetchMedia(config, media, target).getOrThrow()

        return JSONObject()
            .put("localPath", target.absolutePath)
            .put("kind", kind)
            .put("type", media.type)
            .put("region", media.region)
            .put("bytes", target.length())
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
    }
}
