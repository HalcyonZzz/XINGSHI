package com.xingshi.ai.feature.chat.data

import com.xingshi.ai.common.ChatConstants
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.model.ChatMessage

class ChatContextResolver {

    companion object {
        const val MAX_UI_MESSAGES = ChatConstants.MAX_UI_MESSAGES
    }

    fun capUiMessages(allMessages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        if (allMessages.size <= MAX_UI_MESSAGES) return allMessages to false
        val dropped = allMessages.size - MAX_UI_MESSAGES
        SecureLog.w("ChatContextResolver", "UI messages capped: dropped $dropped oldest messages")
        return allMessages.takeLast(MAX_UI_MESSAGES) to true
    }
}
