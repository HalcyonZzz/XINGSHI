package com.xingshi.ai.domain

import kotlinx.coroutines.flow.StateFlow

data class BackendSession(
    val environment: String, val userId: String, val sessionId: String,
    val accessToken: String, val refreshToken: String,
    val accessExpiresAt: String, val sessionExpiresAt: String,
    val nickname: String, val generation: Long
)

interface BackendSessionStore {
    fun current(): BackendSession?
    fun replace(expectedGeneration: Long, session: BackendSession): Boolean
    fun clear(expectedGeneration: Long)
}

data class BackendAccountState(val environment: String = "", val userId: String? = null, val generation: Long = 0, val error: String? = null, val canConfigureEnvironment: Boolean = false)

interface BackendAccountGateway {
    val state: StateFlow<BackendAccountState>
    suspend fun authenticate(account: String, password: String, register: Boolean = false)
    suspend fun logout()
    suspend fun updateProfile(nickname: String)
    suspend fun configureEnvironment(origin: String)
    fun reconnect()
}

data class CloudCompanionDraft(
    val localId: Long, val name: String, val personality: String,
    val backstory: String?, val speakingStyle: String?, val rawPrompt: String?
)

class CompanionLimitReachedException : IllegalStateException("每个账号最多创建 3 个助手，请删除助手后再创建。")

interface CompanionCloudGateway {
    suspend fun save(draft: CloudCompanionDraft): Long
    suspend fun delete(localId: Long): Boolean
}

data class ChatModelDraft(
    val localId: Long, val protocol: String, val baseUrl: String, val model: String,
    val apiKey: String, val temperature: Float, val maxTokens: Int?, val provider: String
)

fun mainChatProtocol(provider: String, formatHint: String): String? = when (provider) {
    "ANTHROPIC" -> "ANTHROPIC_MESSAGES"
    "IFLYTEK" -> "IFLYTEK_HTTP"
    "CUSTOM" -> when (formatHint.lowercase()) {
        "openai" -> "OPENAI_COMPATIBLE"
        "anthropic" -> "ANTHROPIC_MESSAGES"
        "iflytek" -> "IFLYTEK_HTTP"
        else -> null
    }
    "OPENAI", "DEEPSEEK", "DASHSCOPE", "KIMI", "XIAOMI", "ZHIPU", "SILICONFLOW",
    "OPENROUTER", "GROQ", "GEMINI", "PARTNER" -> "OPENAI_COMPATIBLE"
    else -> null
}

enum class MainChatConfigurationIssue {
    UNSUPPORTED_FORMAT, URL_REQUIRED, MODEL_REQUIRED, KEY_REQUIRED,
    MAX_TOKENS_REQUIRED, INVALID_MAX_TOKENS, INVALID_TEMPERATURE
}

class MainChatConfigurationException(val issue: MainChatConfigurationIssue) : IllegalArgumentException(issue.name)

fun ChatModelDraft.configurationIssue(): MainChatConfigurationIssue? = when {
    protocol !in setOf("OPENAI_COMPATIBLE", "ANTHROPIC_MESSAGES", "IFLYTEK_HTTP") ||
        (provider != "CUSTOM" && mainChatProtocol(provider, "") != protocol) -> MainChatConfigurationIssue.UNSUPPORTED_FORMAT
    baseUrl.isBlank() -> MainChatConfigurationIssue.URL_REQUIRED
    model.isBlank() -> MainChatConfigurationIssue.MODEL_REQUIRED
    protocol == "ANTHROPIC_MESSAGES" && maxTokens == null -> MainChatConfigurationIssue.MAX_TOKENS_REQUIRED
    maxTokens != null && maxTokens <= 0 -> MainChatConfigurationIssue.INVALID_MAX_TOKENS
    !temperature.isFinite() || temperature !in 0f..(if (protocol == "ANTHROPIC_MESSAGES") 1f else 2f) -> MainChatConfigurationIssue.INVALID_TEMPERATURE
    else -> null
}

data class CloudChatModelConfiguration(
    val localId: Long, val configurationId: String, val version: Long,
    val provider: String, val protocol: String, val baseUrl: String, val model: String,
    val temperature: Float, val maxTokens: Int?, val status: String,
    val keyConfigured: Boolean, val pending: Boolean
)

data class CloudChatModelSnapshot(
    val configurations: List<CloudChatModelConfiguration>,
    val defaultConfigurationId: String?, val defaultConfigurationVersion: Long,
    val pendingLocalIds: Set<Long>
)

sealed interface CloudChatModelPublish {
    data class Confirmed(val configuration: CloudChatModelConfiguration) : CloudChatModelPublish
    data class Rejected(val reason: String) : CloudChatModelPublish
    data class Unknown(val reason: String) : CloudChatModelPublish
}

data class CloudChatModelDefault(val configurationId: String, val version: Long, val failed: List<String>)

interface ChatModelCloudGateway {
    suspend fun publish(draft: ChatModelDraft): CloudChatModelPublish
    suspend fun attach(localId: Long, configurationId: String)
    suspend fun configurations(): CloudChatModelSnapshot
    suspend fun activate(localId: Long): CloudChatModelDefault
    suspend fun deleteConfiguration(localId: Long)
    suspend fun choices(companionId: Long): List<CloudChatModelChoice>
    suspend fun select(companionId: Long, choice: CloudChatModelChoice)
}

data class CloudChatModelChoice(val configurationId: String, val version: Long, val label: String,
    val selectionMode: String = "EXPLICIT", val selected: Boolean = false)
