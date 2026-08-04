package com.pegasus.bridge.media.sources

import com.pegasus.bridge.core.HttpClient
import org.json.JSONObject

// Port di CoverScraperService.js — sezione Steam Store (~line 343-433)
// Nessuna autenticazione richiesta per i dati pubblici.
// Film: preferisce HLS/DASH (API 2024+), fallback su mp4 legacy.
object SteamStoreClient {

    private const val BASE = "https://store.steampowered.com/api"

    data class SteamGame(val appId: Int, val name: String, val tinyImage: String)

    data class SteamScreenshot(val id: Int, val thumb: String, val full: String)

    data class SteamMovie(
        val id: Int,
        val name: String,
        val thumbnail: String,
        val mp4: String,     // preferred: hls || dash || legacy max/480 (come nel JS)
        val hls: String,
        val dash: String,
        val mp4_480: String, // legacy API: raw m.mp4.480  (usato dal theme per quality="Low")
        val mp4_max: String  // legacy API: raw m.mp4.max  (usato dal theme per quality="Max")
    )

    data class SteamAssets(
        val appId: Int,
        val name: String,
        val headerImage: String,
        val backgroundRaw: String,
        val screenshots: List<SteamScreenshot>,
        val movies: List<SteamMovie>
    )

    // Mirrors searchSteam()
    fun search(term: String): Result<List<SteamGame>> {
        val url = "$BASE/storesearch/?term=${java.net.URLEncoder.encode(term, "UTF-8")}&cc=us&l=en"
        return HttpClient.get(url).map { body ->
            val resp = JSONObject(body)
            val items = resp.optJSONArray("items") ?: return@map emptyList()
            (0 until items.length()).map { i ->
                val it = items.getJSONObject(i)
                SteamGame(
                    appId      = it.optInt("id"),
                    name       = it.optString("name"),
                    tinyImage  = it.optString("tiny_image")
                )
            }
        }
    }

    // Mirrors getSteamAssets() — include HLS/DASH handling per le API 2024+
    fun getAssets(appId: Int): Result<SteamAssets> {
        val url = "$BASE/appdetails?appids=$appId&cc=us&l=en&filters=basic,screenshots,movies,background"
        return HttpClient.get(url).map { body ->
            val resp    = JSONObject(body)
            val appData = resp.optJSONObject(appId.toString())
                ?: throw Exception("App $appId not found")
            if (!appData.optBoolean("success")) throw Exception("App $appId: success=false")
            val d = appData.getJSONObject("data")

            val screenshots = d.optJSONArray("screenshots")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    SteamScreenshot(
                        id    = s.optInt("id"),
                        thumb = s.optString("path_thumbnail"),
                        full  = s.optString("path_full")
                    )
                }
            } ?: emptyList()

            val movies = d.optJSONArray("movies")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    // Preferisci HLS/DASH (2024+), poi mp4 legacy — identico al JS
                    val hls  = m.optString("hls_h264").replace("http:", "https:", ignoreCase = true)
                    val dash = m.optString("dash_h264").replace("http:", "https:", ignoreCase = true)
                    val mp4Obj = m.optJSONObject("mp4")
                    val mp4_480 = mp4Obj?.optString("480").orEmpty()
                        .replace("http:", "https:", ignoreCase = true)
                    val mp4_max = mp4Obj?.optString("max").orEmpty()
                        .replace("http:", "https:", ignoreCase = true)
                    val legacyMp4 = mp4_480.ifEmpty { mp4_max }
                    val primary = hls.ifEmpty { dash.ifEmpty { legacyMp4 } }
                    SteamMovie(
                        id        = m.optInt("id"),
                        name      = m.optString("name"),
                        thumbnail = m.optString("thumbnail"),
                        mp4       = primary,
                        hls       = hls,
                        dash      = dash,
                        mp4_480   = mp4_480,
                        mp4_max   = mp4_max
                    )
                }
            } ?: emptyList()

            SteamAssets(
                appId         = appId,
                name          = d.optString("name"),
                headerImage   = d.optString("header_image"),
                backgroundRaw = d.optString("background_raw").ifEmpty { d.optString("background") },
                screenshots   = screenshots,
                movies        = movies
            )
        }
    }
}
