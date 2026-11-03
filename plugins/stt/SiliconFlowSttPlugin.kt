package com.xingshi.ai.plugins.stt

import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import com.xingshi.ai.plugins.api.PluginRuntime
import com.xingshi.ai.plugins.api.SpeechPreset
import com.xingshi.ai.plugins.api.SpeechRecognitionPlugin
import com.xingshi.ai.plugins.api.SpeechRecognitionRequest
import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import com.xingshi.ai.plugins.api.SpeechSegmentConfig
import com.xingshi.ai.plugins.api.SpeechSegmentProvider
import com.xingshi.ai.plugins.api.SpeechSegmenter
import com.xingshi.ai.plugins.api.XingShiPlugin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

 
class SiliconFlowSttPlugin : XingShiPlugin, SpeechRecognitionPlugin, SpeechSegmentProvider {

    override val id: String = "stt"
    override val name: String = "语音识别"

    override val speechPresets: List<SpeechPreset> = listOf(
        SpeechPreset(
            id = "siliconflow",
            providerName = "硅基流动 STT",
            endpoint = SttProtocol.DEFAULT_ENDPOINT,
            defaultModel = SttProtocol.DEFAULT_MODEL,
            models = SttProtocol.MODELS
        )
    )

    override suspend fun recognize(request: SpeechRecognitionRequest): SpeechRecognitionResult =
        SttUpload.transcribe(request)

    override fun openSegmenter(config: SpeechSegmentConfig, workDir: java.io.File): SpeechSegmenter? {
        if (!workDir.isDirectory && !workDir.mkdirs()) return null
        return AudioRecordSegmenter(config, workDir)
    }

    override suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport =
        PluginReport.Failure("语音识别插件不通过通用工具循环执行")
}

internal object SttProtocol {

    const val DEFAULT_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
    const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    val MODELS = listOf(DEFAULT_MODEL, "TeleAI/TeleSpeechASR")

    fun mimeTypeFor(path: String, provided: String?): String {
        if (!provided.isNullOrBlank() && provided != "application/octet-stream") return provided
        return when (File(path).extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "amr" -> "audio/amr"
            else -> "application/octet-stream"
        }
    }

    fun fileNameFor(path: String): String {
        val raw = File(path).name.ifBlank { "audio" }
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    fun buildMultipart(
        boundary: String,
        model: String,
        fileName: String,
        mimeType: String,
        audio: ByteArray
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun part(header: String, body: ByteArray) {
            out.write(("--$boundary\r\n$header\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        part("Content-Disposition: form-data; name=\"model\"", model.toByteArray(StandardCharsets.UTF_8))
        part(
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mimeType",
            audio
        )
        out.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    private val TEXT_FIELD = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val MESSAGE_FIELD = Regex("\"(?:message|error)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun extractText(body: String): String? = TEXT_FIELD.find(body)?.groupValues?.get(1)?.let(::unescapeJson)

    fun extractErrorMessage(body: String): String? =
        MESSAGE_FIELD.find(body)?.groupValues?.get(1)?.let(::unescapeJson)

    private fun unescapeJson(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i += 1
                continue
            }
            when (val n = raw[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) out.append(code.toChar())
                    i += 4
                }
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }
}

internal object SttUpload {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000

    suspend fun transcribe(request: SpeechRecognitionRequest): SpeechRecognitionResult {
        val audio = File(request.audioPath)
        if (!audio.isFile || audio.length() == 0L) {
            return SpeechRecognitionResult.Failure("录音文件不存在或为空")
        }
        if (request.apiKey.isBlank()) {
            return SpeechRecognitionResult.Failure("语音识别未配置 API Key")
        }
        val endpoint = request.endpoint.ifBlank { SttProtocol.DEFAULT_ENDPOINT }
        val model = request.model.ifBlank { SttProtocol.DEFAULT_MODEL }
        val mimeType = SttProtocol.mimeTypeFor(request.audioPath, request.mimeType)
        val boundary = "----xingshi-stt-${System.nanoTime()}"
        val payload = try {
            SttProtocol.buildMultipart(
                boundary = boundary,
                model = model,
                fileName = SttProtocol.fileNameFor(request.audioPath),
                mimeType = mimeType,
                audio = audio.readBytes()
            )
        } catch (e: Exception) {
            return SpeechRecognitionResult.Failure("读取录音失败：${e.message?.take(60)}")
        }
        
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val connectionRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
            val worker = Thread {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer ${request.apiKey.trim()}")
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        setRequestProperty("Accept", "application/json")
                    }
                    connectionRef.set(connection)
                    if (!continuation.isActive) {
                        connection.disconnect()
                        return@Thread
                    }
                    connection.outputStream.use { it.write(payload) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val result = if (code !in 200..299) {
                        SpeechRecognitionResult.Failure(
                            SttProtocol.extractErrorMessage(body)?.take(120)
                                ?: "语音识别请求失败（HTTP $code）"
                        )
                    } else {
                        val text = SttProtocol.extractText(body)
                        if (text.isNullOrBlank()) {
                            SpeechRecognitionResult.Failure(
                                SttProtocol.extractErrorMessage(body)?.take(120) ?: "语音识别结果为空"
                            )
                        } else {
                            SpeechRecognitionResult.Success(text.trim())
                        }
                    }
                    if (continuation.isActive) continuation.resume(result, null)
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(
                            SpeechRecognitionResult.Failure(
                                "语音识别失败：${e.message?.take(80) ?: e.javaClass.simpleName}"
                            ),
                            null
                        )
                    }
                } finally {
                    runCatching { connection?.disconnect() }
                }
            }.apply {
                isDaemon = true
                name = "xingshi-stt-upload"
            }
            worker.start()
            continuation.invokeOnCancellation { _: Throwable? ->
                runCatching { connectionRef.get()?.disconnect() }
                worker.interrupt()
            }
        }
    }
}
