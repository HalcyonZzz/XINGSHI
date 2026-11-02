package com.xingshi.ai.feature.chat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.SpeechRecognitionRequest
import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 宿主侧语音识别入口：只负责取「已安装插件 + 独立 STT 配置」并调用可选能力。
 * 不读主模型 / 视觉配置，不写聊天库，不构造对话提示词。
 */
object SpeechRecognitionService {

    sealed interface Outcome {
        data class Recognized(val text: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data object NotInstalled : Outcome
        data object NotConfigured : Outcome
    }

    fun isPluginInstalled(): Boolean =
        com.xingshi.ai.plugins.PluginFramework.manager()?.speechRecognitionPlugin() != null

    suspend fun recognize(
        context: Context,
        audioPath: String,
        mimeType: String,
        durationMs: Long
    ): Outcome {
        val manager = PluginFramework.manager()
        val pluginId = manager?.speechRecognitionPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.speechRecognitionPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        val database = AppDatabase.getDatabase(context)
        val config = ApiConfigRepository(database.apiConfigDao()).getSttConfig()
            ?: return Outcome.NotConfigured
        val request = requestFrom(config, audioPath, mimeType, durationMs)
            ?: return Outcome.NotConfigured
        // 先完成资格登记并取消子任务启动：撤销先到则外部请求不会启动；
        // 门闩必须挂到调用方 Job 下，调用方取消时才能真正结束外部调用。
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) return Outcome.NotInstalled
        val result = coroutineScope {
            val call = async(context = gate, start = CoroutineStart.LAZY) { plugin.recognize(request) }
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
            is SpeechRecognitionResult.Success -> Outcome.Recognized(result.text.trim())
            is SpeechRecognitionResult.Failure -> Outcome.Failed(result.reason)
            SpeechRecognitionResult.Cancelled -> Outcome.Failed("语音识别已取消")
        }
    }

    /** STT 配置映射：端点、Key、模型都取独立语音配置，不回退主模型或视觉配置。 */
    internal fun requestFrom(
        config: ApiConfig?,
        audioPath: String,
        mimeType: String,
        durationMs: Long
    ): SpeechRecognitionRequest? {
        if (config == null) return null
        if (config.apiKey.isBlank()) return null
        if (config.baseUrl.isBlank()) return null
        if (config.model.isBlank()) return null
        if (audioPath.isBlank()) return null
        return SpeechRecognitionRequest(
            audioPath = audioPath,
            mimeType = mimeType,
            endpoint = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            audioDurationMs = durationMs
        )
    }
}
