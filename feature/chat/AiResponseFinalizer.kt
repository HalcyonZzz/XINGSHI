package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.feature.chat.CallTurnHandoff
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.room.withTransaction

/**
 * AI 回复落地处理器。
 *
 * 职责链：
 * 1. reasoning 展示
 * 2. 完整回复持久化
 *
 * @param companionId 当前伴侣 ID
 * @param chatRepository 消息持久化
 * @param appSettingsStore 应用设置（reasoning 展示开关）
 * @param reasoningText reasoning 文本 StateFlow（引用，非值拷贝）
 * @param isReasoning reasoning 显示开关 StateFlow（引用）
 * @param application Application（用于微信广播）
 */
class AiResponseFinalizer(
    private val companionId: Long,
    private val chatRepository: ChatRepository,
    private val appSettingsStore: AppSettingsStore,
    private val reasoningText: MutableStateFlow<String>,
    private val isReasoning: MutableStateFlow<Boolean>,
    private val application: Application,
) {
    /** Formal message and its follow-up duty commit together before server acknowledgement. */
    suspend fun saveFormal(messageId: String, content: String, createdAt: Long, link: String, payload: String): com.xingshi.ai.database.BackendDeliveryReceipt {
        val db = com.xingshi.ai.database.AppDatabase.getDatabase(application)
        return db.withTransaction {
            db.backendHandoffDao().receipt(messageId) ?: run {
                val localId = chatRepository.sendMessageAndGetId(ChatMessage(companionId = companionId, content = content,
                    isFromUser = false, timestamp = createdAt, linkString = link))
                com.xingshi.ai.database.BackendDeliveryReceipt(messageId, companionId, localId,
                    com.xingshi.ai.database.repository.ChatMessageCrypto.encrypt(payload)).also { db.backendHandoffDao().insertReceipt(it) }
            }
        }
    }
    /**
     * Process and save an AI response: reasoning display, DB commit, channel handoff.
     * Returns the message ID for the saved response.
     */
    suspend fun finalizeResponse(
        fromChannelTurn: Boolean = false,
        request: ChannelRequestRef? = null,
        aiContent: String,
        reasoning: String?,
        logMessage: String = "AI response received",
        linkString: String = "",
        inputMessageId: Long = 0L
    ): Long {
        if (!reasoning.isNullOrBlank() && appSettingsStore.getShowReasoning()) {
            isReasoning.value = true
            reasoningText.value = reasoning
        }

        val aiMessage = ChatMessage(
            companionId = companionId,
            content = aiContent,
            isFromUser = false,
            timestamp = System.currentTimeMillis(),
            linkString = linkString
        )
        val id = chatRepository.sendMessageAndGetId(aiMessage)
        SecureLog.d("ChatViewModel", "$logMessage, length=${aiContent.length}, id=$id")
        reasoningText.value = ""
        isReasoning.value = false
        delay(100)
        // 回复按原请求归属交回发起通道；本地对话（request 为空且非通道来源）不得占用外部路由。
        // fromChannelTurn 仅用于旧通道兼容，不再作为唯一依据。
        if (request != null) {
            runCatching { com.xingshi.ai.plugins.PluginChannels.deliverReply(companionId, request, aiContent) }
        } else if (fromChannelTurn) {
            runCatching { com.xingshi.ai.plugins.PluginChannels.deliverReply(companionId, aiContent) }
        }
        // 正式终结入口统一交回通话轮次：先落库拿到正式 messageId，再交给通话控制器消费。
        if (linkString.startsWith("call:")) {
            val callId = linkString.removePrefix("call:")
            if (callId.isNotBlank()) {
                CallTurnHandoff.publish(
                    CallTurnHandoff.Reply(
                        callId = callId,
                        companionId = companionId,
                        text = aiContent,
                        messageId = id,
                        request = request,
                        inputMessageId = inputMessageId
                    )
                )
            }
        }
        return id
    }
}
