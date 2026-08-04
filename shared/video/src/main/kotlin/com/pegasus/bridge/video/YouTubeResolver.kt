package com.pegasus.bridge.video

import com.pegasus.bridge.core.BridgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.StreamInfo

private const val TAG = "PegasusBridge.Video"

// ── Result types ─────────────────────────────────────────────────────────────

data class ResolvedStreams(
    val title: String,
    val durationSec: Long,
    val progressive: Progressive?,
    val dashManifestUrl: String?,
    val dashSeparate: DashSeparate?
) {
    data class Progressive(
        val url: String,
        val mimeType: String,
        val resolutionLabel: String
    )
    data class DashSeparate(
        val videoUrl: String,
        val videoMimeType: String,
        val videoResolutionLabel: String,
        val audioUrl: String,
        val audioMimeType: String
    )
}

sealed class ResolveError(msg: String) : Exception(msg) {
    object VideoUnavailable : ResolveError("Video non disponibile o rimosso")
    object AgeRestricted    : ResolveError("Video con restrizione d'età (login richiesto)")
    object RegionBlocked    : ResolveError("Video bloccato in questa regione")
    object NoStreamFound    : ResolveError("Nessuno stream riproducibile trovato")
    object SignInRequired   : ResolveError("YouTube richiede verifica anti-bot — cambia rete o riprova")
    data class Generic(override val cause: Throwable) : ResolveError("Errore estrazione: ${cause.message}")
}

// ── Resolver ─────────────────────────────────────────────────────────────────

object YouTubeResolver {

    suspend fun resolve(url: String): ResolvedStreams = withContext(Dispatchers.IO) {
        NewPipeInit.ensure()

        val info: StreamInfo = try {
            StreamInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: AgeRestrictedContentException)    { throw ResolveError.AgeRestricted }
        catch (e: GeographicRestrictionException)    { throw ResolveError.RegionBlocked }
        catch (e: ContentNotAvailableException)      { throw ResolveError.VideoUnavailable }
        catch (e: SignInConfirmNotBotException)       { throw ResolveError.SignInRequired }
        catch (e: ExtractionException)               { throw ResolveError.Generic(e) }

        BridgeLog.i(TAG, "YT resolved: ${info.name} | streams=${info.videoStreams?.size} " +
                "videoOnly=${info.videoOnlyStreams?.size} audio=${info.audioStreams?.size} " +
                "dash=${info.dashMpdUrl?.take(60)}")

        // ── Progressive MP4 (audio+video, compatibile ≤720p)
        val progressive = info.videoStreams
            ?.filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            ?.sortedByDescending { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 0 }
            ?.let { list ->
                list.firstOrNull { (it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 0) <= 720 }
                    ?: list.firstOrNull()
            }?.let {
                ResolvedStreams.Progressive(
                    url              = it.content,
                    mimeType         = it.format?.mimeType ?: "video/mp4",
                    resolutionLabel  = it.resolution ?: ""
                )
            }

        val dashManifestUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() }

        val videoOnly = info.videoOnlyStreams
            ?.filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            ?.maxByOrNull { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 0 }

        val audioOnly = info.audioStreams
            ?.filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            ?.maxByOrNull { it.averageBitrate }

        val dashSeparate = if (videoOnly != null && audioOnly != null) {
            ResolvedStreams.DashSeparate(
                videoUrl             = videoOnly.content,
                videoMimeType        = videoOnly.format?.mimeType ?: "video/mp4",
                videoResolutionLabel = videoOnly.resolution ?: "",
                audioUrl             = audioOnly.content,
                audioMimeType        = audioOnly.format?.mimeType ?: "audio/mp4"
            )
        } else null

        if (progressive == null && dashManifestUrl == null && dashSeparate == null) {
            throw ResolveError.NoStreamFound
        }

        BridgeLog.i(TAG, "YT streams: progressive=${progressive?.resolutionLabel} " +
                "dash=${dashManifestUrl != null} dashSep=${dashSeparate?.videoResolutionLabel}")

        ResolvedStreams(
            title        = info.name ?: "",
            durationSec  = info.duration,
            progressive  = progressive,
            dashManifestUrl = dashManifestUrl,
            dashSeparate = dashSeparate
        )
    }
}
