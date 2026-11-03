package com.xingshi.ai.plugins.samplevision

import com.xingshi.ai.plugins.api.VisionAnalysisPlugin
import com.xingshi.ai.plugins.api.VisionAnalysisRequest
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

 
internal object VisionAnalysisClient {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    suspend fun analyze(request: VisionAnalysisRequest, readTimeoutMs: Int = READ_TIMEOUT_MS): VisionAnalysisResult {
        if (request.endpoint.isBlank()) return VisionAnalysisResult.Failure("视觉接口地址为空", false)
        val endpoint = chatEndpoint(request.endpoint)
        if (request.apiKey.isBlank()) return VisionAnalysisResult.Failure("视觉 API Key 为空", false)
        if (request.model.isBlank()) return VisionAnalysisResult.Failure("视觉模型为空", false)
        val file = File(request.imagePath)
        if (!file.isFile || file.length() == 0L) return VisionAnalysisResult.Failure("本轮图片不可读")
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val base64 = Base64.getEncoder().encodeToString(file.readBytes())
        val payload = buildString {
            append("{\"model\":\"").append(escape(request.model)).append("\",")
            append("\"messages\":[{\"role\":\"user\",\"content\":[")
            append("{\"type\":\"text\",\"text\":\"").append(escape(request.prompt)).append("\"},")
            append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
                .append(mime).append(";base64,").append(base64).append("\"}}]}]}")
        }
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var bodyStream: java.io.InputStream? = null
            var cancelled = false
            try {
                val active = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${request.apiKey.trim()}")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection = active
                val code = abortsOnCancel(active) {
                    active.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                    active.responseCode
                }
                val stream = if (code in 200..299) active.inputStream else active.errorStream
                bodyStream = stream
                val body = stream?.let {
                    readBodyCancellable(it) { closeQuietly(active, it) }
                        .toString(StandardCharsets.UTF_8)
                }.orEmpty()
                if (code !in 200..299) {
                    VisionAnalysisResult.Failure(errorMessage(body) ?: "视觉识别失败（HTTP $code）")
                } else {
                    val content = contentOf(body)?.trim()
                    if (content.isNullOrBlank()) {
                        VisionAnalysisResult.Failure("视觉返回空描述")
                    } else {
                        VisionAnalysisResult.Described(content)
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                VisionAnalysisResult.Failure("视觉识别失败：视觉读取超时")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                VisionAnalysisResult.Failure("视觉识别失败：${e.message?.take(80) ?: e.javaClass.simpleName}")
            } finally {
                if (!cancelled) {
                    runCatching { bodyStream?.close() }
                    runCatching { connection?.disconnect() }
                }
            }
        }
    }

     
    internal suspend fun readBodyCancellable(
        stream: java.io.InputStream,
        abort: () -> Unit
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val reader = Thread({
            try {
                continuation.resumeWith(Result.success(stream.use { it.readBytes() }))
            } catch (t: Throwable) {
                continuation.resumeWith(Result.failure(t))
            }
        }, "vision-body-read").apply { isDaemon = true }
        continuation.invokeOnCancellation {
            reader.interrupt()
            Thread({ runCatching { abort() } }, "vision-body-abort").apply { isDaemon = true }.start()
        }
        reader.start()
    }

    private fun closeQuietly(connection: HttpURLConnection, stream: java.io.InputStream) {
        runCatching { connection.disconnect() }
        runCatching { stream.close() }
    }

    private suspend fun <T> abortsOnCancel(connection: HttpURLConnection, block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching { connection.disconnect() }
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            guard.cancel()
        }
    }

     
    internal fun chatEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        return "$base/chat/completions"
    }

    internal fun contentOf(response: String): String? {
        val match = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(response) ?: return null
        return unescape(match.groupValues[1])
    }

    internal fun errorMessage(response: String): String? {
        val match = Regex("\"(?:message|error)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(response) ?: return null
        return unescape(match.groupValues[1])
    }

    internal fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    internal fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c); i++; continue
            }
            when (val next = value[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 6 <= value.length) {
                        out.append(value.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                }
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }
}

class SampleVisionAnalysis : VisionAnalysisPlugin {
    override suspend fun analyze(request: VisionAnalysisRequest): VisionAnalysisResult =
        VisionAnalysisClient.analyze(request)
}
