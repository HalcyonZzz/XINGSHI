package com.xingshi.ai.feature.settings

import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.api.VideoPreset
import com.xingshi.ai.plugins.api.VideoPresets as SharedVideoPresets

object VideoPresets {
    val all: List<VideoPreset> get() = SharedVideoPresets.all

    fun of(id: String): VideoPreset? = SharedVideoPresets.of(id)

    fun byBaseUrl(baseUrl: String): VideoPreset =
        SharedVideoPresets.presetOf(SharedVideoPresets.legacyIdOf(baseUrl))

    fun forConfig(providerId: String, baseUrl: String): VideoPreset =
        of(providerId) ?: byBaseUrl(baseUrl)
}

private fun providerOf(presetId: String): ApiProvider = when (presetId) {
    "qwen" -> ApiProvider.DASHSCOPE
    "glm" -> ApiProvider.ZHIPU
    "minimax" -> ApiProvider.MINIMAX
    "kimi" -> ApiProvider.KIMI
    else -> ApiProvider.CUSTOM
}

const val VIDEO_CONFIG_KIND = "VIDEO"
const val VIDEO_CONFIG_NAME = "视频识别"

const val VIDEO_SAVED_KEY = "ui_video_config_saved"
const val VIDEO_DELETED_KEY = "ui_video_config_deleted"
const val VIDEO_ENABLED_KEY = "ui_video_config_enabled_on"
const val VIDEO_DISABLED_KEY = "ui_video_config_enabled_off"
const val VIDEO_SAVE_FAILED_KEY = "ui_video_config_save_failed"
const val VIDEO_DELETE_FAILED_KEY = "ui_video_config_delete_failed"
const val VIDEO_KEY_REQUIRED_KEY = "ui_video_config_key_required"
const val VIDEO_URL_REQUIRED_KEY = "ui_video_config_url_required"
const val VIDEO_MODEL_REQUIRED_KEY = "ui_video_config_model_required"

fun applyVideoPreset(current: ApiConfig?, preset: VideoPreset): ApiConfig {
    if (current == null) {
        return ApiConfig(
            provider = providerOf(preset.id),
            name = VIDEO_CONFIG_NAME,
            apiKey = "",
            baseUrl = preset.defaultBaseUrl,
            model = preset.defaultModel,
            kind = VIDEO_CONFIG_KIND,
            providerId = preset.id
        )
    }
    val previous = SharedVideoPresets.presetOf(current.providerId)
    val keepBaseUrl = !isPresetDefault(current.baseUrl, previous.defaultBaseUrl)
    val keepModel = !isPresetDefault(current.model, previous.defaultModel)
    return current.copy(
        provider = providerOf(preset.id),
        baseUrl = if (keepBaseUrl) current.baseUrl else preset.defaultBaseUrl,
        model = if (keepModel) current.model else preset.defaultModel,
        kind = VIDEO_CONFIG_KIND,
        providerId = preset.id
    )
}

private fun isPresetDefault(value: String, presetDefault: String): Boolean =
    value.isBlank() || value.trim().trimEnd('/') == presetDefault.trimEnd('/')

fun validateVideoConfig(config: ApiConfig): String? = when {
    config.apiKey.isBlank() -> VIDEO_KEY_REQUIRED_KEY
    config.baseUrl.isBlank() -> VIDEO_URL_REQUIRED_KEY
    config.model.isBlank() -> VIDEO_MODEL_REQUIRED_KEY
    else -> null
}

suspend fun ensureVideoProviderId(repository: ApiConfigRepository, config: ApiConfig): ApiConfig {
    if (config.kind != VIDEO_CONFIG_KIND) return config
    val providerId = config.providerId.ifBlank { SharedVideoPresets.legacyIdOf(config.baseUrl) }
    val preset = SharedVideoPresets.presetOf(providerId)
    val baseUrl = if (config.baseUrl.isBlank()) preset.defaultBaseUrl else config.baseUrl
    val model = if (config.model.isBlank()) preset.defaultModel else config.model
    if (providerId == config.providerId && baseUrl == config.baseUrl && model == config.model) return config
    val migrated = config.copy(providerId = providerId, baseUrl = baseUrl, model = model)
    repository.updateConfig(migrated)
    return migrated
}

suspend fun saveVideoConfiguration(repository: ApiConfigRepository, config: ApiConfig): String? {
    val existing = if (config.id > 0L && config.apiKey.isBlank()) repository.getConfigById(config.id) else null
    val merged = (if (existing?.apiKey?.isNotBlank() == true) config.copy(apiKey = existing.apiKey) else config)
        .copy(kind = VIDEO_CONFIG_KIND)
    validateVideoConfig(merged)?.let { return it }
    val savedId = repository.saveConfig(merged)
    repository.deleteVideoConfigsExcept(savedId)
    return null
}

suspend fun deleteVideoConfiguration(repository: ApiConfigRepository, id: Long) {
    if (id <= 0L) return
    repository.deleteConfigById(id)
}
