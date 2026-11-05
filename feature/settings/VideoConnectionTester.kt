package com.xingshi.ai.feature.settings

import com.xingshi.ai.database.model.ApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface VideoProbeTransport {
    suspend fun post(url: String, apiKey: String, body: String): VideoProbeResponse
}

data class VideoProbeResponse(val status: Int, val body: String)

enum class VideoConnectionOutcome {
    OK,
    NEEDS_KEY,
    NEEDS_URL,
    NEEDS_MODEL,
    NO_NETWORK,
    TIMEOUT,
    AUTH,
    RATE_LIMITED,
    MODEL_REJECTED,
    UPSTREAM,
    CANCELLED,
    FAILED
}

fun videoConnectionTextKey(outcome: VideoConnectionOutcome): String = when (outcome) {
    VideoConnectionOutcome.OK -> "ui_video_config_test_ok"
    VideoConnectionOutcome.NEEDS_KEY -> "ui_video_config_test_needs_key"
    VideoConnectionOutcome.NEEDS_URL -> "ui_video_config_test_needs_url"
    VideoConnectionOutcome.NEEDS_MODEL -> "ui_video_config_test_needs_model"
    VideoConnectionOutcome.NO_NETWORK -> "ui_video_config_test_no_network"
    VideoConnectionOutcome.TIMEOUT -> "ui_video_config_test_timeout"
    VideoConnectionOutcome.AUTH -> "ui_video_config_test_auth_failed"
    VideoConnectionOutcome.RATE_LIMITED -> "ui_video_config_test_rate_limited"
    VideoConnectionOutcome.MODEL_REJECTED -> "ui_video_config_test_model_rejected"
    VideoConnectionOutcome.UPSTREAM -> "ui_video_config_test_upstream_failed"
    VideoConnectionOutcome.CANCELLED -> "ui_video_config_test_cancelled"
    VideoConnectionOutcome.FAILED -> "ui_video_config_test_failed"
}

fun videoChatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

fun videoProbeBody(model: String): String {
    val escaped = model.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"model":"$escaped","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"temperature":0,"stream":false}"""
}

class VideoConnectionTester(
    private val transport: VideoProbeTransport = OkHttpVideoProbeTransport()
) {
    suspend fun test(config: ApiConfig): VideoConnectionOutcome {
        if (config.apiKey.isBlank()) return VideoConnectionOutcome.NEEDS_KEY
        if (config.baseUrl.isBlank()) return VideoConnectionOutcome.NEEDS_URL
        if (config.model.isBlank()) return VideoConnectionOutcome.NEEDS_MODEL
        val url = videoChatCompletionsUrl(config.baseUrl)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return VideoConnectionOutcome.NEEDS_URL
        val response = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                transport.post(url, config.apiKey, videoProbeBody(config.model))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failureOutcome(failure)
        } ?: return VideoConnectionOutcome.TIMEOUT
        return classify(response)
    }

    private fun classify(response: VideoProbeResponse): VideoConnectionOutcome = when {
        response.status in 200..299 ->
            if (response.body.contains("\"choices\"")) VideoConnectionOutcome.OK else VideoConnectionOutcome.FAILED
        response.status == 401 || response.status == 403 -> VideoConnectionOutcome.AUTH
        response.status == 404 -> VideoConnectionOutcome.MODEL_REJECTED
        response.status == 400 || response.status == 422 -> VideoConnectionOutcome.MODEL_REJECTED
        response.status == 429 -> VideoConnectionOutcome.RATE_LIMITED
        response.status in 500..599 -> VideoConnectionOutcome.UPSTREAM
        else -> VideoConnectionOutcome.FAILED
    }

    private fun failureOutcome(failure: Exception): VideoConnectionOutcome = when (failure) {
        is SocketTimeoutException -> VideoConnectionOutcome.TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> VideoConnectionOutcome.NO_NETWORK
        is IOException -> VideoConnectionOutcome.NO_NETWORK
        else -> VideoConnectionOutcome.FAILED
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 30_000L
    }
}

class OkHttpVideoProbeTransport : VideoProbeTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    override suspend fun post(url: String, apiKey: String, body: String): VideoProbeResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) {
                            continuation.resume(VideoProbeResponse(it.code, it.body?.string().orEmpty()))
                        }
                    }
                }
            })
        }
}
