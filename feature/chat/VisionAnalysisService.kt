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
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

 
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
        
        val config = repository.getVisionConfig() ?: return Outcome.NotConfigured
        val request = requestFrom(config, imagePath, prompt) ?: return Outcome.NotConfigured
        
        
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) return Outcome.NotInstalled
        val result = awaitGated(plugin, request, gate) { manager.untrackOptionalExecution(pluginId, gate) }
            ?: return Outcome.NotInstalled
        return when (result) {
            is VisionAnalysisResult.Described -> Outcome.Described(result.description)
            is VisionAnalysisResult.Failure -> Outcome.Failed(result.reason)
            VisionAnalysisResult.Cancelled -> Outcome.Failed("视觉识别已取消")
        }
    }

    internal suspend fun awaitGated(
        plugin: VisionAnalysisPlugin,
        request: VisionAnalysisRequest,
        gate: CompletableJob,
        onFinished: () -> Unit
    ): VisionAnalysisResult? = coroutineScope {
        val call = async(context = gate, start = CoroutineStart.LAZY) { plugin.analyze(request) }
        call.start()
        try {
            call.await()
        } catch (e: CancellationException) {
            
            currentCoroutineContext().ensureActive()
            null
        } finally {
            onFinished()
            
            gate.complete()
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
