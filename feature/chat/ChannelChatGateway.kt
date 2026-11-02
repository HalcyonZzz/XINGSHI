package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.plugins.api.ChannelInbound
import com.xingshi.ai.plugins.api.ChannelInboundResult
import com.xingshi.ai.plugins.api.HostChannelGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用级通道对话入口：通道插件不直接写库，统一在这里过封禁、角色校验与输入检查，
 * 通过后才落库并进入既有对话链路。聊天页仍走 ChatViewModel，二者复用同一存储与检查。
 */
class ChannelChatGateway(private val app: Application) : HostChannelGateway {
    private val partition = com.xingshi.ai.common.AccountStorage.partition

    /** 与聊天页同一入口：校验、落库、入队都在 ConversationIntake；只有真正入队才算接受。 */
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
