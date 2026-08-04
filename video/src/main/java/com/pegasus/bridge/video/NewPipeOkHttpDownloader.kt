package com.pegasus.bridge.video

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import java.util.concurrent.TimeUnit

/**
 * NewPipe needs its own [Downloader] implementation; we wrap a dedicated OkHttpClient
 * rather than reusing `core/HttpClient` because NewPipe expects specific timeout tuning
 * and we don't want its retries/interceptors to affect the shared client.
 */
class NewPipeOkHttpDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: NpRequest): NpResponse {
        val builder = Request.Builder().url(request.url())
        request.headers().forEach { (k, vs) -> vs.forEach { builder.addHeader(k, it) } }

        when (request.httpMethod().uppercase()) {
            "GET"  -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(
                (request.dataToSend() ?: ByteArray(0))
                    .toRequestBody(null, 0, request.dataToSend()?.size ?: 0)
            )
            else -> error("Unsupported HTTP method: ${request.httpMethod()}")
        }

        client.newCall(builder.build()).execute().use { resp ->
            return NpResponse(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }

    companion object {
        @Volatile private var instance: NewPipeOkHttpDownloader? = null

        fun get(): NewPipeOkHttpDownloader = instance ?: synchronized(this) {
            instance ?: NewPipeOkHttpDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .build()
            ).also { instance = it }
        }
    }
}
