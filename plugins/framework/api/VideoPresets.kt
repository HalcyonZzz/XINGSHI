package com.xingshi.ai.plugins.api

data class VideoPreset(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val inlineEncodedLimit: Long,
    val inlineVideoLimit: Long,
    val supportsAudio: Boolean
)

object VideoPresets {
    val all: List<VideoPreset> = listOf(
        VideoPreset("doubao", "豆包／火山", "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seed-2-0-lite-260428", inlineEncodedLimit = 64L * 1024 * 1024, inlineVideoLimit = 50L * 1024 * 1024, supportsAudio = true),
        VideoPreset("qwen", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-omni-flash",
            inlineEncodedLimit = 10L * 1024 * 1024, inlineVideoLimit = 7L * 1024 * 1024, supportsAudio = true),
        VideoPreset("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            "glm-5.3-flash", inlineEncodedLimit = 64L * 1024 * 1024, inlineVideoLimit = 48L * 1024 * 1024, supportsAudio = false),
        VideoPreset("step", "阶跃星辰", "https://api.stepfun.com/v1",
            "step-3.7-flash", inlineEncodedLimit = 64L * 1024 * 1024, inlineVideoLimit = 128L * 1024 * 1024, supportsAudio = false),
        VideoPreset("minimax", "MiniMax", "https://api.minimax.io/v1",
            "MiniMax-M3", inlineEncodedLimit = 64L * 1024 * 1024, inlineVideoLimit = 50L * 1024 * 1024, supportsAudio = false),
        VideoPreset("kimi", "Kimi", "https://api.moonshot.cn/v1",
            "kimi-k2.6", inlineEncodedLimit = 100L * 1024 * 1024, inlineVideoLimit = 70L * 1024 * 1024, supportsAudio = false),
        VideoPreset("custom", "自定义供应商", "", "",
            inlineEncodedLimit = 32L * 1024 * 1024, inlineVideoLimit = 24L * 1024 * 1024, supportsAudio = false)
    )

    fun of(id: String): VideoPreset? = all.firstOrNull { it.id == id }

    fun presetOf(id: String): VideoPreset = of(id) ?: of("custom")!!

    fun legacyIdOf(baseUrl: String): String {
        val url = baseUrl.lowercase()
        return when {
            url.contains("volces.com") || url.contains("ark.cn") -> "doubao"
            url.contains("stepfun.com") -> "step"
            url.contains("bigmodel.cn") -> "glm"
            url.contains("minimax") -> "minimax"
            url.contains("moonshot") -> "kimi"
            url.contains("dashscope") || url.contains("aliyuncs") -> "qwen"
            else -> "custom"
        }
    }
}
