package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.OptionalCapabilityCall
import com.xingshi.ai.plugins.OptionalCapabilityCall.Outcome.Completed
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.DocumentExtractionRequest
import com.xingshi.ai.plugins.api.DocumentExtractionResult
import com.xingshi.ai.plugins.api.DocumentExtractionRuntime

object DocumentAnalysisService {

    sealed interface Outcome {
        data class Extracted(val format: com.xingshi.ai.plugins.api.DocumentFormat, val text: String) : Outcome
        data class Failed(val code: com.xingshi.ai.plugins.api.DocumentFailureCode) : Outcome
        data object NotInstalled : Outcome
        data object Cancelled : Outcome
    }

    fun isPluginInstalled(): Boolean =
        PluginFramework.manager()?.documentExtractionPlugin() != null

    suspend fun extract(path: String, fileName: String, mimeType: String): Outcome {
        val manager = PluginFramework.manager() ?: return Outcome.NotInstalled
        val pluginId = manager.documentExtractionPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.documentExtractionPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        val request = DocumentExtractionRequest(path = path, fileName = fileName, mimeType = mimeType)
        return when (val call = OptionalCapabilityCall.invoke(manager, pluginId, version) {
            val gate = OptionalCapabilityCall.callJob()
            plugin.extract(request, object : DocumentExtractionRuntime {
                override fun isActive(): Boolean = gate.isActive
            })
        }) {
            is Completed -> when (val result = call.value) {
                is DocumentExtractionResult.Extracted -> Outcome.Extracted(result.format, result.text)
                is DocumentExtractionResult.Failed -> Outcome.Failed(result.code)
                DocumentExtractionResult.Cancelled -> Outcome.Cancelled
            }
            else -> Outcome.NotInstalled
        }
    }
}
