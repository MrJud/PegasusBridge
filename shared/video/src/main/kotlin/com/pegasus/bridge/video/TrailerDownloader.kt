package com.pegasus.bridge.video

import com.pegasus.bridge.core.BridgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "PegasusBridge.TrailerDL"

/**
 * Downloads a video trailer (YouTube or direct HTTPS MP4) to a local file.
 *
 * For YouTube URLs, stream resolution is delegated to [YouTubeResolver] (NewPipe extractor);
 * only the progressive (single-file) stream is used — DASH separate streams require muxing
 * which is not supported here.
 *
 * For direct HTTPS URLs (Steam CDN, etc.) the URL is downloaded as-is.
 *
 * Progress is written every ~2 seconds via [DownloadCallback.atomicWrite] so the QML
 * polling loop can update its UI.
 */
object TrailerDownloader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * @param url           YouTube page URL or direct HTTPS MP4 URL.
     * @param outVideoFile  Target local file (will be created/overwritten).
     * @param callbackFile  JSON file QML polls for progress/completion.
     */
    suspend fun download(
        url: String,
        outVideoFile: File,
        callbackFile: File
    ) = withContext(Dispatchers.IO) {

        val directUrl = try {
            if (YouTubeUrlDetector.isYouTube(url)) {
                BridgeLog.i(TAG, "Resolving YouTube URL: $url")
                val streams = YouTubeResolver.resolve(url)
                streams.progressive?.url
                    ?: throw IOException("No progressive stream found for YouTube video — DASH not supported for download")
            } else {
                url
            }
        } catch (e: Exception) {
            BridgeLog.e(TAG, "Stream resolution failed", e)
            DownloadCallback.atomicWrite(callbackFile, DownloadCallback.errorJson(e.message ?: "Resolution failed"))
            return@withContext
        }

        outVideoFile.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(directUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Odin2) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DownloadCallback.atomicWrite(callbackFile, DownloadCallback.errorJson("HTTP ${response.code}"))
                return@withContext
            }

            val body = response.body ?: run {
                DownloadCallback.atomicWrite(callbackFile, DownloadCallback.errorJson("Empty response body"))
                return@withContext
            }

            val contentLength = body.contentLength()   // -1 if unknown
            var bytesRead = 0L
            var lastProgressWrite = System.currentTimeMillis()
            val buffer = ByteArray(65_536)

            DownloadCallback.atomicWrite(callbackFile, DownloadCallback.progressJson(0f))

            outVideoFile.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        bytesRead += n

                        val now = System.currentTimeMillis()
                        if (now - lastProgressWrite >= 2_000) {
                            lastProgressWrite = now
                            val progress = if (contentLength > 0) bytesRead.toFloat() / contentLength else 0f
                            DownloadCallback.atomicWrite(callbackFile, DownloadCallback.progressJson(progress))
                            BridgeLog.d(TAG, "Progress: $bytesRead / $contentLength bytes")
                        }
                    }
                }
            }

            if (coroutineContext[kotlinx.coroutines.Job]?.isActive != true) {
                outVideoFile.delete()
                BridgeLog.i(TAG, "Download cancelled, file removed: ${outVideoFile.path}")
                return@withContext
            }

            BridgeLog.i(TAG, "Download complete: ${outVideoFile.path} ($bytesRead bytes)")
            DownloadCallback.atomicWrite(callbackFile, DownloadCallback.okJson(outVideoFile.absolutePath))

        } catch (e: IOException) {
            BridgeLog.e(TAG, "Download IO error", e)
            outVideoFile.delete()
            DownloadCallback.atomicWrite(callbackFile, DownloadCallback.errorJson(e.message ?: "IO error"))
        }
    }
}
