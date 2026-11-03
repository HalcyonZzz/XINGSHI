package com.xingshi.ai.feature.settings

import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider

 
object ApiConfigValidator {

     
    fun validate(config: ApiConfig, allowBlankKey: Boolean = false): String? = when {
        config.model.isBlank() -> "保存失败：请填写模型名称"
        config.provider == ApiProvider.PARTNER -> null
        config.provider == ApiProvider.CUSTOM ->
            if (config.baseUrl.isBlank()) "保存失败：请填写接口地址" else null
        config.apiKey.isBlank() && !allowBlankKey -> "保存失败：请填写 API Key"
        else -> null
    }

     
    fun validateStt(config: ApiConfig): String? = when {
        config.apiKey.isBlank() -> "保存失败：请填写 API Key"
        config.baseUrl.isBlank() -> "保存失败：请填写转写接口地址"
        config.model.isBlank() -> "保存失败：请填写模型名称"
        else -> null
    }

     
    fun validateTts(config: ApiConfig): String? = when {
        config.apiKey.isBlank() -> "保存失败：请填写 API Key"
        config.baseUrl.isBlank() -> "保存失败：请填写合成接口地址"
        config.model.isBlank() -> "保存失败：请填写模型名称"
        else -> null
    }
}
