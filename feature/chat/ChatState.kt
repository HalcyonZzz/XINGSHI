package com.xingshi.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.CompanionEntity

 
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
     
    val pipelineStage: String = "IDLE",
     
    val pipelineError: String? = null
)
