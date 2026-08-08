package com.pegasus.bridge.media

import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.hasher.PlainRomHasher
import com.pegasus.bridge.scrapers.ScreenScraperClient
import com.pegasus.bridge.scrapers.ScreenScraperSystemMap
import java.io.File
import com.pegasus.bridge.media.sources.IgdbClient
import com.pegasus.bridge.media.sources.IgnClient
import com.pegasus.bridge.media.sources.SteamGridDbClient
import com.pegasus.bridge.media.sources.SteamStoreClient
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
object ScrapeSourceDispatcher {

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
        Config.load().steamGridDb?.apiKey
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
        val creds = Config.load().igdb
            ?: throw IllegalStateException("missing igdb block in credentials.json")
        if (creds.clientId.isEmpty() || creds.clientSecret.isEmpty())
            throw IllegalStateException("missing igdb.clientId/clientSecret")
        return creds.clientId to creds.clientSecret
    }

    private fun dispatchIgdb(op: String, params: Map<String, String>): Result {
        val (clientId, clientSecret) = igdbCreds()
        // Tutte le ops IGDB richiedono un token valido — ensureToken() lo rinfresca se serve.
        val token = if (op == "token") {
            IgdbClient.ensureToken(clientId, clientSecret).getOrThrow()
        } else {
            IgdbClient.ensureToken(clientId, clientSecret).getOrThrow()
        }

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
    // The twin of the `ss` block in `shared/scrapers/.../ScrapeSourceDispatcher.kt`, and
    // it has to answer with the **same JSON**: the theme is one codebase and reads this
    // payload identically on both platforms.
    //
    // What is *not* duplicated is everything worth not duplicating.
    // `ScreenScraperClient` and `ScreenScraperSystemMap` are compiled from a single copy
    // in `shared/scrapers/src/android-shared`, so every parser, the refusal taxonomy and
    // the system table are literally the same code here as on the desktop. Only the
    // dispatch differs, because the two shells disagree about the shapes underneath it:
    // `Config` is an object here and a class there, and the data root is `Paths` here
    // and an injected `BridgePaths` there.
    //
    // The odd one out among the sources, and deliberately so: every other one is asked
    // "which game is called this?" and answers with a list to match by title. This one
    // is asked "what is this file?" and answers with one game or none.

    /**
     * The last few identified ROMs, keyed by file path.
     *
     * Small and in memory on purpose: it exists so one game's four contents cost one
     * request rather than four, not to remember a library. Persisting it would promise
     * the database never gains a game.
     */
    private val ssCache = object : LinkedHashMap<String, ScreenScraperClient.Game>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ScreenScraperClient.Game>?
        ): Boolean = size > 64
    }

    private const val SS_SYSTEMS_FILE = "screenscraper_systems.json"

    private fun dispatchScreenScraper(op: String, params: Map<String, String>): Result = when (op) {
        "game"    -> Result(ssGameToJson(ssIdentify(params)))
        "media"   -> Result(ssFetchMedia(params))
        "systems" -> Result(ssSystems())
        else      -> throw IllegalArgumentException("ss: unknown op '$op'")
    }

    private fun ssSystems(): JSONArray {
        val list = ScreenScraperClient.systems(Config).getOrThrow()
        try {
            Paths.CACHE.mkdirs()
            Paths.cache(SS_SYSTEMS_FILE).writeText(ScreenScraperSystemMap.toJson(list))
        } catch (t: Throwable) {
            // A cache that cannot be written costs a request next time, nothing more.
            android.util.Log.w(TAG, "could not cache the ScreenScraper systems: ${t.message}")
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

    /** The cached table, fetched once if it is not there. Empty is a usable answer. */
    private fun ssSystemIndex(): Map<String, Int> {
        val f = Paths.cache(SS_SYSTEMS_FILE)
        if (f.isFile) {
            val cached = ScreenScraperSystemMap.fromJson(f.readText())
            if (cached.isNotEmpty()) return ScreenScraperSystemMap.index(cached)
        }
        return try {
            ssSystems()
            val refreshed = if (f.isFile) ScreenScraperSystemMap.fromJson(f.readText()) else emptyList()
            ScreenScraperSystemMap.index(refreshed)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "no ScreenScraper system table: ${t.message}")
            emptyMap()
        }
    }

    private fun ssIdentify(params: Map<String, String>): Pair<ScreenScraperClient.Game, String> {
        val path = params["file"].orEmpty()
        if (path.isEmpty()) throw IllegalArgumentException("missing file")
        val platform = params["platform"].orEmpty()
        val lang = params["lang"]?.takeIf { it.isNotEmpty() } ?: "en"

        val key = "$platform|$path"
        ssCache[key]?.let { return it to path }

        val byName = ScreenScraperSystemMap.matchedByName(platform)
        val systemeId = params["systemeid"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: ScreenScraperSystemMap.systemeId(platform, ssSystemIndex())
        val name = File(path).name

        val game = if (byName) {
            // Arcade: the romset name is the identity, and the hashes would describe the
            // wrong thing — a MAME zip holds a pile of separately-dumped chips, so
            // neither the archive's digest nor its largest entry's means anything to the
            // database. Sending them alongside the name is a worse request, not a safer one.
            ScreenScraperClient.jeuInfos(Config, romName = name, systemeId = systemeId, lang = lang)
        } else {
            Paths.CACHE.mkdirs()
            val h = PlainRomHasher.hash(path, Paths.CACHE)
                ?: throw IllegalStateException("could not read $name")
            ScreenScraperClient.jeuInfos(
                Config, md5 = h.md5, crc = h.crc32, size = h.size,
                romName = h.name, systemeId = systemeId, lang = lang)
        }.getOrThrow()

        ssCache[key] = game
        return game to path
    }

    /**
     * The identified game, plus which kinds of art it has.
     *
     * `kinds` is what makes one request serve a whole screen: without it the theme would
     * have to ask for a wheel to discover there is no wheel, and every question costs quota.
     */
    private fun ssGameToJson(pair: Pair<ScreenScraperClient.Game, String>): JSONObject {
        val (g, path) = pair
        val kinds = JSONObject()
        for (kind in listOf("cover", "wheel", "wallpaper", "screenshot", "video"))
            kinds.put(kind, ScreenScraperClient.pickMedia(g.media, kind, File(path).name) != null)

        // Types and regions only. The URLs are deliberately absent: a ScreenScraper media
        // URL carries devid, devpassword and sspassword in its query string, and handing
        // one to the theme would write the credentials into an override map on disk.
        val media = JSONArray()
        for (m in g.media)
            media.put(JSONObject().put("type", m.type).put("region", m.region).put("format", m.format))

        return JSONObject()
            .put("id", g.id)
            .put("title", g.title)
            .put("developer", g.developer)
            .put("publisher", g.publisher)
            .put("genres", JSONArray(g.genres))
            .put("releaseYear", g.releaseYear)
            .put("gameModes", JSONArray(listOf(g.players).filter { it.isNotEmpty() }))
            .put("description", g.description)
            .put("score", ScreenScraperClient.scoreOutOf20(g.rating))
            .put("kinds", kinds)
            .put("media", media)
            .put("coverUrl", "")
    }

    /** Downloads the best media of one kind and answers with where it landed. */
    private fun ssFetchMedia(params: Map<String, String>): JSONObject {
        val kind = params["kind"] ?: throw IllegalArgumentException("missing kind")
        val (game, path) = ssIdentify(params)
        val romName = File(path).name

        val media = ScreenScraperClient.pickMedia(game.media, kind, romName)
            ?: return JSONObject().put("localPath", "").put("kind", kind)

        val ext = media.format.ifEmpty { if (kind == "video") "mp4" else "png" }
        val id = game.id.ifEmpty { romName.replace(Regex("[^A-Za-z0-9]"), "") }
        Paths.ARTWORK.mkdirs()
        val target = Paths.artwork("ss-$id-$kind.$ext")
        // Named by the game's id, never by its title: two ROMs of the same game in
        // different regions carry different art, and a title-keyed file would let the
        // second overwrite the first. Already-fetched files are reused — the picture
        // cannot change under an id, so re-downloading spends quota for the same bytes.
        if (!target.isFile || target.length() == 0L)
            ScreenScraperClient.fetchMedia(Config, media, target).getOrThrow()

        return JSONObject()
            .put("localPath", target.absolutePath)
            .put("kind", kind)
            .put("type", media.type)
            .put("region", media.region)
            .put("bytes", target.length())
    }

    private const val TAG = "ScrapeSourceDispatcher"

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun paramInt(params: Map<String, String>, vararg keys: String): Int {
        for (k in keys) {
            val v = params[k]?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        throw IllegalArgumentException("missing numeric param: ${keys.joinToString("|")}")
    }
}
