package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.plugins.ChatSendEntry
import com.xingshi.ai.plugins.PluginSendGuard
import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.currentCoroutineContext

/**
 * 应用级插件发送口：页面未创建时（例如只有通道图片入站）插件结果仍进入统一对话入口。
 * 路由来自原请求时随本次入队保留，回复按该请求回原会话；不创建 ChatViewModel。
 * 与页面入口共用同一取消/接受合同：实际入队在框架给出的 PluginSendGuard 临界区内完成。
 */
class ApplicationChatEntry(private val app: Application) : ChatSendEntry {
    private val partition = com.xingshi.ai.common.AccountStorage.partition

    override suspend fun submit(companionId: Long, originalText: String, mergedText: String): Boolean =
        submitScoped(companionId, originalText, mergedText, null)

    override suspend fun submitScoped(
        companionId: Long,
        originalText: String,
        mergedText: String,
        request: ChannelRequestRef?
    ): Boolean {
        if (partition != com.xingshi.ai.common.AccountStorage.partition) return false
        val guard = currentCoroutineContext()[PluginSendGuard]
        val outcome = ConversationIntake.submit(
            app = app,
            companionId = companionId,
            text = originalText.ifBlank { mergedText },
            source = ConversationIntake.Source.PLUGIN,
            request = request,
            enqueueText = mergedText,
            guard = guard, accountPartition = partition
        )
        return outcome.accepted
    }

    override suspend fun submitExisting(companionId: Long, originalText: String, mergedText: String,
        request: ChannelRequestRef?, localMessageId: Long): Boolean {
        if (partition != com.xingshi.ai.common.AccountStorage.partition) return false
        return ConversationIntake.submit(app, companionId, originalText.ifBlank { "[图片]" }, source = ConversationIntake.Source.PLUGIN,
            request = request, enqueueText = mergedText, guard = currentCoroutineContext()[PluginSendGuard],
            existingUserMessageId = localMessageId, accountPartition = partition).accepted
    }
}
