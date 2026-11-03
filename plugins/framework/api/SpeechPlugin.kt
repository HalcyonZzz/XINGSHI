package com.xingshi.ai.plugins.api

 
interface SpeechRecognitionPlugin {

     
    val speechPresets: List<SpeechPreset> get() = emptyList()

     
    suspend fun recognize(request: SpeechRecognitionRequest): SpeechRecognitionResult
}

data class SpeechPreset(
    val id: String,
    val providerName: String,
    val endpoint: String,
    val defaultModel: String,
    val models: List<String> = listOf(defaultModel)
)

data class SpeechRecognitionRequest(
    val audioPath: String,
    val mimeType: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val audioDurationMs: Long = 0L,
    val language: String? = null
)

sealed interface SpeechRecognitionResult {
    data class Success(val text: String) : SpeechRecognitionResult

    data class Failure(
        val reason: String,
        val retryable: Boolean = true
    ) : SpeechRecognitionResult

    data object Cancelled : SpeechRecognitionResult
}

 
data class SpeechSegmentConfig(
    val sampleRate: Int = 16_000,
    val minSegmentMs: Int = 400,
    val silenceHoldMs: Int = 800,
    val maxSegmentMs: Int = 15_000,
    val maxWaitMs: Int = 30_000,
     
    val energyThreshold: Float = 0.02f
)

data class SpeechSegment(
    val audioPath: String,
    val durationMs: Long
)

 
interface SpeechSegmentProvider {
    fun openSegmenter(config: SpeechSegmentConfig, workDir: java.io.File): SpeechSegmenter?
}

interface SpeechSegmenter {
     
    suspend fun nextSegment(): SpeechSegment?
    fun cancel()
    fun release()
}

 
interface TextToSpeechPlugin {

    val ttsPresets: List<TtsPreset> get() = emptyList()

    val ttsVoices: List<TtsVoice> get() = emptyList()

    suspend fun synthesize(request: TtsRequest): TtsResult

     
    suspend fun cloneVoice(request: TtsCloneRequest): TtsVoiceResult =
        TtsVoiceResult.Failure("当前供应商不支持音频复刻，或尚未通过厂商验收", retryable = false)

     
    suspend fun designVoice(request: TtsDesignRequest): TtsVoiceResult =
        TtsVoiceResult.Failure("当前供应商不支持文字设计，或尚未通过厂商验收", retryable = false)
}

data class TtsPreset(
    val id: String,
    val providerName: String,
    val endpoint: String,
    val defaultModel: String,
    val defaultVoice: String,
    val responseFormat: String = "mp3",
     
    val verified: Boolean = false,
    val supportsClone: Boolean = false,
    val supportsDesign: Boolean = false,
     
    val regions: List<String> = emptyList(),
     
    val sampleFormats: List<String> = listOf("mp3", "wav", "m4a"),
    val sampleMaxBytes: Long = 20L * 1024 * 1024,
    val sampleMinDurationMs: Long = 3_000L,
    val sampleMaxDurationMs: Long = 300_000L
)

data class TtsCloneRequest(
    val samplePath: String,
    val mimeType: String,
    val providerId: String = "",
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val displayName: String,
    val region: String = "",
    val workspace: String = "",
    val outputDir: java.io.File
)

data class TtsDesignRequest(
    val prompt: String,
    val previewText: String,
    val providerId: String = "",
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val region: String = "",
    val workspace: String = "",
    val outputDir: java.io.File
)

sealed interface TtsVoiceResult {
    data class Success(
        val voiceId: String,
        val model: String = "",
        val region: String = "",
        val previewPath: String = "",
        val mimeType: String = "",
        val durationMs: Long = 0L
    ) : TtsVoiceResult

    data class Failure @JvmOverloads constructor(
        val reason: String,
        val retryable: Boolean = true,
         
        val code: String = ""
    ) : TtsVoiceResult
}

data class TtsVoice(
    val id: String,
    val label: String,
     
    val providerId: String = ""
)

data class TtsRequest(
    val text: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val voice: String,
    val responseFormat: String,
    val outputDir: java.io.File,
    val providerId: String = "",
     
    val voiceMode: String = "preset",
    val voiceId: String = "",
    val voiceSamplePath: String = "",
    val voiceDesignPrompt: String = "",
    val voiceRegion: String = "",
     
    val voiceWorkspace: String = ""
)

sealed interface TtsResult {
    data class Success(
        val audioPath: String,
        val mimeType: String,
        val durationMs: Long = 0L
    ) : TtsResult

    data class Failure @JvmOverloads constructor(
        val reason: String,
        val retryable: Boolean = true,
         
        val code: String = ""
    ) : TtsResult

    data object Cancelled : TtsResult
}

 
interface VisionAnalysisPlugin {
    suspend fun analyze(request: VisionAnalysisRequest): VisionAnalysisResult
}

data class VisionAnalysisRequest(
    val imagePath: String,
    val prompt: String,
    val endpoint: String,
    val apiKey: String,
    val model: String
)

sealed interface VisionAnalysisResult {
    data class Described(val description: String) : VisionAnalysisResult
    data class Failure(val reason: String, val retryable: Boolean = true) : VisionAnalysisResult
    data object Cancelled : VisionAnalysisResult
}
