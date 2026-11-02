package com.xingshi.ai.plugins.api

/**
 * 可选语音识别能力：插件在 [XingShiPlugin] 之外额外实现本接口即可，不修改既有 SPI。
 * 旧插件不实现仍能正常加载；宿主不从插件 ID 或市场扫描顺序推断能力。
 */
interface SpeechRecognitionPlugin {

    /** 该插件提供的云端预设；首版官方插件至少给出一个可直接生效的预设。 */
    val speechPresets: List<SpeechPreset> get() = emptyList()

    /** 上传一段音频并返回识别文字或明确失败；插件不写聊天库、不构造对话请求。 */
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

/** 通话分句参数：集中定义有界片段，避免持续录音或无限音频文件。 */
data class SpeechSegmentConfig(
    val sampleRate: Int = 16_000,
    val minSegmentMs: Int = 400,
    val silenceHoldMs: Int = 800,
    val maxSegmentMs: Int = 15_000,
    val maxWaitMs: Int = 30_000,
    /** 归一化 RMS 能量阈值（0..1），低于该值视为静音。 */
    val energyThreshold: Float = 0.02f
)

data class SpeechSegment(
    val audioPath: String,
    val durationMs: Long
)

/** 可选通话分句能力：STT 插件在识别之外额外实现，通话控制器据此拿片段再送识别。 */
interface SpeechSegmentProvider {
    fun openSegmenter(config: SpeechSegmentConfig, workDir: java.io.File): SpeechSegmenter?
}

interface SpeechSegmenter {
    /** 阻塞等待下一段有效语音；挂断/取消/超时返回 null，插件不自行提交对话。 */
    suspend fun nextSegment(): SpeechSegment?
    fun cancel()
    fun release()
}

/** 可选语音合成能力：正文 → 音频或明确失败；不走聊天模型协议。 */
interface TextToSpeechPlugin {

    val ttsPresets: List<TtsPreset> get() = emptyList()

    val ttsVoices: List<TtsVoice> get() = emptyList()

    suspend fun synthesize(request: TtsRequest): TtsResult

    /** 音频复刻：上传样本并创建厂商音色；不支持或未通过验收的供应商保持默认失败。 */
    suspend fun cloneVoice(request: TtsCloneRequest): TtsVoiceResult =
        TtsVoiceResult.Failure("当前供应商不支持音频复刻，或尚未通过厂商验收", retryable = false)

    /** 文字设计：按描述生成试听预览与可保存音色；不支持的供应商保持默认失败。 */
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
    /** 是否已通过本单厂商验收；未验收的供应商只展示待验，不进入正式可选列表。 */
    val verified: Boolean = false,
    val supportsClone: Boolean = false,
    val supportsDesign: Boolean = false,
    /** 供应商必需的地域/业务空间等枚举选项。 */
    val regions: List<String> = emptyList(),
    /** 参考音频限制：格式、大小与真实时长（毫秒），上传前按所选供应商校验。 */
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
        /** 产品自产错误的语言键片段；供应商原始原因留空。 */
        val code: String = ""
    ) : TtsVoiceResult
}

data class TtsVoice(
    val id: String,
    val label: String,
    /** 所属供应商预设；空表示旧插件未标注。 */
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
    /** "preset" | "clone" | "design" */
    val voiceMode: String = "preset",
    val voiceId: String = "",
    val voiceSamplePath: String = "",
    val voiceDesignPrompt: String = "",
    val voiceRegion: String = "",
    /** 供应商必需的业务空间/工作区标识（阿里百炼）。 */
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
        /** 产品自产错误的语言键片段；供应商原始原因留空。 */
        val code: String = ""
    ) : TtsResult

    data object Cancelled : TtsResult
}

/** 可选「只识别并返回」视觉能力：视频通话按轮提交真实图片数据，不走自动对话交接。 */
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
