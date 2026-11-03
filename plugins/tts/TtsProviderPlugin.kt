package com.xingshi.ai.plugins.tts

import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import com.xingshi.ai.plugins.api.PluginRuntime
import com.xingshi.ai.plugins.api.TextToSpeechPlugin
import com.xingshi.ai.plugins.api.TtsCloneRequest
import com.xingshi.ai.plugins.api.TtsDesignRequest
import com.xingshi.ai.plugins.api.TtsPreset
import com.xingshi.ai.plugins.api.TtsRequest
import com.xingshi.ai.plugins.api.TtsResult
import com.xingshi.ai.plugins.api.TtsVoice
import com.xingshi.ai.plugins.api.TtsVoiceResult
import com.xingshi.ai.plugins.api.XingShiPlugin
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

 
class TtsProviderPlugin : XingShiPlugin, TextToSpeechPlugin {

    override val id: String = "tts"
    override val name: String = "语音合成"

    override val ttsPresets: List<TtsPreset> = TtsPresets.ALL

    override val ttsVoices: List<TtsVoice> = TtsPresets.VOICES

    override suspend fun synthesize(request: TtsRequest): TtsResult =
        TtsEngine.synthesize(request)

    override suspend fun cloneVoice(request: TtsCloneRequest): TtsVoiceResult =
        TtsEngine.clone(request)

    override suspend fun designVoice(request: TtsDesignRequest): TtsVoiceResult =
        TtsEngine.design(request)

    override suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport =
        PluginReport.Failure("语音合成插件不通过通用工具循环执行")
}

 
internal object TtsPresets {

    const val XIAOMI = "xiaomi"
    const val MINIMAX = "minimax"
    const val ELEVENLABS = "elevenlabs"
    const val ALIYUN = "aliyun"

    val ALL: List<TtsPreset> = listOf(
        TtsPreset(
            id = XIAOMI,
            providerName = "小米 MiMo",
            endpoint = "https://api.xiaomimimo.com/v1/chat/completions",
            defaultModel = "mimo-v2.5-tts",
            defaultVoice = "mimo_default",
            responseFormat = "mp3",
            verified = true,
            supportsClone = true,
            supportsDesign = true,
            sampleFormats = listOf("mp3", "wav", "m4a"),
            sampleMaxBytes = 10L * 1024 * 1024,
            sampleMinDurationMs = 3_000L,
            sampleMaxDurationMs = 120_000L
        ),
        TtsPreset(
            id = MINIMAX,
            providerName = "MiniMax",
            endpoint = "https://api.minimax.io",
            defaultModel = "speech-02-hd",
            defaultVoice = "male-qn-qingse",
            responseFormat = "mp3",
            verified = false,
            supportsClone = true,
            supportsDesign = true,
            sampleFormats = listOf("mp3", "wav", "m4a"),
            sampleMaxBytes = 20L * 1024 * 1024,
            sampleMinDurationMs = 10_000L,
            sampleMaxDurationMs = 300_000L
        ),
        TtsPreset(
            id = ELEVENLABS,
            providerName = "ElevenLabs",
            endpoint = "https://api.elevenlabs.io",
            defaultModel = "eleven_multilingual_v2",
            defaultVoice = "21m00Tcm4TlvDq8ikWAM",
            responseFormat = "mp3",
            verified = false,
            supportsClone = true,
            supportsDesign = true,
            sampleFormats = listOf("mp3", "wav", "m4a"),
            sampleMaxBytes = 10L * 1024 * 1024,
            sampleMinDurationMs = 30_000L,
            sampleMaxDurationMs = 180_000L
        ),
        TtsPreset(
            id = ALIYUN,
            providerName = "阿里百炼",
            endpoint = "https://dashscope.aliyuncs.com",
            defaultModel = "cosyvoice-v3-flash",
            defaultVoice = "longanyang",
            responseFormat = "mp3",
            verified = true,
            supportsClone = true,
            supportsDesign = true,
            sampleFormats = listOf("wav", "mp3", "m4a"),
            sampleMaxBytes = 10L * 1024 * 1024,
            sampleMinDurationMs = 10_000L,
            sampleMaxDurationMs = 20_000L
        )
    )

    val VOICES: List<TtsVoice> = listOf(
        
        voice(XIAOMI, "mimo_default", "默认"),
        voice(XIAOMI, "冰糖", "冰糖"),
        voice(XIAOMI, "茉莉", "茉莉"),
        voice(XIAOMI, "苏打", "苏打"),
        voice(XIAOMI, "白桦", "白桦"),
        voice(XIAOMI, "Mia", "Mia"),
        voice(XIAOMI, "Chloe", "Chloe"),
        voice(XIAOMI, "Milo", "Milo"),
        voice(XIAOMI, "Dean", "Dean"),
        
        voice(MINIMAX, "male-qn-qingse", "青涩青年"),
        voice(MINIMAX, "female-shaonv", "少女"),
        voice(MINIMAX, "presenter_male", "男声主持"),
        voice(MINIMAX, "presenter_female", "女声主持"),
        voice(MINIMAX, "audiobook_male_1", "有声书男声"),
        voice(MINIMAX, "audiobook_female_1", "有声书女声"),
        
        voice(ELEVENLABS, "21m00Tcm4TlvDq8ikWAM", "Rachel"),
        voice(ELEVENLABS, "AZnzlk1XvdvUeBnXmlld", "Domi"),
        voice(ELEVENLABS, "EXAVITQu4vr4xnSDxMaL", "Bella"),
        voice(ELEVENLABS, "ErXwobaYiN019PkySvjV", "Antoni"),
        voice(ELEVENLABS, "TxGEqnHWrfWFTfGW9XjX", "Josh"),
        voice(ELEVENLABS, "pNInz6obpgDQGcFmaJgB", "Adam"),
        
        voice(ALIYUN, "longanyang", "龙安洋"),
        voice(ALIYUN, "longanhuan", "龙安欢"),
        voice(ALIYUN, "longhuhu_v3", "龙呼呼"),
        voice(ALIYUN, "longjiaxin_v3", "龙嘉欣"),
        voice(ALIYUN, "loongdavid_v3", "David"),
        voice(ALIYUN, "loongcindy_v3", "Cindy")
    )

    private fun voice(providerId: String, id: String, label: String) = TtsVoice(id, label, providerId)

    fun find(providerId: String): TtsPreset? = ALL.firstOrNull { it.id == providerId }

    fun voicesOf(providerId: String): List<TtsVoice> = VOICES.filter { it.providerId == providerId }
}

internal object TtsJson {

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.toString()
    }

    fun string(json: String, field: String): String? {
        
        val key = "\"" + field + "\""
        var from = 0
        while (true) {
            val start = json.indexOf(key, from)
            if (start < 0) return null
            var index = start + key.length
            while (index < json.length && json[index].isWhitespace()) index += 1
            if (index < json.length && json[index] == ':') {
                index += 1
                while (index < json.length && json[index].isWhitespace()) index += 1
                if (index < json.length && json[index] == '"') {
                    val raw = StringBuilder()
                    var cursor = index + 1
                    var escaped = false
                    while (cursor < json.length) {
                        val c = json[cursor]
                        when {
                            escaped -> {
                                raw.append('\\').append(c)
                                escaped = false
                            }
                            c == '\\' -> escaped = true
                            c == '"' -> return unescape(raw.toString())
                            else -> raw.append(c)
                        }
                        cursor += 1
                    }
                    return null
                }
            }
            from = start + key.length
        }
    }

    fun number(json: String, field: String): Long? =
        Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

     
    fun objectAt(json: String, field: String): String? {
        val start = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\\{").find(json)?.range?.last ?: return null
        var depth = 1
        var index = start
        var inString = false
        var escaped = false
        while (index < json.length) {
            val c = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return json.substring(start, index + 1)
                    }
                }
            }
            index += 1
        }
        return null
    }

    fun errorMessage(json: String): String? =
        string(json, "message") ?: string(json, "error") ?: string(json, "detail")

    private fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                append(c)
                i += 1
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                'u' -> {
                    val hex = value.substring(i + 2, minOf(i + 6, value.length))
                    append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    i += 4
                }
                else -> append(next)
            }
            i += 2
        }
    }
}

 
internal object TtsHttp {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000

    class Response(val code: Int, val bytes: ByteArray, val contentType: String?) {
        val text: String get() = String(bytes, StandardCharsets.UTF_8)
        val isSuccessful: Boolean get() = code in 200..299
    }

    suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response = withContext(Dispatchers.IO) {
        val connection = open(url, apiKey, "POST", "application/json; charset=utf-8", extraHeaders)
        try {
            abortable(connection) {
                currentCoroutineContext().ensureActive()
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                read(connection)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    suspend fun postMultipart(
        url: String,
        apiKey: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response = withContext(Dispatchers.IO) {
        val boundary = "----xingshi${UUID.randomUUID().toString().replace("-", "")}"
        val connection = open(
            url,
            apiKey,
            "POST",
            "multipart/form-data; boundary=$boundary",
            extraHeaders
        )
        try {
            abortable(connection) {
            currentCoroutineContext().ensureActive()
            connection.outputStream.use { output ->
                fields.forEach { (name, value) ->
                    writeUtf8(output, "--$boundary\r\n")
                    writeUtf8(output, "Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writeUtf8(output, value)
                    writeUtf8(output, "\r\n")
                }
                writeUtf8(output, "--$boundary\r\n")
                writeUtf8(
                    output,
                    "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n"
                )
                writeUtf8(output, "Content-Type: $mimeType\r\n\r\n")
                output.write(fileBytes)
                writeUtf8(output, "\r\n--$boundary--\r\n")
            }
            read(connection)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    suspend fun get(url: String, apiKey: String): Response = withContext(Dispatchers.IO) {
        val connection = open(url, apiKey, "GET", null, emptyMap())
        try {
            abortable(connection) { read(connection) }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

     
    private suspend fun <T> abortable(connection: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        try {
            block()
        } finally {
            guard.cancel()
        }
    }

    private fun writeUtf8(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun open(
        url: String,
        apiKey: String,
        method: String,
        contentType: String?,
        extraHeaders: Map<String, String>
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = method != "GET"
        connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        contentType?.let { connection.setRequestProperty("Content-Type", it) }
        extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun read(connection: HttpURLConnection): Response {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        
        val body = if (code in 200..299) bytes else bytes.copyOf(minOf(bytes.size, 64 * 1024))
        return Response(code, body, connection.contentType)
    }
}

internal object TtsAudio {

    fun looksLikeAudio(bytes: ByteArray, contentType: String?): Boolean {
        if (bytes.size < 4) return false
        val type = contentType?.lowercase().orEmpty()
        if (type.contains("json") || type.contains("html") || type.contains("text/")) return false
        if (type.startsWith("audio/")) return true
        return sniff(bytes) != null
    }

     
    fun sniff(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val head = String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1)
        if (head.startsWith("ID3")) return "mp3"
        if (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (head == "RIFF" && bytes.size >= 12 && String(bytes.copyOfRange(8, 12), Charsets.ISO_8859_1) == "WAVE") return "wav"
        if (head == "OggS") return "opus"
        
        if (bytes.size >= 12 && String(bytes.copyOfRange(4, 8), Charsets.ISO_8859_1) == "ftyp") return "m4a"
        return null
    }

    fun mimeOf(format: String): String = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/ogg"
        else -> "audio/$format"
    }

     
    fun save(bytes: ByteArray, requestedFormat: String, dir: File, prefix: String = "tts"): TtsResult {
        if (bytes.isEmpty()) return TtsResult.Failure("语音合成返回空音频", code = "empty_audio")
        val detected = sniff(bytes) ?: return TtsResult.Failure("语音合成响应不是有效音频", code = "invalid_audio")
        dir.mkdirs()
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.$detected")
        file.outputStream().use { it.write(bytes) }
        return TtsResult.Success(
            audioPath = file.absolutePath,
            mimeType = mimeOf(detected),
            durationMs = durationMs(bytes, detected)
        )
    }

    fun durationMs(bytes: ByteArray, format: String): Long = when (format) {
        "mp3" -> Mp3Duration.parse(bytes) ?: 0L
        "wav" -> WavDuration.parse(bytes) ?: 0L
        else -> 0L
    }
}

 
internal object WavDuration {

    fun parse(bytes: ByteArray): Long? {
        if (bytes.size < 44) return null
        if (String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1) != "RIFF") return null
        var offset = 12
        var byteRate = 0L
        var dataSize = 0L
        while (offset + 8 <= bytes.size) {
            val id = String(bytes.copyOfRange(offset, offset + 4), Charsets.ISO_8859_1)
            val size = leInt(bytes, offset + 4)
            if (size < 0) return null
            when (id) {
                "fmt " -> if (offset + 16 <= bytes.size) byteRate = leInt(bytes, offset + 16).toLong() and 0xFFFFFFFFL
                "data" -> {
                    dataSize = size.toLong() and 0xFFFFFFFFL
                    break
                }
            }
            offset += 8 + size + (size and 1)
        }
        if (byteRate <= 0L || dataSize <= 0L) return null
        return dataSize * 1000L / byteRate
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

internal object TtsEngine {

    suspend fun synthesize(request: TtsRequest): TtsResult {
        if (request.text.isBlank()) return TtsResult.Failure("没有可合成的正文", code = "text_empty")
        if (request.apiKey.isBlank()) return TtsResult.Failure("语音合成未配置 API Key", code = "api_key_missing")
        val preset = TtsPresets.find(request.providerId)
            ?: return TtsResult.Failure("语音合成供应商不在正式预设内，请重新选择", retryable = false, code = "preset_invalid")
        return try {
            when (preset.id) {
                TtsPresets.XIAOMI -> MiMoTts.synthesize(request, preset)
                TtsPresets.MINIMAX -> MiniMaxTts.synthesize(request, preset)
                TtsPresets.ELEVENLABS -> ElevenLabsTts.synthesize(request, preset)
                TtsPresets.ALIYUN -> AliyunTts.synthesize(request, preset)
                else -> TtsResult.Failure("不支持的语音合成供应商", retryable = false, code = "preset_invalid")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TtsResult.Failure("语音合成失败：${e.message?.take(80) ?: e.javaClass.simpleName}", code = "provider_failed")
        }
    }

    suspend fun clone(request: TtsCloneRequest): TtsVoiceResult {
        if (request.apiKey.isBlank()) {
            return TtsVoiceResult.Failure("语音合成未配置 API Key", code = "api_key_missing")
        }
        val sample = File(request.samplePath)
        if (!sample.isFile || sample.length() == 0L) {
            return TtsVoiceResult.Failure("参考音频不可读", code = "sample_missing")
        }
        val preset = TtsPresets.find(request.providerId ?: "")
            ?: return TtsVoiceResult.Failure("供应商不在正式预设内", retryable = false, code = "preset_invalid")
        if (!preset.supportsClone) {
            return TtsVoiceResult.Failure("当前供应商不支持音频复刻", retryable = false, code = "clone_unsupported")
        }
        val bytes = sample.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > preset.sampleMaxBytes) {
                    return TtsVoiceResult.Failure("参考音频超过供应商大小限制", retryable = false, code = "sample_size")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val sniffed = TtsAudio.sniff(bytes)
            ?: return TtsVoiceResult.Failure("参考音频格式无法识别", retryable = false, code = "sample_format")
        if (sniffed !in preset.sampleFormats) {
            return TtsVoiceResult.Failure("参考音频格式不在该供应商支持范围", retryable = false, code = "sample_format")
        }
        return try {
            when (preset.id) {
                TtsPresets.XIAOMI -> MiMoTts.clone(request, preset, sample)
                TtsPresets.MINIMAX -> MiniMaxTts.clone(request, preset, sample)
                TtsPresets.ELEVENLABS -> ElevenLabsTts.clone(request, preset, sample)
                TtsPresets.ALIYUN -> AliyunTts.clone(request, preset, sample)
                else -> TtsVoiceResult.Failure("不支持的音频复刻供应商", retryable = false, code = "clone_unsupported")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TtsVoiceResult.Failure("音频复刻失败：${e.message?.take(80) ?: e.javaClass.simpleName}", code = "provider_failed")
        }
    }


    suspend fun design(request: TtsDesignRequest): TtsVoiceResult {
        if (request.apiKey.isBlank()) {
            return TtsVoiceResult.Failure("语音合成未配置 API Key", code = "api_key_missing")
        }
        if (request.prompt.isBlank()) {
            return TtsVoiceResult.Failure("请先填写声音描述", code = "prompt_missing")
        }
        val preset = TtsPresets.find(request.providerId ?: "")
            ?: return TtsVoiceResult.Failure("供应商不在正式预设内", retryable = false, code = "preset_invalid")
        if (!preset.supportsDesign) {
            return TtsVoiceResult.Failure("当前供应商不支持文字设计", retryable = false, code = "design_unsupported")
        }
        return try {
            when (preset.id) {
                TtsPresets.XIAOMI -> MiMoTts.design(request, preset)
                TtsPresets.MINIMAX -> MiniMaxTts.design(request, preset)
                TtsPresets.ELEVENLABS -> ElevenLabsTts.design(request, preset)
                TtsPresets.ALIYUN -> AliyunTts.design(request, preset)
                else -> TtsVoiceResult.Failure("不支持的文字设计供应商", retryable = false, code = "design_unsupported")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TtsVoiceResult.Failure("文字设计失败：${e.message?.take(80) ?: e.javaClass.simpleName}", code = "provider_failed")
        }
    }
}
