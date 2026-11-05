package com.xingshi.ai.feature.chat

import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.plugins.OptionalCapabilityCall
import com.xingshi.ai.plugins.OptionalCapabilityCall.Outcome.Completed
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.VideoAnalysisPlugin
import com.xingshi.ai.plugins.api.VideoAnalysisResult
import com.xingshi.ai.plugins.api.VideoAnalysisRuntime
import com.xingshi.ai.plugins.api.VideoFailureCode
import com.xingshi.ai.plugins.api.VideoPlanRequest
import com.xingshi.ai.plugins.api.VideoPlanResult
import com.xingshi.ai.plugins.api.VideoPresets
import com.xingshi.ai.plugins.api.VideoProviderConfig
import com.xingshi.ai.plugins.api.VideoSegmentPlan
import com.xingshi.ai.plugins.api.VideoSegmentRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

object VideoAnalysisService {

    const val PROMPT_VERSION = 1

    data class SegmentText(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val providerId: String = "",
        val model: String = "",
        val promptVersion: Int = 0,
        val fileKey: String = "",
        val endpoint: String = ""
    )

    sealed interface Outcome {
        data class Described(val segments: List<SegmentText>) : Outcome
        data class Failed(val code: VideoFailureCode) : Outcome
        data object NotInstalled : Outcome
        data object Cancelled : Outcome
    }

    fun isPluginInstalled(): Boolean =
        PluginFramework.manager()?.videoAnalysisPlugin() != null

    fun providerConfig(config: ApiConfig?): VideoProviderConfig? {
        if (config == null) return null
        if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) return null
        return VideoProviderConfig(
            providerId = providerIdOf(config),
            providerBaseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model
        )
    }

    fun providerIdOf(config: ApiConfig): String =
        config.providerId.ifBlank { VideoPresets.legacyIdOf(config.baseUrl) }

    fun videoText(segments: List<SegmentText>): String =
        segments.joinToString("\n") { "[${videoTimeLabel(it.startMs)}–${videoTimeLabel(it.endMs)}] ${it.text}" }

    fun fileKey(path: String): String {
        val source = java.io.File(path)
        if (!source.isFile) return ""
        return "${source.absolutePath}|${source.length()}|${source.lastModified()}"
    }

    fun endpointOf(baseUrl: String): String = baseUrl.trim()

    private fun videoTimeLabel(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    suspend fun prepare(
        path: String,
        fileName: String,
        mimeType: String,
        sliceDir: String,
        config: VideoProviderConfig,
        savedSegments: List<SegmentText>,
        onSegment: suspend (index: Int, segment: SegmentText) -> Unit
    ): Outcome {
        if (config.providerBaseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return Outcome.Failed(VideoFailureCode.NOT_CONFIGURED)
        }
        val manager = PluginFramework.manager() ?: return Outcome.NotInstalled
        val pluginId = manager.videoAnalysisPluginId() ?: return Outcome.NotInstalled
        val plugin = manager.videoAnalysisPlugin() ?: return Outcome.NotInstalled
        val version = manager.capabilityVersion(pluginId) ?: return Outcome.NotInstalled
        val request = VideoPlanRequest(path = path, fileName = fileName, mimeType = mimeType, sliceDir = sliceDir, config = config)
        return when (
            val call = OptionalCapabilityCall.invoke(manager, pluginId, version) {
                val gate = OptionalCapabilityCall.callJob()
                execute(
                    plugin = plugin,
                    request = request,
                    savedSegments = savedSegments,
                    runtime = object : VideoAnalysisRuntime {
                        override fun isActive(): Boolean = gate.isActive
                    },
                    gate = gate,
                    onSegment = onSegment
                )
            }
        ) {
            is Completed -> call.value
            OptionalCapabilityCall.Outcome.Unavailable -> Outcome.NotInstalled
            OptionalCapabilityCall.Outcome.Cancelled -> Outcome.Cancelled
        }
    }

    internal suspend fun execute(
        plugin: VideoAnalysisPlugin,
        request: VideoPlanRequest,
        savedSegments: List<SegmentText>,
        runtime: VideoAnalysisRuntime,
        gate: Job,
        onSegment: suspend (index: Int, segment: SegmentText) -> Unit
    ): Outcome = coroutineScope {
        val planned = when (
            val result = OptionalCapabilityCall.await(gate) { plugin.plan(request, runtime) }
        ) {
            is Completed -> result.value
            else -> return@coroutineScope Outcome.Cancelled
        }
        val segments = when (planned) {
            VideoPlanResult.Cancelled -> return@coroutineScope Outcome.Cancelled
            is VideoPlanResult.Failed -> return@coroutineScope Outcome.Failed(planned.code)
            is VideoPlanResult.Planned -> planned.segments
        }
        if (segments.isEmpty()) return@coroutineScope Outcome.Failed(VideoFailureCode.EMPTY)
        val sourceKey = fileKey(request.path)
        val results = arrayOfNulls<SegmentText>(segments.size)
        for ((position, segment) in segments.withIndex()) {
            val saved = savedSegments.firstOrNull {
                it.isReusableFor(segment, request.config, sourceKey)
            }
            if (saved != null) {
                results[position] = saved
                continue
            }
            val result = when (
                val waited = OptionalCapabilityCall.await(gate) {
                    plugin.analyze(segmentRequest(segment, segments.size, request), runtime)
                }
            ) {
                is Completed -> waited.value
                else -> return@coroutineScope Outcome.Cancelled
            }
            when (result) {
                is VideoAnalysisResult.Described -> {
                    if (result.text.isBlank()) return@coroutineScope Outcome.Failed(VideoFailureCode.EMPTY)
                    val value = SegmentText(
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = result.text,
                        providerId = request.config.providerId,
                        model = request.config.model,
                        promptVersion = PROMPT_VERSION,
                        fileKey = sourceKey,
                        endpoint = endpointOf(request.config.providerBaseUrl)
                    )
                    results[position] = value
                    onSegment(segment.index, value)
                }
                is VideoAnalysisResult.Failed -> return@coroutineScope Outcome.Failed(result.code)
                VideoAnalysisResult.Cancelled -> return@coroutineScope Outcome.Cancelled
            }
        }
        val completed = results.map { it ?: return@coroutineScope Outcome.Failed(VideoFailureCode.EMPTY) }
        Outcome.Described(completed)
    }

    private fun SegmentText.isReusableFor(
        segment: VideoSegmentPlan,
        config: VideoProviderConfig,
        sourceKey: String
    ): Boolean =
        text.isNotBlank() &&
            startMs == segment.startMs &&
            endMs == segment.endMs &&
            providerId == config.providerId &&
            model == config.model &&
            promptVersion == PROMPT_VERSION &&
            fileKey == sourceKey &&
            endpoint == endpointOf(config.providerBaseUrl)

    private fun segmentRequest(segment: VideoSegmentPlan, count: Int, request: VideoPlanRequest) =
        VideoSegmentRequest(
            segment = segment,
            segmentCount = count,
            fileName = request.fileName,
            mimeType = request.mimeType,
            config = request.config
        )
}
