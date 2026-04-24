package com.pegasus.bridge.media

import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.media.sources.IgdbClient
import com.pegasus.bridge.media.sources.IgnClient
import com.pegasus.bridge.media.sources.SteamGridDbClient
import com.pegasus.bridge.media.sources.SteamStoreClient

// Catena di priorità (da 06_SCRAPERS.md): IGDB → Steam → IGN → SGDB
// Per ogni campo: prende il primo non-null in ordine di priorità.
object MediaAggregator {

    fun scrape(gameId: String, title: String, platform: String): MediaPayload {
        val creds       = Config.load()
        val queriedSources = mutableListOf<String>()
        val partials    = mutableListOf<PartialMedia>()

        // ── IGDB ─────────────────────────────────────────────────
        val igdbCreds = creds.igdb
        if (igdbCreds != null && igdbCreds.clientId.isNotEmpty()) {
            queriedSources += "igdb"
            val tokenResult = IgdbClient.ensureToken(igdbCreds.clientId, igdbCreds.clientSecret)
            tokenResult.onSuccess { token ->
                val games = IgdbClient.search(title, igdbCreds.clientId, token).getOrNull()
                val best  = games?.let { findBest(title, it.map { g -> g.id to g.name }) }
                if (best != null) {
                    val id = best.first
                    val covers      = IgdbClient.getCovers(id, igdbCreds.clientId, token).getOrNull()
                    val screenshots = IgdbClient.getScreenshots(id, igdbCreds.clientId, token).getOrNull()
                    val details     = IgdbClient.getDetails(id, igdbCreds.clientId, token).getOrNull()
                    partials += PartialMedia(
                        source      = "igdb",
                        coverUrl    = covers?.firstOrNull()?.url,
                        coverThumb  = covers?.firstOrNull()?.thumb,
                        description = details?.description,
                        rating      = details?.score,
                        screenshots = screenshots?.map { it.url to it.thumb } ?: emptyList(),
                        genres      = details?.genres ?: emptyList(),
                        developer   = details?.developer,
                        releaseDate = details?.releaseYear?.toString()
                    )
                }
            }
        } else {
            queriedSources += "igdb:skipped(no-creds)"
        }

        // ── Steam ─────────────────────────────────────────────────
        queriedSources += "steam"
        val steamGames = SteamStoreClient.search(title).getOrNull()
        val steamBest  = steamGames?.let { findBest(title, it.map { g -> g.appId to g.name }) }
        if (steamBest != null) {
            val assets = SteamStoreClient.getAssets(steamBest.first).getOrNull()
            if (assets != null) {
                val firstMovie = assets.movies.firstOrNull()
                partials += PartialMedia(
                    source      = "steam",
                    coverUrl    = assets.headerImage,
                    coverThumb  = assets.headerImage,
                    videoMp4    = firstMovie?.mp4,
                    videoHls    = firstMovie?.hls,
                    videoDash   = firstMovie?.dash,
                    videoThumb  = firstMovie?.thumbnail,
                    screenshots = assets.screenshots.map { it.full to it.thumb }
                )
            }
        }

        // ── IGN ───────────────────────────────────────────────────
        queriedSources += "ign"
        val ignGames = IgnClient.search(title).getOrNull()
        val ignBest  = ignGames?.let { list ->
            val bestScore = list.maxByOrNull { FuzzyMatch.similarity(title, it.title) }
            if (bestScore != null && FuzzyMatch.similarity(title, bestScore.title) >= 0.6) bestScore else null
        }
        if (ignBest != null) {
            val details = IgnClient.getDetails(ignBest.slug).getOrNull()
            if (details != null) {
                partials += PartialMedia(
                    source      = "ign",
                    coverUrl    = details.coverUrl,
                    coverThumb  = details.coverUrl,
                    description = details.description,
                    rating      = details.score?.let { "$it/10" },
                    genres      = details.genres
                )
            }
        }

        // ── SteamGridDB ───────────────────────────────────────────
        val sgdbKey = creds.steamGridDb?.apiKey
        if (!sgdbKey.isNullOrEmpty()) {
            queriedSources += "sgdb"
            val sgdbGames = SteamGridDbClient.search(title, sgdbKey).getOrNull()
            val sgdbBest  = sgdbGames?.let { findBest(title, it.map { g -> g.id to g.name }) }
            if (sgdbBest != null) {
                val grids  = SteamGridDbClient.getGrids(sgdbBest.first, sgdbKey).getOrNull()
                val heroes = SteamGridDbClient.getHeroes(sgdbBest.first, sgdbKey).getOrNull()
                val shots  = SteamGridDbClient.getScreenshots(sgdbBest.first, sgdbKey).getOrNull()
                partials += PartialMedia(
                    source      = "sgdb",
                    coverUrl    = grids?.firstOrNull()?.url,
                    coverThumb  = grids?.firstOrNull()?.thumb,
                    screenshots = (heroes ?: emptyList()).map { it.url to it.thumb } +
                                  (shots  ?: emptyList()).map { it.url to it.thumb }
                )
            }
        } else {
            queriedSources += "sgdb:skipped(no-creds)"
        }

        return merge(gameId, queriedSources, partials)
    }

    // Seleziona per ogni campo il primo non-null nell'ordine dei partials (già ordinati per priorità)
    private fun merge(gameId: String, sources: List<String>, partials: List<PartialMedia>): MediaPayload {
        fun <T> first(selector: (PartialMedia) -> T?): Pair<T, String>? =
            partials.firstNotNullOfOrNull { p -> selector(p)?.let { it to p.source } }

        val cover = first { it.coverUrl }?.let { (url, src) ->
            val thumb = partials.firstOrNull { it.source == src }?.coverThumb ?: url
            MediaImage(url, thumb, src)
        }

        val video = first { it.videoMp4 }?.let { (mp4, src) ->
            val p = partials.first { it.source == src }
            MediaVideo(mp4, p.videoHls ?: "", p.videoDash ?: "", p.videoThumb ?: "", src)
        }

        val description = first { it.description?.ifEmpty { null } }?.let { (text, src) -> MediaText(text, src) }
        val rating      = first { it.rating?.ifEmpty { null } }?.let { (value, src) -> MediaRating(value, src) }

        val screenshots = partials.flatMap { p ->
            p.screenshots.map { (url, thumb) -> MediaImage(url, thumb, p.source) }
        }.distinctBy { it.url }.take(20)

        val genres      = first { it.genres.ifEmpty { null } }?.first ?: emptyList()
        val developer   = first { it.developer?.ifEmpty { null } }?.first ?: ""
        val releaseDate = first { it.releaseDate?.ifEmpty { null } }?.first

        return MediaPayload(
            gameId      = gameId,
            fetchedAt   = System.currentTimeMillis() / 1000L,
            sources     = sources,
            cover       = cover,
            video       = video,
            description = description,
            rating      = rating,
            screenshots = screenshots,
            genres      = genres,
            developer   = developer,
            releaseDate = releaseDate
        )
    }

    // Fuzzy-match tra il titolo cercato e una lista id→name, restituisce la coppia migliore
    private fun findBest(query: String, candidates: List<Pair<Int, String>>): Pair<Int, String>? {
        val best = candidates.maxByOrNull { FuzzyMatch.similarity(query, it.second) } ?: return null
        return if (FuzzyMatch.similarity(query, best.second) >= 0.5) best else null
    }
}
