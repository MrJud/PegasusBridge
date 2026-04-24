package com.pegasus.bridge.media.sources

import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.HttpClient
import org.json.JSONArray
import org.json.JSONObject

// Port di CoverScraperService.js — sezioni IGDB (~line 439-693)
// Twitch OAuth2 + /v4/games, /v4/covers, /v4/screenshots, /v4/artworks
object IgdbClient {

    private const val BASE         = "https://api.igdb.com/v4"
    private const val TWITCH_TOKEN = "https://id.twitch.tv/oauth2/token"

    data class IgdbGame(
        val id: Int,
        val name: String,
        val coverUrl: String,
        val summary: String,
        val genres: List<String>
    )

    data class IgdbImage(
        val id: Int,
        val url: String,
        val thumb: String,
        val width: Int,
        val height: Int
    )

    data class IgdbDetails(
        val title: String,
        val description: String,
        val genres: List<String>,
        val score: String?,
        val developer: String,
        val publisher: String,
        val releaseYear: Int?,
        val gameModes: List<String>
    )

    // Replicates _igdbImg() — replaces thumb slug with requested size
    private fun igdbImg(url: String, size: String): String {
        if (url.isEmpty()) return ""
        val full = if (url.startsWith("http")) url else "https:$url"
        return full.replace(Regex("/t_[a-z0-9_]+/"), "/$size/")
    }

    private fun postHeaders(clientId: String, token: String) = mapOf(
        "Client-ID"     to clientId,
        "Authorization" to "Bearer $token",
        "Content-Type"  to "text/plain"
    )

    // Mirrors fetchIGDBToken() — reads cache from credentials.json, re-auths if expired
    fun ensureToken(clientId: String, clientSecret: String): Result<String> {
        val creds = Config.load().igdb
        if (creds != null
            && creds.cachedToken.isNotEmpty()
            && creds.cachedTokenExp > System.currentTimeMillis() / 1000L + 60
        ) {
            return Result.success(creds.cachedToken)
        }

        val body = "client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                   "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}" +
                   "&grant_type=client_credentials"
        return HttpClient.post(TWITCH_TOKEN, body, "application/x-www-form-urlencoded").map { resp ->
            val j     = JSONObject(resp)
            val token = j.getString("access_token")
            val exp   = j.optLong("expires_in", 3600L)
            Config.saveToken("igdb", token, System.currentTimeMillis() / 1000L + exp)
            token
        }
    }

    // Mirrors searchIGDB()
    fun search(term: String, clientId: String, token: String): Result<List<IgdbGame>> {
        val query = """fields id,name,cover.url,summary,genres.name; search "${term.replace("\"", "")}"; limit 20;"""
        return HttpClient.post("$BASE/games", query, "text/plain", postHeaders(clientId, token)).map { body ->
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                val genres = g.optJSONArray("genres")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).optString("name") }
                } ?: emptyList()
                IgdbGame(
                    id       = g.optInt("id"),
                    name     = g.optString("name"),
                    coverUrl = igdbImg(g.optJSONObject("cover")?.optString("url") ?: "", "t_cover_big"),
                    summary  = g.optString("summary"),
                    genres   = genres
                )
            }
        }
    }

    // Mirrors getIGDBCovers()
    fun getCovers(gameId: Int, clientId: String, token: String): Result<List<IgdbImage>> {
        val query = "fields url,width,height; where game = $gameId; limit 10;"
        return HttpClient.post("$BASE/covers", query, "text/plain", postHeaders(clientId, token)).map { parseImages(it, "t_1080p", "t_cover_big") }
    }

    // Mirrors getIGDBScreenshots()
    fun getScreenshots(gameId: Int, clientId: String, token: String): Result<List<IgdbImage>> {
        val query = "fields url,width,height; where game = $gameId; limit 15;"
        return HttpClient.post("$BASE/screenshots", query, "text/plain", postHeaders(clientId, token)).map { parseImages(it, "t_1080p", "t_screenshot_big") }
    }

    // Mirrors getIGDBArtworks()
    fun getArtworks(gameId: Int, clientId: String, token: String): Result<List<IgdbImage>> {
        val query = "fields url,width,height; where game = $gameId; limit 15;"
        return HttpClient.post("$BASE/artworks", query, "text/plain", postHeaders(clientId, token)).map { parseImages(it, "t_1080p", "t_screenshot_big") }
    }

    // Mirrors getIGDBDetails()
    fun getDetails(gameId: Int, clientId: String, token: String): Result<IgdbDetails> {
        val query = "fields id,name,summary,storyline," +
            "genres.name," +
            "involved_companies.company.name,involved_companies.developer,involved_companies.publisher," +
            "first_release_date,game_modes.name," +
            "aggregated_rating,rating;" +
            " where id = $gameId; limit 1;"
        return HttpClient.post("$BASE/games", query, "text/plain", postHeaders(clientId, token)).map { body ->
            val arr = JSONArray(body)
            if (arr.length() == 0) throw Exception("Not found")
            val g = arr.getJSONObject(0)

            val genres = g.optJSONArray("genres")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("name") }
            } ?: emptyList()

            var developer = ""; var publisher = ""
            g.optJSONArray("involved_companies")?.let { a ->
                for (i in 0 until a.length()) {
                    val ic = a.getJSONObject(i)
                    val name = ic.optJSONObject("company")?.optString("name") ?: ""
                    if (ic.optBoolean("developer") && developer.isEmpty()) developer = name
                    if (ic.optBoolean("publisher") && publisher.isEmpty()) publisher = name
                }
            }

            val gameModes = g.optJSONArray("game_modes")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("name") }
            } ?: emptyList()

            val releaseYear = if (g.has("first_release_date"))
                java.util.Calendar.getInstance().also { cal ->
                    cal.timeInMillis = g.getLong("first_release_date") * 1000L
                }.get(java.util.Calendar.YEAR)
            else null

            val score = when {
                g.has("aggregated_rating") -> "${g.getDouble("aggregated_rating").toInt()}/100"
                g.has("rating")            -> "${g.getDouble("rating").toInt()}/100"
                else                       -> null
            }

            IgdbDetails(
                title       = g.optString("name"),
                description = g.optString("summary").ifEmpty { g.optString("storyline") },
                genres      = genres,
                score       = score,
                developer   = developer,
                publisher   = publisher,
                releaseYear = releaseYear,
                gameModes   = gameModes
            )
        }
    }

    private fun parseImages(body: String, fullSize: String, thumbSize: String): List<IgdbImage> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val it = arr.getJSONObject(i)
            val rawUrl = it.optString("url")
            IgdbImage(
                id     = it.optInt("id"),
                url    = igdbImg(rawUrl, fullSize),
                thumb  = igdbImg(rawUrl, thumbSize),
                width  = it.optInt("width"),
                height = it.optInt("height")
            )
        }
    }
}
