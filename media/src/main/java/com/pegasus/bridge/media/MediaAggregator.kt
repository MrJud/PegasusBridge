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

    /**
     * What `sources` says about one source, and why it is not just a name.
     *
     * The list used to hold the bare name of every client that was *asked*, so
     * a source that was asked and answered looked exactly like one that was
     * asked and failed. Measured on the tablet with a SteamGridDB key that
     * SteamGridDB itself rejects: every scraped game listed `sgdb` among its
     * sources and not one of them carried a single picture from it. A reader —
     * a theme, or a person — reasonably concluded it had worked.
     *
     * So a name now carries what became of it:
     *
     * | `igdb`                     | asked, matched, had its say |
     * | `igdb:none`                | asked, answered, nothing matched — a result |
     * | `igdb:failed(401 …)`       | the call itself did not complete |
     * | `igdb:skipped(no-creds)`   | never asked, nothing configured |
     *
     * Only the bare name means the source contributed. The suffix is the
     * difference between "this game is not in that database" and "that database
     * would not talk to us", which is the distinction the hash scan already
     * makes and this one did not.
     */
    private const val NONE    = ":none"
    private const val SKIPPED = ":skipped(no-creds)"

    /** A short reason for a `failed(…)` suffix. */
    private fun why(t: Throwable?): String =
        t?.message?.takeIf { it.isNotBlank() }?.take(80)
            ?: t?.javaClass?.simpleName
            ?: "unknown"

    fun scrape(gameId: String, title: String, platform: String): MediaPayload {
        val creds       = Config.load()
        val queriedSources = mutableListOf<String>()
        val partials    = mutableListOf<PartialMedia>()

        // ── IGDB ─────────────────────────────────────────────────
        val igdbCreds = creds.igdb
        if (igdbCreds != null && igdbCreds.clientId.isNotEmpty()) {
            val tokenResult = IgdbClient.ensureToken(igdbCreds.clientId, igdbCreds.clientSecret)
            val token = tokenResult.getOrNull()
            if (token == null) {
                queriedSources += "igdb:failed(${why(tokenResult.exceptionOrNull())})"
            } else {
                val searchResult = IgdbClient.search(title, igdbCreds.clientId, token)
                val games = searchResult.getOrNull()
                val best  = games?.let { findBest(title, it.map { g -> g.id to g.name }) }
                if (searchResult.isFailure) {
                    queriedSources += "igdb:failed(${why(searchResult.exceptionOrNull())})"
                } else if (best == null) {
                    queriedSources += "igdb$NONE"
                } else {
                    queriedSources += "igdb"
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
            queriedSources += "igdb$SKIPPED"
        }

        // ── Steam ─────────────────────────────────────────────────
        val steamSearch = SteamStoreClient.search(title)
        val steamGames  = steamSearch.getOrNull()
        val steamBest   = steamGames?.let { findBest(title, it.map { g -> g.appId to g.name }) }
        queriedSources += when {
            steamSearch.isFailure -> "steam:failed(${why(steamSearch.exceptionOrNull())})"
            steamBest == null     -> "steam$NONE"
            else                  -> "steam"
        }
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
        val ignSearch = IgnClient.search(title)
        val ignGames  = ignSearch.getOrNull()
        val ignBest   = ignGames?.let { list ->
            val bestScore = list.maxByOrNull { FuzzyMatch.similarity(title, it.title) }
            if (bestScore != null && FuzzyMatch.similarity(title, bestScore.title) >= 0.6) bestScore else null
        }
        queriedSources += when {
            ignSearch.isFailure -> "ign:failed(${why(ignSearch.exceptionOrNull())})"
            ignBest == null     -> "ign$NONE"
            else                -> "ign"
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
            val sgdbSearch = SteamGridDbClient.search(title, sgdbKey)
            val sgdbGames  = sgdbSearch.getOrNull()
            val sgdbBest   = sgdbGames?.let { findBest(title, it.map { g -> g.id to g.name }) }
            queriedSources += when {
                sgdbSearch.isFailure -> "sgdb:failed(${why(sgdbSearch.exceptionOrNull())})"
                sgdbBest == null     -> "sgdb$NONE"
                else                 -> "sgdb"
            }
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
            queriedSources += "sgdb$SKIPPED"
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
