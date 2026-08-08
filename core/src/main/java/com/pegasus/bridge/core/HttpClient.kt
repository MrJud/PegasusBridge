package com.pegasus.bridge.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object HttpClient {

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

// ── Kept in step with shared/core/.../HttpClient.kt ─────────────────────────
    // These three exist for ScreenScraper, whose client is compiled by *both* shells
    // from one copy in `shared/scrapers/src/android-shared`. That copy calls
    // `com.pegasus.bridge.core.HttpClient` — which on Android is this file and on the
    // desktop is the other one. The two are separate classes with one name, so an
    // addition to either has to be made to both or the shared file stops compiling on
    // the shell that was forgotten.
    /**
     * Same connection settings, patient about the body — a picture is not a JSON
     * document and ten seconds is not enough for one on a slow link.
     */
    private val downloadClient: OkHttpClient = okHttp.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * For APIs that are slow rather than broken.
     *
     * Ten seconds is right for a JSON API that answers in one; it is wrong for
     * ScreenScraper, which serves one request per account at a time and can take
     * appreciably longer under load. Measured on the ten-ROM NES folder: nine answered
     * and the tenth timed out, which reads as a defect and is really just impatience.
     * A timeout is recorded as a failure and — correctly — never cached, so the cost is
     * a game that has to be asked for again rather than a wrong answer; but on a
     * library of hundreds that is a steady trickle of work redone for nothing.
     */
    private val patientClient: OkHttpClient = okHttp.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Status and body together, which [get] cannot express. */
    data class RawResponse(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * The response whatever its status, for APIs whose refusals are in the **body**.
     *
     * [get] folds every non-2xx into `HTTP <code>` and throws the body away, which is
     * exactly the wrong shape for ScreenScraper: a 404 there carries the sentence that
     * distinguishes "no such ROM" — a real answer, and cacheable — from "your quota is
     * gone", which must never be cached as one. Losing that text is how a refusal gets
     * recorded as a miss.
     */
    fun getRaw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        patient: Boolean = false
    ): Result<RawResponse> {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        return try {
            (if (patient) patientClient else okHttp).newCall(req).execute().use { resp ->
                Result.success(RawResponse(resp.code, resp.body?.string() ?: ""))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams a URL to a file and answers with the byte count.
     *
     * Streamed rather than buffered because the caller may be fetching a trailer, and
     * written to a temporary sibling first: a half-written picture left at the target
     * path would be indistinguishable from a good one on the next run, and the theme
     * would show a broken image with nothing anywhere saying why.
     */
    fun download(url: String, target: File, headers: Map<String, String> = emptyMap()): Result<Long> {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    tmp.delete()
                    return Result.failure(Exception("HTTP ${resp.code}"))
                }
                val body = resp.body ?: return Result.failure(Exception("empty response"))
                body.byteStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                return Result.failure(Exception("download produced no bytes"))
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.success(target.length())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        return try {
            val resp = okHttp.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful) Result.success(body)
            else Result.failure(Exception("HTTP ${resp.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): Result<String> {
        val reqBody = body.toRequestBody(contentType.toMediaType())
        val req = Request.Builder().url(url).post(reqBody).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        return try {
            val resp = okHttp.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (resp.isSuccessful) Result.success(respBody)
            else Result.failure(Exception("HTTP ${resp.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
