package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.Config




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
class ScrapeSourceDispatcher(private val config: Config) {

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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun paramInt(params: Map<String, String>, vararg keys: String): Int {
        for (k in keys) {
            val v = params[k]?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        throw IllegalArgumentException("missing numeric param: ${keys.joinToString("|")}")
    }
}
