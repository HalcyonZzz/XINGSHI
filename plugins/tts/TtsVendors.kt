package com.xingshi.ai.plugins.tts

import android.util.Base64
import com.xingshi.ai.plugins.api.TtsCloneRequest
import com.xingshi.ai.plugins.api.TtsDesignRequest
import com.xingshi.ai.plugins.api.TtsPreset
import com.xingshi.ai.plugins.api.TtsRequest
import com.xingshi.ai.plugins.api.TtsResult
import com.xingshi.ai.plugins.api.TtsVoiceResult
import java.io.File

private fun dataUrl(mime: String, bytes: ByteArray): String =
    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

 
private fun upstreamTts(text: String, code: Int, hint: String): TtsResult =
    TtsJson.errorMessage(text)?.let { TtsResult.Failure(it) }
        ?: TtsResult.Failure(hint, code = "upstream_http")

private fun upstreamVoice(text: String, code: Int, hint: String): TtsVoiceResult =
    TtsJson.errorMessage(text)?.let { TtsVoiceResult.Failure(it) }
        ?: TtsVoiceResult.Failure(hint, code = "upstream_http")

private fun writePreview(bytes: ByteArray, format: String, dir: File): TtsResult =
    TtsAudio.save(bytes, format, dir, prefix = "preview")

private fun previewOf(result: TtsResult): TtsVoiceResult = when (result) {
    is TtsResult.Success -> TtsVoiceResult.Success(
        voiceId = "",
        previewPath = result.audioPath,
        mimeType = result.mimeType,
        durationMs = result.durationMs
    )
    is TtsResult.Failure -> TtsVoiceResult.Failure(result.reason, result.retryable, result.code)
    TtsResult.Cancelled -> TtsVoiceResult.Failure("操作已取消", code = "cancelled")
}

 
internal object MiMoTts {

    private const val CLONE_MODEL = "mimo-v2.5-tts-voiceclone"
    private const val DESIGN_MODEL = "mimo-v2.5-tts-voicedesign"
    private const val DEFAULT_PROMPT = "请用自然、清晰、适合正式回复的语气朗读下面的内容。"

    suspend fun synthesize(request: TtsRequest, preset: TtsPreset): TtsResult {
        val mode = request.voiceMode.ifBlank { "preset" }
        val body = when (mode) {
            "clone" -> {
                val sample = File(request.voiceSamplePath)
                if (!sample.isFile) return TtsResult.Failure("复刻参考音频已丢失，请重新选择样本", code = "sample_missing")
                val mime = TtsAudio.mimeOf(TtsAudio.sniff(sample.readBytes()) ?: "wav")
                buildBody(
                    model = CLONE_MODEL,
                    prompt = DEFAULT_PROMPT,
                    text = request.text,
                    voice = dataUrl(mime, sample.readBytes())
                )
            }
            "design" -> {
                if (request.voiceDesignPrompt.isBlank()) return TtsResult.Failure("请先保存声音描述", code = "prompt_missing")
                buildBody(
                    model = DESIGN_MODEL,
                    prompt = request.voiceDesignPrompt,
                    text = request.text,
                    voice = ""
                )
            }
            else -> {
                val voice = request.voice.ifBlank { preset.defaultVoice }
                buildBody(
                    model = preset.defaultModel,
                    prompt = DEFAULT_PROMPT,
                    text = request.text,
                    voice = voice
                )
            }
        }
        val response = TtsHttp.postJson(preset.endpoint, request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamTts(response.text, response.code, "语音合成失败（HTTP ${response.code}）")
        }
        val audio = TtsJson.objectAt(response.text, "audio")
        val data = audio?.let { TtsJson.string(it, "data") }.orEmpty()
        if (data.isBlank()) {
            return TtsJson.errorMessage(response.text)?.let { TtsResult.Failure(it) }
                ?: TtsResult.Failure("语音合成返回空音频", code = "empty_audio")
        }
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }
            .getOrElse { return TtsResult.Failure("语音合成音频解码失败", code = "invalid_audio") }
        return TtsAudio.save(bytes, preset.responseFormat, request.outputDir)
    }

    suspend fun clone(request: TtsCloneRequest, preset: TtsPreset, sample: File): TtsVoiceResult {
        val bytes = sample.readBytes()
        val mime = TtsAudio.mimeOf(TtsAudio.sniff(bytes) ?: request.mimeType.ifBlank { "audio/wav" })
        val body = buildBody(
            model = CLONE_MODEL,
            prompt = DEFAULT_PROMPT,
            text = request.displayName.ifBlank { "你好，这是音色复刻试听。" },
            voice = dataUrl(mime, bytes)
        )
        val attempt = runCatching { synthesizePreview(preset.endpoint, request.apiKey, body, request.outputDir, preset.responseFormat) }
            .getOrElse { return TtsVoiceResult.Failure("音频复刻失败：${it.message?.take(80) ?: "网络错误"}", code = "provider_failed") }
        if (attempt is TtsResult.Failure) return TtsVoiceResult.Failure(attempt.reason, attempt.retryable, attempt.code)
        val success = previewOf(attempt) as? TtsVoiceResult.Success
            ?: return TtsVoiceResult.Failure("音频复刻失败", code = "provider_failed")
        
        return success.copy(model = CLONE_MODEL)
    }

    suspend fun design(request: TtsDesignRequest, preset: TtsPreset): TtsVoiceResult {
        val text = request.previewText.ifBlank { "你好，这就是设计出来的声音，请试听确认。" }
        val body = buildBody(model = DESIGN_MODEL, prompt = request.prompt, text = text, voice = "")
        val attempt = runCatching { synthesizePreview(preset.endpoint, request.apiKey, body, request.outputDir, preset.responseFormat) }
            .getOrElse { return TtsVoiceResult.Failure("文字设计失败：${it.message?.take(80) ?: "网络错误"}", code = "provider_failed") }
        val success = previewOf(attempt) as? TtsVoiceResult.Success
            ?: return (attempt as? TtsResult.Failure)?.let { TtsVoiceResult.Failure(it.reason, it.retryable, it.code) }
                ?: TtsVoiceResult.Failure("文字设计失败", code = "design_failed")
        return success.copy(model = DESIGN_MODEL)
    }

    private suspend fun synthesizePreview(
        endpoint: String,
        apiKey: String,
        body: String,
        outputDir: File,
        format: String
    ): TtsResult {
        val response = TtsHttp.postJson(endpoint, apiKey, body)
        if (!response.isSuccessful) {
            return upstreamTts(response.text, response.code, "语音合成失败（HTTP ${response.code}）")
        }
        val audio = TtsJson.objectAt(response.text, "audio")
        val data = audio?.let { TtsJson.string(it, "data") }.orEmpty()
        if (data.isBlank()) {
            return TtsJson.errorMessage(response.text)?.let { TtsResult.Failure(it) }
                ?: TtsResult.Failure("语音合成返回空音频", code = "empty_audio")
        }
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }
            .getOrElse { return TtsResult.Failure("语音合成音频解码失败", code = "invalid_audio") }
        return TtsAudio.save(bytes, format, outputDir, prefix = "preview")
    }

    internal fun buildBody(model: String, prompt: String, text: String, voice: String): String = buildString {
        append('{')
        append("\"model\":\"").append(TtsJson.escape(model)).append("\",")
        append("\"messages\":[")
        append("{\"role\":\"user\",\"content\":\"").append(TtsJson.escape(prompt)).append("\"},")
        append("{\"role\":\"assistant\",\"content\":\"").append(TtsJson.escape(text)).append("\"}")
        append("],")
        append("\"audio\":{\"format\":\"mp3\"")
        if (voice.isNotBlank()) append(",\"voice\":\"").append(TtsJson.escape(voice)).append('"')
        append("},\"stream\":false}")
    }
}

 
internal object MiniMaxTts {

    private const val VOICE_CLONE_MODEL = "speech-02-hd"

    suspend fun synthesize(request: TtsRequest, preset: TtsPreset): TtsResult {
        val voice = when (request.voiceMode.ifBlank { "preset" }) {
            "clone", "design" -> request.voiceId.ifBlank {
                return TtsResult.Failure("请先保存供应商音色（voice_id）", code = "voice_missing")
            }
            else -> request.voice.ifBlank { preset.defaultVoice }
        }
        val body = buildString {
            append("{\"model\":\"").append(TtsJson.escape(preset.defaultModel)).append("\",")
            append("\"text\":\"").append(TtsJson.escape(request.text)).append("\",")
            append("\"stream\":false,")
            append("\"voice_setting\":{\"voice_id\":\"").append(TtsJson.escape(voice)).append("\",\"speed\":1,\"vol\":1,\"pitch\":0},")
            append("\"audio_setting\":{\"sample_rate\":32000,\"bitrate\":128000,\"format\":\"mp3\",\"channel\":1}}")
        }
        val response = TtsHttp.postJson("${preset.endpoint}/v1/t2a_v2", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamTts(response.text, response.code, "语音合成失败（HTTP ${response.code}）")
        }
        val base = TtsJson.objectAt(response.text, "base_resp")
        val status = base?.let { TtsJson.number(it, "status_code") } ?: 0L
        if (status != 0L) {
            val message = base?.let { TtsJson.string(it, "status_msg") }
            
            return if (message != null) {
                TtsResult.Failure(message)
            } else {
                TtsResult.Failure("语音合成失败（status=${status}）", code = "provider_failed")
            }
        }
        val data = TtsJson.objectAt(response.text, "data")
        val hex = data?.let { TtsJson.string(it, "audio") }.orEmpty()
        if (hex.isBlank()) return TtsResult.Failure("语音合成返回空音频", code = "empty_audio")
        val bytes = runCatching { hexToBytes(hex) }.getOrElse { return TtsResult.Failure("语音合成音频解码失败", code = "invalid_audio") }
        return TtsAudio.save(bytes, "mp3", request.outputDir)
    }

    suspend fun clone(request: TtsCloneRequest, preset: TtsPreset, sample: File): TtsVoiceResult {
        val upload = TtsHttp.postMultipart(
            url = "${preset.endpoint}/v1/files/upload",
            apiKey = request.apiKey,
            fields = listOf("purpose" to "voice_clone"),
            fileField = "file",
            fileName = sample.name.ifBlank { "sample.wav" },
            mimeType = request.mimeType.ifBlank { "audio/wav" },
            fileBytes = sample.readBytes()
        )
        if (!upload.isSuccessful) {
            return TtsJson.errorMessage(upload.text)?.let { TtsVoiceResult.Failure(it) }
                ?: TtsVoiceResult.Failure("参考音频上传失败（HTTP ${upload.code}）", code = "upload_failed")
        }
        val fileId = TtsJson.string(upload.text, "file_id")
            ?: return TtsVoiceResult.Failure("参考音频上传失败：缺少 file_id", code = "upload_failed")
        val voiceId = "xingshi" + System.currentTimeMillis().toString()
        val body = "{"
            .plus("\"file_id\":\"${TtsJson.escape(fileId)}\",")
            .plus("\"voice_id\":\"${TtsJson.escape(voiceId)}\",")
            .plus("\"model\":\"${TtsJson.escape(VOICE_CLONE_MODEL)}\"}")
        val response = TtsHttp.postJson("${preset.endpoint}/v1/voice_clone", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamVoice(response.text, response.code, "音色复刻失败（HTTP ${response.code}）")
        }
        checkStatus(response.text)?.let { return it }
        val preview = synthesize(
            TtsRequest(
                text = request.displayName.ifBlank { "你好，这就是复刻出来的声音。" },
                endpoint = preset.endpoint,
                apiKey = request.apiKey,
                model = preset.defaultModel,
                voice = "",
                responseFormat = "mp3",
                outputDir = request.outputDir,
                providerId = preset.id,
                voiceMode = "clone",
                voiceId = voiceId
            ),
            preset
        )
        return when (preview) {
            is TtsResult.Success -> TtsVoiceResult.Success(
                voiceId = voiceId,
                model = preset.defaultModel,
                region = request.region,
                previewPath = preview.audioPath,
                mimeType = preview.mimeType,
                durationMs = preview.durationMs
            )
            is TtsResult.Failure -> TtsVoiceResult.Failure(preview.reason, preview.retryable, preview.code)
            TtsResult.Cancelled -> TtsVoiceResult.Failure("操作已取消", code = "cancelled")
        }
    }

    suspend fun design(request: TtsDesignRequest, preset: TtsPreset): TtsVoiceResult {
        val body = buildString {
            append("{\"prompt\":\"").append(TtsJson.escape(request.prompt)).append("\",")
            append("\"preview_text\":\"").append(TtsJson.escape(request.previewText)).append('"')
            append('}')
        }
        val response = TtsHttp.postJson("${preset.endpoint}/v1/voice_design", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamVoice(response.text, response.code, "文字设计失败（HTTP ${response.code}）")
        }
        checkStatus(response.text)?.let { return it }
        val voiceId = TtsJson.string(response.text, "voice_id")
            ?: return TtsVoiceResult.Failure("文字设计失败：缺少 voice_id", code = "design_failed")
        
        val preview = synthesize(
            TtsRequest(
                text = request.previewText.ifBlank { "你好，这就是设计出来的声音。" },
                endpoint = preset.endpoint,
                apiKey = request.apiKey,
                model = preset.defaultModel,
                voice = "",
                responseFormat = "mp3",
                outputDir = request.outputDir,
                providerId = preset.id,
                voiceMode = "design",
                voiceId = voiceId
            ),
            preset
        )
        return when (preview) {
            is TtsResult.Success -> TtsVoiceResult.Success(
                voiceId = voiceId,
                model = preset.defaultModel,
                region = request.region,
                previewPath = preview.audioPath,
                mimeType = preview.mimeType,
                durationMs = preview.durationMs
            )
            is TtsResult.Failure -> TtsVoiceResult.Failure(preview.reason, preview.retryable, preview.code)
            TtsResult.Cancelled -> TtsVoiceResult.Failure("操作已取消", code = "cancelled")
        }
    }

    private fun checkStatus(json: String): TtsVoiceResult.Failure? {
        val base = TtsJson.objectAt(json, "base_resp") ?: return null
        val code = TtsJson.number(base, "status_code") ?: return null
        if (code == 0L) return null
        val message = TtsJson.string(base, "status_msg")
        return if (message != null) {
            TtsVoiceResult.Failure(message)
        } else {
            TtsVoiceResult.Failure("供应商返回失败（status=$code）", code = "provider_failed")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) throw IllegalArgumentException("长度非法")
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

 
internal object ElevenLabsTts {

    suspend fun synthesize(request: TtsRequest, preset: TtsPreset): TtsResult {
        if (request.voiceMode.ifBlank { "preset" } != "preset" && request.voiceId.isBlank() && request.voice.isBlank()) {
            return TtsResult.Failure("缺少自定义音色 ID，请重新复刻", retryable = false, code = "voice_missing")
        }
        val voiceId = request.voiceId.ifBlank { request.voice.ifBlank { preset.defaultVoice } }
        val body = "{\"text\":\"${TtsJson.escape(request.text)}\",\"model_id\":\"${TtsJson.escape(preset.defaultModel)}\"}"
        val response = TtsHttp.postJson("${preset.endpoint}/v1/text-to-speech/$voiceId", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamTts(response.text, response.code, "语音合成失败（HTTP ${response.code}）")
        }
        return TtsAudio.save(response.bytes, "mp3", request.outputDir)
    }

    suspend fun clone(request: TtsCloneRequest, preset: TtsPreset, sample: File): TtsVoiceResult {
        val upload = TtsHttp.postMultipart(
            url = "${preset.endpoint}/v1/voices/add",
            apiKey = request.apiKey,
            fields = listOf(
                "name" to request.displayName.ifBlank { "xingshi-voice" },
                "description" to "星识语音合成复刻音色"
            ),
            fileField = "files",
            fileName = sample.name.ifBlank { "sample.wav" },
            mimeType = request.mimeType.ifBlank { "audio/wav" },
            fileBytes = sample.readBytes(),
            extraHeaders = mapOf("xi-api-key" to request.apiKey)
        )
        if (!upload.isSuccessful) {
            return TtsJson.errorMessage(upload.text)?.let { TtsVoiceResult.Failure(it) }
                ?: TtsVoiceResult.Failure("音色复刻失败（HTTP ${upload.code}）", code = "clone_failed")
        }
        val voiceId = TtsJson.string(upload.text, "voice_id")
            ?: return TtsVoiceResult.Failure("音色复刻失败：缺少 voice_id", code = "clone_failed")
        val preview = synthesize(
            TtsRequest(
                text = "你好，这就是复刻出来的声音，请试听确认。",
                endpoint = preset.endpoint,
                apiKey = request.apiKey,
                model = preset.defaultModel,
                voice = "",
                responseFormat = "mp3",
                outputDir = request.outputDir,
                providerId = preset.id,
                voiceMode = "clone",
                voiceId = voiceId
            ),
            preset
        )
        return when (preview) {
            is TtsResult.Success -> TtsVoiceResult.Success(
                voiceId = voiceId,
                model = preset.defaultModel,
                region = request.region,
                previewPath = preview.audioPath,
                mimeType = preview.mimeType,
                durationMs = preview.durationMs
            )
            is TtsResult.Failure -> TtsVoiceResult.Failure(preview.reason, preview.retryable, preview.code)
            TtsResult.Cancelled -> TtsVoiceResult.Failure("操作已取消", code = "cancelled")
        }
    }

    suspend fun design(request: TtsDesignRequest, preset: TtsPreset): TtsVoiceResult {
        val body = buildString {
            append("{\"voice_description\":\"").append(TtsJson.escape(request.prompt)).append("\",")
            append("\"text\":\"").append(TtsJson.escape(request.previewText.ifBlank { "你好，这就是设计出来的声音。" })).append("\"}")
        }
        val response = TtsHttp.postJson("${preset.endpoint}/v1/text-to-voice/design", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamVoice(response.text, response.code, "文字设计失败（HTTP ${response.code}）")
        }
        val previews = TtsJson.string(response.text, "generated_voice_id")
            ?: return TtsVoiceResult.Failure("文字设计失败：缺少候选音色", code = "design_failed")
        val audio = TtsJson.string(response.text, "audio_base_64")
        val previewPath = audio
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?.let { bytes -> writePreview(bytes, "mp3", request.outputDir) }
            ?.let { (it as? TtsResult.Success)?.audioPath }
            .orEmpty()
        
        val createBody = buildString {
            append("{\"voice_name\":\"").append(TtsJson.escape("xingshi-" + System.currentTimeMillis())).append("\",")
            append("\"voice_description\":\"").append(TtsJson.escape(request.prompt)).append("\",")
            append("\"generated_voice_id\":\"").append(TtsJson.escape(previews)).append("\"}")
        }
        val created = TtsHttp.postJson("${preset.endpoint}/v1/text-to-voice", request.apiKey, createBody)
        if (!created.isSuccessful) {
            return TtsJson.errorMessage(created.text)?.let { TtsVoiceResult.Failure(it) }
                ?: TtsVoiceResult.Failure("保存设计音色失败（HTTP ${created.code}）", code = "design_failed")
        }
        val voiceId = TtsJson.string(created.text, "voice_id")
            ?: return TtsVoiceResult.Failure("保存设计音色失败：缺少 voice_id", code = "design_failed")
        return TtsVoiceResult.Success(
            voiceId = voiceId,
            model = preset.defaultModel,
            region = request.region,
            previewPath = previewPath,
            mimeType = if (previewPath.isBlank()) "" else "audio/mpeg"
        )
    }
}

 
internal object AliyunTts {

    private const val BASE = "https://dashscope.aliyuncs.com/api/v1"
    private const val MODEL = "cosyvoice-v3-flash"

    private fun prefix(seed: String): String =
        (seed + System.currentTimeMillis().toString().takeLast(4)).take(10)

    suspend fun synthesize(request: TtsRequest, preset: TtsPreset): TtsResult {
        
        if (request.voiceMode.ifBlank { "preset" } != "preset" && request.voiceId.isBlank() && request.voice.isBlank()) {
            return TtsResult.Failure("缺少自定义音色 ID，请重新复刻", retryable = false, code = "voice_missing")
        }
        val voice = request.voiceId.ifBlank { request.voice }.ifBlank { preset.defaultVoice }
        val format = preset.responseFormat.ifBlank { "mp3" }
        val body = buildString {
            append("{\"model\":\"").append(TtsJson.escape(preset.defaultModel.ifBlank { MODEL })).append("\",")
            append("\"input\":{\"text\":\"").append(TtsJson.escape(request.text)).append("\",")
            append("\"voice\":\"").append(TtsJson.escape(voice)).append("\",")
            append("\"format\":\"").append(TtsJson.escape(format)).append("\",")
            append("\"sample_rate\":24000}}")
        }
        val response = TtsHttp.postJson("$BASE/services/audio/tts/SpeechSynthesizer", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamTts(response.text, response.code, "语音合成失败（HTTP ${response.code}）")
        }
        val audio = TtsJson.objectAt(response.text, "audio")
        val url = audio?.let { TtsJson.string(it, "url") }.orEmpty()
        if (url.isNotBlank()) {
            val audioResponse = TtsHttp.get(url, "")
            if (!audioResponse.isSuccessful) return TtsResult.Failure("语音合成音频下载失败（HTTP ${audioResponse.code}）", code = "upstream_http")
            return TtsAudio.save(audioResponse.bytes, format, request.outputDir)
        }
        val data = audio?.let { TtsJson.string(it, "data") }.orEmpty()
        if (data.isBlank()) return TtsResult.Failure("语音合成返回空音频", code = "empty_audio")
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            ?: return TtsResult.Failure("语音合成音频解码失败", code = "invalid_audio")
        return TtsAudio.save(bytes, format, request.outputDir)
    }

    suspend fun clone(request: TtsCloneRequest, preset: TtsPreset, sample: File): TtsVoiceResult {
        val bytes = sample.readBytes()
        val mime = TtsAudio.mimeOf(TtsAudio.sniff(bytes) ?: request.mimeType.ifBlank { "audio/wav" })
        val body = buildString {
            append("{\"model\":\"voice-enrollment\",\"input\":{\"action\":\"create_voice\",")
            append("\"target_model\":\"").append(TtsJson.escape(preset.defaultModel.ifBlank { MODEL })).append("\",")
            append("\"prefix\":\"").append(TtsJson.escape(prefix("xingshi"))).append("\",")
            append("\"audio\":\"").append(TtsJson.escape(dataUrl(mime, bytes))).append("\"}}")
        }
        val response = TtsHttp.postJson("$BASE/services/audio/tts/customization", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamVoice(response.text, response.code, "音色复刻失败（HTTP ${response.code}）")
        }
        val output = TtsJson.objectAt(response.text, "output")
        val voiceId = output?.let { TtsJson.string(it, "voice_id") }
            ?: return TtsVoiceResult.Failure("音色复刻失败：缺少 voice_id", code = "clone_failed")
        return waitVoiceReady(request.apiKey, voiceId)
            ?: TtsVoiceResult.Success(voiceId = voiceId, model = preset.defaultModel.ifBlank { MODEL })
    }

    suspend fun design(request: TtsDesignRequest, preset: TtsPreset): TtsVoiceResult {
        val previewText = request.previewText.ifBlank { "欢迎收听今天的节目，我们一起来聊聊生活中的小确幸。" }
        val body = buildString {
            append("{\"model\":\"voice-enrollment\",\"input\":{\"action\":\"create_voice\",")
            append("\"target_model\":\"").append(TtsJson.escape(preset.defaultModel.ifBlank { MODEL })).append("\",")
            append("\"prefix\":\"").append(TtsJson.escape(prefix("xingshid"))).append("\",")
            append("\"voice_prompt\":\"").append(TtsJson.escape(request.prompt)).append("\",")
            append("\"preview_text\":\"").append(TtsJson.escape(previewText)).append("\",")
            append("\"language_hints\":[\"zh\"]}}")
        }
        val response = TtsHttp.postJson("$BASE/services/audio/tts/customization", request.apiKey, body)
        if (!response.isSuccessful) {
            return upstreamVoice(response.text, response.code, "文字设计失败（HTTP ${response.code}）")
        }
        val output = TtsJson.objectAt(response.text, "output")
        val voiceId = output?.let { TtsJson.string(it, "voice_id") }
            ?: return TtsVoiceResult.Failure("文字设计失败：缺少 voice_id", code = "design_failed")
        val preview = TtsJson.objectAt(response.text, "preview_audio")
        val previewData = preview?.let { TtsJson.string(it, "data") }.orEmpty()
        val previewFormat = preview?.let { TtsJson.string(it, "response_format") }.orEmpty().ifBlank { "wav" }
        var previewPath = ""
        var durationMs = 0L
        if (previewData.isNotBlank()) {
            val bytes = runCatching { Base64.decode(previewData, Base64.DEFAULT) }.getOrNull()
            if (bytes != null) {
                when (val saved = TtsAudio.save(bytes, previewFormat, request.outputDir)) {
                    is TtsResult.Success -> {
                        previewPath = saved.audioPath
                        durationMs = saved.durationMs
                    }
                    else -> Unit
                }
            }
        }
        return TtsVoiceResult.Success(
            voiceId = voiceId,
            model = preset.defaultModel.ifBlank { MODEL },
            previewPath = previewPath,
            mimeType = if (previewPath.isBlank()) "" else TtsAudio.mimeOf(previewFormat),
            durationMs = durationMs
        )
    }

    private suspend fun waitVoiceReady(apiKey: String, voiceId: String): TtsVoiceResult.Failure? {
        repeat(20) { attempt ->
            val body = "{\"model\":\"voice-enrollment\",\"input\":{\"action\":\"query_voice\",\"voice_id\":\"${TtsJson.escape(voiceId)}\"}}"
            val response = TtsHttp.postJson("$BASE/services/audio/tts/customization", apiKey, body)
            if (!response.isSuccessful) {
                return upstreamVoice(response.text, response.code, "音色状态查询失败（HTTP ${response.code}）") as TtsVoiceResult.Failure
            }
            val output = TtsJson.objectAt(response.text, "output")
            val status = output?.let { TtsJson.string(it, "status") }.orEmpty()
            if (status == "OK") return null
            if (status.isNotBlank() && status != "DEPLOYING") {
                return TtsVoiceResult.Failure("音色不可用（$status）", code = "provider_failed")
            }
            if (attempt < 19) kotlinx.coroutines.delay(1_000)
        }
        return TtsVoiceResult.Failure("音色创建超时，请稍后重试", code = "provider_failed")
    }
}
