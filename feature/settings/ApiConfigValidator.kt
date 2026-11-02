package com.xingshi.ai.feature.settings

import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider

/**
 * 配置保存 / 启用的完整性校验：只做判断，不落库、不激活。
 *
 * - 编辑时未重填 Key 会先与库内旧值合并，所以这里看到的是合并后的配置；
 *   新建预设没有旧值可合并，空 Key 会在这里被拒绝，不会顶掉原有有效配置。
 * - 自定义 API 允许本地无 Key 端点，但必须有接口地址；自定义中继沿用既有免 Key 口径。
 */
object ApiConfigValidator {

    /**
     * @param allowBlankKey 云端已确认的配置：手机不长期保存 Key，编辑时留空表示沿用云端凭据。
     */
    fun validate(config: ApiConfig, allowBlankKey: Boolean = false): String? = when {
        config.model.isBlank() -> "保存失败：请填写模型名称"
        config.provider == ApiProvider.PARTNER -> null
        config.provider == ApiProvider.CUSTOM ->
            if (config.baseUrl.isBlank()) "保存失败：请填写接口地址" else null
        config.apiKey.isBlank() && !allowBlankKey -> "保存失败：请填写 API Key"
        else -> null
    }

    /** 语音识别是独立全局配置：端点、Key、模型都必须明确，不回退主模型或视觉配置。 */
    fun validateStt(config: ApiConfig): String? = when {
        config.apiKey.isBlank() -> "保存失败：请填写 API Key"
        config.baseUrl.isBlank() -> "保存失败：请填写转写接口地址"
        config.model.isBlank() -> "保存失败：请填写模型名称"
        else -> null
    }

    /** 语音合成同样独立：端点、Key、模型（音色标识存于 model 字段的约定由 UI 保证）。 */
    fun validateTts(config: ApiConfig): String? = when {
        config.apiKey.isBlank() -> "保存失败：请填写 API Key"
        config.baseUrl.isBlank() -> "保存失败：请填写合成接口地址"
        config.model.isBlank() -> "保存失败：请填写模型名称"
        else -> null
    }
}
