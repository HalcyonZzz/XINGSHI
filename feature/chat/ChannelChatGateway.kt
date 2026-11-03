package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.plugins.api.ChannelInbound
import com.xingshi.ai.plugins.api.ChannelInboundResult
import com.xingshi.ai.plugins.api.HostChannelGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 
class ChannelChatGateway(private val app: Application) : HostChannelGateway {
    private val partition = com.xingshi.ai.common.AccountStorage.partition

     
    override suspend fun accept(inbound: ChannelInbound): ChannelInboundResult = withContext(Dispatchers.IO) {
        if (inbound.text.isBlank() && inbound.attachments.isEmpty()) {
            return@withContext ChannelInboundResult(false, reason = "空消息")
        }
        val companionId = inbound.companionId
            ?: return@withContext ChannelInboundResult(false, reason = "未映射到角色")
        val outcome = ConversationIntake.submit(
            app = app,
            companionId = companionId,
            text = inbound.text,
            attachments = inbound.attachments,
            source = ConversationIntake.Source.CHANNEL,
            request = inbound.request, accountPartition = partition
        )
        if (outcome.accepted) {
            SecureLog.d("ChannelChatGateway", "accepted inbound plugin=${inbound.pluginId} companion=$companionId")
        }
        ChannelInboundResult(outcome.accepted, companionId = companionId, reason = outcome.reason)
    }
}
