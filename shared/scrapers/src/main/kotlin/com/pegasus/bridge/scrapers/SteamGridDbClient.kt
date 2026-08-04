package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.HttpClient
import org.json.JSONObject

// Port di CoverScraperService.js — sezioni SGDB (~line 46-337)
// Endpoint replicati byte-for-byte: /search/autocomplete, /grids/game, /logos/game,
//   /screenshots/game, /heroes/game
object SteamGridDbClient {

    // Dichiarate come `internal var` e non `const`: i test puntano queste
    // basi a un MockWebServer per esercitare i parser senza rete.
    internal var BASE = "https://www.steamgriddb.com/api/v2"

    data class SgdbItem(
        val id: Int,
        val url: String,
        val thumb: String,
        val width: Int,
        val height: Int,
        val style: String,
        val author: String
    )

    data class SgdbGame(val id: Int, val name: String, val types: List<String>, val verified: Boolean)

    private fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    private fun parseItems(body: String): List<SgdbItem> {
        val resp = JSONObject(body)
        if (!resp.optBoolean("success")) return emptyList()
        val data = resp.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            SgdbItem(
                id     = item.optInt("id"),
                url    = item.optString("url"),
                thumb  = item.optString("thumb"),
                width  = item.optInt("width"),
                height = item.optInt("height"),
                style  = item.optString("style"),
                author = item.optJSONObject("author")?.optString("name") ?: ""
            )
        }
    }

    // /search/autocomplete/{term}
    fun search(term: String, apiKey: String): Result<List<SgdbGame>> {
        val url = "$BASE/search/autocomplete/${java.net.URLEncoder.encode(term, "UTF-8")}"
        return HttpClient.get(url, headers(apiKey)).mapCatching { body ->
            val resp = JSONObject(body)
            if (!resp.optBoolean("success")) return@mapCatching emptyList()
            val data = resp.optJSONArray("data") ?: return@mapCatching emptyList()
            (0 until data.length()).map { i ->
                val g = data.getJSONObject(i)
                val types = g.optJSONArray("types")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                SgdbGame(
                    id       = g.optInt("id"),
                    name     = g.optString("name"),
                    types    = types,
                    verified = g.optBoolean("verified")
                )
            }
        }
    }

    // /grids/game/{id} — stesse dimensioni e filtri del JS
    fun getGrids(gameId: Int, apiKey: String): Result<List<SgdbItem>> {
        val url = "$BASE/grids/game/$gameId?dimensions=512x512,1024x1024,600x900,342x482,660x930&limit=24&types=static&nsfw=false&humor=false"
        return HttpClient.get(url, headers(apiKey)).mapCatching { parseItems(it) }
    }

    // /logos/game/{id}
    fun getLogos(gameId: Int, apiKey: String): Result<List<SgdbItem>> {
        val url = "$BASE/logos/game/$gameId?limit=12&types=static&nsfw=false&humor=false"
        return HttpClient.get(url, headers(apiKey)).mapCatching { parseItems(it) }
    }

    // /screenshots/game/{id}
    fun getScreenshots(gameId: Int, apiKey: String): Result<List<SgdbItem>> {
        val url = "$BASE/screenshots/game/$gameId?limit=24&nsfw=false&humor=false"
        return HttpClient.get(url, headers(apiKey)).mapCatching { body ->
            parseItems(body).map { it.copy(style = "screenshot") }
        }
    }

    // /heroes/game/{id}
    fun getHeroes(gameId: Int, apiKey: String): Result<List<SgdbItem>> {
        val url = "$BASE/heroes/game/$gameId?limit=12&types=static&nsfw=false&humor=false"
        return HttpClient.get(url, headers(apiKey)).mapCatching { parseItems(it) }
    }
}
