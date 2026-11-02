package com.xingshi.ai.plugins.samplevision

import com.xingshi.ai.plugins.api.VisionAnalysisPlugin
import com.xingshi.ai.plugins.api.VisionAnalysisRequest
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 真实按帧读图：把本轮图片本体以 data URL 提交到视觉接口，返回结构化画面描述。
 * 不依赖固定示例结果、不拿文件名代替识别；不承担自动对话交接。
 */
internal object VisionAnalysisClient {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    suspend fun analyze(request: VisionAnalysisRequest): VisionAnalysisResult {
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
        val base64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        val payload = buildString {
            append("{\"model\":\"").append(escape(request.model)).append("\",")
            append("\"messages\":[{\"role\":\"user\",\"content\":[")
            append("{\"type\":\"text\",\"text\":\"").append(escape(request.prompt)).append("\"},")
            append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
                .append(mime).append(";base64,").append(base64).append("\"}}]}]}")
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${request.apiKey.trim()}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
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
        } catch (e: Exception) {
            VisionAnalysisResult.Failure("视觉识别失败：${e.message?.take(80) ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** 基础地址与完整识图路径的约定：已含 chat/completions 不重复拼接，否则补全一次。 */
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
