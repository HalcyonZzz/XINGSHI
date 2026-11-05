package com.xingshi.ai.plugins.api

import androidx.compose.runtime.Composable

interface XingShiPlugin {
    val id: String
    val name: String
    fun inputActions(): List<PluginInputAction> = emptyList()
    fun acceptsSentImages(): Boolean = false

    fun requiresVisionModel(): Boolean = false
    fun chatCards(): List<PluginChatCard> = emptyList()
    fun page(): PluginPageContent? = null
    fun settingsPage(): PluginPageContent? = null

    fun channel(): XingShiChannel? = null
    suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport
    fun cleanup() {}
}

class PluginInputAction(
    val id: String,
    val label: String,
    val icon: PluginIcon = PluginIcon.Default
)

enum class PluginIcon { Default, Image, Text, File, Audio, Video, Search, Star }

data class PluginRequest(
    val companionId: Long,
    val text: String = "",
    val attachments: List<PluginAttachment> = emptyList()
)

data class PluginAttachment(
    val path: String,
    val mimeType: String,
    val kind: PluginAttachmentKind
)

enum class PluginAttachmentKind { IMAGE, AUDIO, VIDEO, FILE }

sealed interface PluginReport {
    data class Success(val text: String) : PluginReport
    data class Failure(val message: String) : PluginReport
    data object Cancelled : PluginReport
}

class PluginTool(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val execute: suspend (String) -> String
)

data class PluginMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<PluginToolCall> = emptyList()
)

data class PluginToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class PluginModelResponse(
    val text: String? = null,
    val toolCalls: List<PluginToolCall> = emptyList(),
    val finishReason: String? = null
)

interface PluginModel {
    val isConfigured: Boolean
    val supportsToolCalls: Boolean
    suspend fun complete(messages: List<PluginMessage>, tools: List<PluginTool>): PluginModelResponse
}

interface PluginRuntime {
    val companionId: Long
    val model: PluginModel
    fun isActive(): Boolean
    suspend fun runToolLoop(prompt: String, input: String, tools: List<PluginTool>, maxRounds: Int = 4): PluginReport
    suspend fun saveAttachment(path: String, mimeType: String, kind: PluginAttachmentKind): String
}

typealias PluginPageContent = @Composable (PluginPageContext) -> Unit

class PluginPageContext(
    val companionId: Long,
    val input: String,
    val close: () -> Unit,
    val execute: (PluginRequest) -> Unit,
    val cancel: () -> Unit,
    val report: PluginReport?,
    val actionId: String? = null,
    val navigate: (route: String) -> Unit = {}
)

typealias PluginChatCardContent = @Composable (PluginChatCardContext) -> Unit

class PluginChatCard(
    val id: String,
    val content: PluginChatCardContent
)

class PluginChatCardContext(
    val companionId: Long,
    val input: String,
    val report: PluginReport?,
    val execute: (PluginRequest) -> Unit
)
