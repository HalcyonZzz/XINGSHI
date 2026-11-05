package com.xingshi.ai.feature.chat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.OptionalCapabilityCall
import com.xingshi.ai.plugins.OptionalCapabilityCall.Outcome.Completed
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.VisionAnalysisRequest
import com.xingshi.ai.plugins.api.VisionAnalysisResult

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
        val manager = PluginFramework.manager() ?: return Outcome.NotInstalled
        val pluginId = manager.visionAnalysisPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.visionAnalysisPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        val database = AppDatabase.getDatabase(context)
        val repository = ApiConfigRepository(database.apiConfigDao())
        val config = repository.getVisionConfig() ?: return Outcome.NotConfigured
        val request = requestFrom(config, imagePath, prompt) ?: return Outcome.NotConfigured
        return when (val call = OptionalCapabilityCall.invoke(manager, pluginId, version) { plugin.analyze(request) }) {
            is Completed -> when (val result = call.value) {
                is VisionAnalysisResult.Described -> Outcome.Described(result.description)
                is VisionAnalysisResult.Failure -> Outcome.Failed(result.reason)
                VisionAnalysisResult.Cancelled -> Outcome.Failed("视觉识别已取消")
            }
            else -> Outcome.NotInstalled
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
}
