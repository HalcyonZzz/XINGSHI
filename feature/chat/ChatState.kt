package com.xingshi.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.CompanionEntity

/**
 * ChatViewModel 打包状态 — 统一替换零散 StateFlow。
 *
 * 控制论: 单点状态降低扰动传播链，combine() 保证一致性。
 */
@Stable
data class ChatState(
    val companionData: CompanionEntity? = null,
    val messages: List<ChatMessage> = emptyList(),
    val visibleMessages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val typingText: String = "",
    val reasoningText: String = "",
    val isReasoning: Boolean = false,
    val userName: String = "我",
    val userAvatar: String? = null,
    val queueDepth: Int = 0,
    val hasMoreMessages: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** 流水线阶段: IDLE → VALIDATE → CLASSIFY → ENCRYPT → SEND → CONFIRM → DONE */
    val pipelineStage: String = "IDLE",
    /** 流水线错误信息 */
    val pipelineError: String? = null
)
