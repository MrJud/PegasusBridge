package com.pegasus.bridge.media

import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONArray
import org.json.JSONObject

data class MediaImage(
    val url: String,
    val thumb: String,
    val source: String
)

data class MediaVideo(
    val mp4: String,
    val hls: String,
    val dash: String,
    val thumb: String,
    val source: String
)

data class MediaText(val text: String, val source: String)
data class MediaRating(val value: String, val source: String)

// Output finale scritto in /sdcard/PegasusData/media/{gameId}.json
data class MediaPayload(
    val gameId: String,
    val fetchedAt: Long,
    val sources: List<String>,          // quali client sono stati interrogati
    val cover: MediaImage?,
    val video: MediaVideo?,
    val description: MediaText?,
    val rating: MediaRating?,
    val screenshots: List<MediaImage>,
    val genres: List<String>,
    val developer: String,
    val releaseDate: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", SchemaVersion.CURRENT)
        put("gameId",   gameId)
        put("fetchedAt", fetchedAt)
        put("sources",  JSONArray(sources))
        cover?.let { put("cover", JSONObject().put("url", it.url).put("thumb", it.thumb).put("source", it.source)) }
        video?.let { put("video", JSONObject().put("mp4", it.mp4).put("hls", it.hls).put("dash", it.dash).put("thumb", it.thumb).put("source", it.source)) }
        description?.let { put("description", JSONObject().put("text", it.text).put("source", it.source)) }
        rating?.let { put("rating", JSONObject().put("value", it.value).put("source", it.source)) }
        put("screenshots", JSONArray(screenshots.map { s ->
            JSONObject().put("url", s.url).put("thumb", s.thumb).put("source", s.source)
        }))
        put("genres",      JSONArray(genres))
        put("developer",   developer)
        put("releaseDate", releaseDate)
    }
}

// Risultato parziale da ogni singolo client — campi nullable = non trovato
data class PartialMedia(
    val source: String,
    val coverUrl: String?         = null,
    val coverThumb: String?       = null,
    val videoMp4: String?         = null,
    val videoHls: String?         = null,
    val videoDash: String?        = null,
    val videoThumb: String?       = null,
    val description: String?      = null,
    val rating: String?           = null,
    val screenshots: List<Pair<String, String>> = emptyList(), // url to thumb
    val genres: List<String>      = emptyList(),
    val developer: String?        = null,
    val releaseDate: String?      = null
)
