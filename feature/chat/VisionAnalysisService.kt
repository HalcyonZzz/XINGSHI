package com.xingshi.ai.feature.chat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.VisionAnalysisPlugin
import com.xingshi.ai.plugins.api.VisionAnalysisRequest
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 视频通话按轮读图入口：使用独立视觉配置把本轮画面交给视觉插件，
 * 不启动自动对话交接，也不回退聊天主模型。
 */
object VisionAnalysisService {

    sealed interface Outcome {
        data class Described(val text: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data object NotInstalled : Outcome
        data object NotConfigured : Outcome
    }

    fun isPluginInstalled(): Boolean =
        PluginFramework.manager()?.visionAnalysisPlugin() != null

    suspend fun analyze(context: Context, imagePath: String, prompt: String): Outcome {
        val manager = PluginFramework.manager()
        val pluginId = manager?.visionAnalysisPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.visionAnalysisPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        val database = AppDatabase.getDatabase(context)
        val repository = ApiConfigRepository(database.apiConfigDao())
        // 独立 VISUAL 配置；没有配置就明确失败，不借用聊天主模型配置
        val config = repository.getVisionConfig() ?: return Outcome.NotConfigured
        val request = requestFrom(config, imagePath, prompt) ?: return Outcome.NotConfigured
        // 先完成资格登记并取消子任务启动：撤销先到则外部请求不会启动；
        // 门闩必须挂到调用方 Job 下，调用方取消时才能真正结束外部调用。
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) return Outcome.NotInstalled
        val result = coroutineScope {
            val call = async(context = gate, start = CoroutineStart.LAZY) { plugin.analyze(request) }
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
            is VisionAnalysisResult.Described -> Outcome.Described(result.description)
            is VisionAnalysisResult.Failure -> Outcome.Failed(result.reason)
            VisionAnalysisResult.Cancelled -> Outcome.Failed("视觉识别已取消")
        }
    }

    internal fun requestFrom(config: ApiConfig?, imagePath: String, prompt: String): VisionAnalysisRequest? {
        if (config == null || imagePath.isBlank()) return null
        if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) return null
        return VisionAnalysisRequest(
            imagePath = imagePath,
            prompt = prompt,
            endpoint = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model
        )
    }

    const val FRAME_PROMPT: String =
        "只描述这张画面中实际可见的人物、物体、位置、状态和可读文字；" +
            "模糊、遮挡或无法判断的部分要保留不确定性，不猜身份、不推测画面外的事情。"
}
