package com.pegasus.bridge.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object HttpClient {

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
