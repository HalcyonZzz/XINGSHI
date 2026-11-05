package com.xingshi.ai.plugins.api

interface VideoAnalysisPlugin {

    suspend fun plan(request: VideoPlanRequest, runtime: VideoAnalysisRuntime): VideoPlanResult

    suspend fun analyze(request: VideoSegmentRequest, runtime: VideoAnalysisRuntime): VideoAnalysisResult
}

data class VideoProviderConfig(
    val providerId: String,
    val providerBaseUrl: String,
    val apiKey: String,
    val model: String
)

data class VideoPlanRequest(
    val path: String,
    val fileName: String,
    val mimeType: String,
    val sliceDir: String,
    val config: VideoProviderConfig
)

sealed class VideoPlanResult {
    data class Planned(val segments: List<VideoSegmentPlan>) : VideoPlanResult()
    data class Failed(val code: VideoFailureCode, val providerCode: String? = null) : VideoPlanResult()

    object Cancelled : VideoPlanResult()
}

data class VideoSegmentPlan(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val path: String,
    val sizeBytes: Long
)

data class VideoSegmentRequest(
    val segment: VideoSegmentPlan,
    val segmentCount: Int,
    val fileName: String,
    val mimeType: String,
    val config: VideoProviderConfig
)

interface VideoAnalysisRuntime {
    fun isActive(): Boolean
}

sealed class VideoAnalysisResult {
    data class Described(val text: String) : VideoAnalysisResult()
    data class Failed(
        val code: VideoFailureCode,
        val providerCode: String? = null,
        val requestId: String? = null
    ) : VideoAnalysisResult()

    object Cancelled : VideoAnalysisResult()
}

enum class VideoFailureCode {
    NOT_CONFIGURED,
    NOT_FOUND,
    UNSUPPORTED,
    TOO_LARGE,
    NETWORK,
    AUTH,
    RATE_LIMITED,
    EMPTY,
    IO_ERROR,
    UNKNOWN
}
