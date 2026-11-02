package com.xingshi.ai.feature.chat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.TextToSpeechPlugin
import com.xingshi.ai.plugins.api.TtsRequest
import com.xingshi.ai.plugins.api.TtsResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 宿主语音合成入口：正式正文 → 音频文件。
 * 只读独立 TTS 配置，不回填主/视觉模型，不润色正文、不增加对话轮次。
 */
object TextToSpeechService {

    sealed interface Outcome {
        data class Success(val audioPath: String, val durationMs: Long, val mimeType: String) : Outcome
        data class Failed(val reason: String, val code: String = "") : Outcome
        data object NotInstalled : Outcome
        data object NotConfigured : Outcome
    }

    fun isPluginInstalled(): Boolean =
        PluginFramework.manager()?.textToSpeechPlugin() != null

    suspend fun synthesize(context: Context, text: String): Outcome =
        synthesize(context, text, File(context.applicationContext.filesDir, "attachments/tts"))

    internal suspend fun synthesize(context: Context, text: String, outputDir: File): Outcome {
        val manager = PluginFramework.manager()
        val pluginId = manager?.textToSpeechPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.textToSpeechPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        if (text.isBlank()) return Outcome.Failed("没有可合成的正文", code = "text_empty")
        val database = AppDatabase.getDatabase(context)
        val config = ApiConfigRepository(database.apiConfigDao()).getTtsConfig()
            ?: return Outcome.NotConfigured
        val request = requestFrom(config, plugin, text, outputDir)
            ?: return Outcome.NotConfigured
        // 先完成资格登记并取消子任务启动：撤销先到则外部请求不会启动；
        // 门闩必须挂到调用方 Job 下，调用方取消时才能真正结束外部调用。
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) return Outcome.NotInstalled
        val result = coroutineScope {
            val call = async(context = gate, start = CoroutineStart.LAZY) { plugin.synthesize(request) }
            call.start()
            try {
                call.await()
            } catch (e: CancellationException) {
                // 调用方取消必须继续向上传播；只有本服务登记的资格被撤销才降级为不可用。
                currentCoroutineContext().ensureActive()
                null
            } finally {
                manager.untrackOptionalExecution(pluginId, gate)
                // 登记成功后的正常/失败返回也必须结束 gate；只解除登记会让外层 await 永久等待。
                gate.complete()
            }
        } ?: return Outcome.NotInstalled
        return when (result) {
            is TtsResult.Success -> Outcome.Success(result.audioPath, result.durationMs, result.mimeType)
            is TtsResult.Failure -> Outcome.Failed(result.reason, result.code)
            TtsResult.Cancelled -> Outcome.Failed("语音合成已取消", code = "cancelled")
        }
    }

    /** 供应商 → 正式预设：固定协议、域名与模型；不再取「第一个可用预设」兜底。 */
    internal fun presetIdOf(config: ApiConfig): String? = when (config.provider) {
        ApiProvider.XIAOMI -> "xiaomi"
        ApiProvider.MINIMAX -> "minimax"
        ApiProvider.ELEVENLABS -> "elevenlabs"
        ApiProvider.ALIYUN -> "aliyun"
        else -> null
    }

    internal fun requestFrom(
        config: ApiConfig?,
        plugin: TextToSpeechPlugin?,
        text: String,
        outputDir: File
    ): TtsRequest? {
        if (config == null || plugin == null) return null
        if (config.apiKey.isBlank()) return null
        val presetId = presetIdOf(config) ?: return null
        val preset = plugin.ttsPresets.firstOrNull { it.id == presetId } ?: return null
        val mode = config.voiceMode.ifBlank { "preset" }
        val voice = when (mode) {
            "preset" -> config.presetVoiceId.ifBlank { preset.defaultVoice }
            else -> ""
        }
        if (mode == "preset" && voice.isBlank()) return null
        if (mode == "clone" && config.voiceSamplePath.isBlank() && config.voiceId.isBlank()) return null
        if (mode == "design" && config.voiceDesignPrompt.isBlank() && config.voiceId.isBlank()) return null
        return TtsRequest(
            text = text,
            endpoint = preset.endpoint,
            apiKey = config.apiKey,
            model = config.voiceModel.ifBlank { preset.defaultModel },
            voice = voice,
            responseFormat = preset.responseFormat,
            outputDir = outputDir,
            providerId = preset.id,
            voiceMode = mode,
            voiceId = config.voiceId,
            voiceSamplePath = config.voiceSamplePath,
            voiceDesignPrompt = config.voiceDesignPrompt,
            voiceRegion = config.voiceRegion,
            voiceWorkspace = config.voiceWorkspace
        )
    }
}
