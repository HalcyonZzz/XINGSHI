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
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) return Outcome.NotInstalled
        val result = coroutineScope {
            val call = async(context = gate, start = CoroutineStart.LAZY) { plugin.recognize(request) }
            call.start()
            try {
                call.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                manager.untrackOptionalExecution(pluginId, gate)
                gate.complete()
            }
        } ?: return Outcome.NotInstalled
        return when (result) {
            is SpeechRecognitionResult.Success -> Outcome.Recognized(result.text.trim())
            is SpeechRecognitionResult.Failure -> Outcome.Failed(result.reason)
            SpeechRecognitionResult.Cancelled -> Outcome.Failed("语音识别已取消")
        }
    }

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
