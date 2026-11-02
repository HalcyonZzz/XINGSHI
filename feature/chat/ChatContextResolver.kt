package com.xingshi.ai.feature.chat.data

import com.xingshi.ai.common.ChatConstants
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.model.ChatMessage

/**
 * 聊天 UI 消息列表上限保护。
 *
 * - 对 UI 侧消息列表做上限保护，防止长对话时内存持续增长
 */
class ChatContextResolver {

    companion object {
        // 数值常量已收敛到 ChatConstants，此处保留文档注释。
        /**
         * UI 层内存中保留的最大消息条数（older + recent）。
         * 超过时丢弃最旧的历史，避免 LazyColumn + StateFlow 组合导致内存持续增长。
         */
        const val MAX_UI_MESSAGES = ChatConstants.MAX_UI_MESSAGES
    }

    /**
     * 对 UI 消息列表做上限保护。
     * 返回裁剪后的列表（保留最近的 [MAX_UI_MESSAGES] 条），以及是否发生了裁剪。
     */
    fun capUiMessages(allMessages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        if (allMessages.size <= MAX_UI_MESSAGES) return allMessages to false
        val dropped = allMessages.size - MAX_UI_MESSAGES
        SecureLog.w("ChatContextResolver", "UI messages capped: dropped $dropped oldest messages")
        return allMessages.takeLast(MAX_UI_MESSAGES) to true
    }
}
