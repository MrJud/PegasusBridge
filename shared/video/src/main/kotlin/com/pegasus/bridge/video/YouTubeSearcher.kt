package com.pegasus.bridge.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeSearcher {

    data class Result(
        val videoId: String,
        val title: String,
        val author: String,
        val durationSec: Long,
        val thumbUrl: String
    )

    suspend fun search(query: String, limit: Int = 20): List<Result> = withContext(Dispatchers.IO) {
        NewPipeInit.ensure()

        val yt = ServiceList.YouTube
        val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            ""
        )
        val extractor = yt.getSearchExtractor(handler)
        extractor.fetchPage()

        extractor.initialPage.items
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { toResult(it) }
            .take(limit)
            .toList()
    }

    private fun toResult(item: StreamInfoItem): Result? {
        val url = item.url ?: return null
        val vid = extractVideoId(url) ?: return null
        return Result(
            videoId     = vid,
            title       = item.name.orEmpty(),
            author      = item.uploaderName.orEmpty(),
            durationSec = item.duration.coerceAtLeast(0),
            thumbUrl    = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
        )
    }

    private val ID_RE = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private fun extractVideoId(url: String): String? = ID_RE.find(url)?.groupValues?.get(1)
}
