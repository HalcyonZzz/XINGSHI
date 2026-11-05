package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.plugins.ChatSendEntry
import com.xingshi.ai.plugins.PluginSendGuard
import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.currentCoroutineContext

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
