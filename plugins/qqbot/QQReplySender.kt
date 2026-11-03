package com.xingshi.ai.feature.qqbot

import com.xingshi.ai.feature.qqbot.data.model.QQInboundEvent
import com.xingshi.ai.feature.qqbot.data.model.QQMessageEvent
import com.xingshi.ai.plugins.api.ChannelReplyRoute
import com.xingshi.ai.plugins.api.ChannelSendResult
import kotlinx.serialization.json.*

 
class QQReplySender(private val send: suspend (QQInboundEvent, String) -> ChannelSendResult) {
    companion object {
    fun capture(accountId: String, event: QQInboundEvent): ChannelReplyRoute {
        val locator = buildJsonObject {
            when (event) {
                is QQInboundEvent.C2CMessage -> { put("kind", "C2C"); put("target", event.userOpenid) }
                is QQInboundEvent.GroupAtMessage -> { put("kind", "GROUP"); put("target", event.groupOpenid); put("peer", event.memberOpenid) }
                is QQInboundEvent.GuildMessage -> { put("kind", "GUILD"); put("target", event.channelId); put("peer", event.authorId); event.guildId?.let { put("guild", it) } }
                is QQInboundEvent.DirectMessage -> { put("kind", "DIRECT"); put("target", event.guildId); put("peer", event.authorId) }
            }
        }
        val key = key(event)
        return ChannelReplyRoute(accountId, key, key, event.raw.id, locator.toString())
    }
        private fun key(event: QQInboundEvent) = when (event) {
            is QQInboundEvent.C2CMessage -> event.userOpenid
            is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
            is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
            is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
        }
    }

    suspend fun deliver(currentAccountId: String, route: ChannelReplyRoute, text: String): ChannelSendResult {
        if (route.accountId.isBlank() || route.accountId != currentAccountId) return ChannelSendResult.Failed("账号已更替，已拒绝旧请求发送")
        val event = restore(route) ?: return ChannelSendResult.Failed("缺少合法原QQ请求定位，无法发送")
        if (key(event) != route.externalConversationId) return ChannelSendResult.Failed("路由不匹配，已拒绝发送")
        return send(event, text)
    }

    private fun restore(route: ChannelReplyRoute): QQInboundEvent? = runCatching {
        require(route.platformMessageId.isNotBlank())
        val locator = Json.parseToJsonElement(requireNotNull(route.opaqueRoute)).jsonObject
        fun field(name: String) = locator[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        val target = field("target").also { require(it.isNotBlank()) }
        val peer = field("peer")
        val raw = QQMessageEvent(id = route.platformMessageId)
        when (field("kind")) {
            "C2C" -> QQInboundEvent.C2CMessage(target, raw)
            "GROUP" -> QQInboundEvent.GroupAtMessage(target, peer.also { require(it.isNotBlank()) }, raw)
            "GUILD" -> QQInboundEvent.GuildMessage(target, field("guild").ifBlank { null }, peer.also { require(it.isNotBlank()) }, raw)
            "DIRECT" -> QQInboundEvent.DirectMessage(target, peer.also { require(it.isNotBlank()) }, raw)
            else -> null
        }
    }.getOrNull()

}
