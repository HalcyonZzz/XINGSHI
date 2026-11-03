package com.xingshi.ai.plugins.api

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope

 
interface XingShiChannel {
    val pluginId: String
    val displayName: String

     
    suspend fun start(host: ChannelHost)

     
    suspend fun stop()

     
    suspend fun sendOutbound(message: ChannelOutbound): ChannelSendResult
}

 
class ChannelHost(
    val scope: CoroutineScope,
    val appContext: Context,
    val privateDir: File,
    private val statusSink: (ChannelStatus) -> Unit,
    private val inboundSink: suspend (ChannelInbound) -> ChannelInboundResult,
    private val routeSink: (companionId: Long, route: ChannelReplyRoute) -> Unit,
     
    private val routeRevokeSink: suspend (accountId: String?) -> Unit = {},
     
    private val isActive: () -> Boolean = { true }
) {
    fun reportStatus(status: ChannelStatus) {
        if (isActive()) statusSink(status)
    }

     
    suspend fun acceptInbound(inbound: ChannelInbound): ChannelInboundResult =
        if (isActive()) inboundSink(inbound) else ChannelInboundResult(false, reason = "通道已停止")

     
    fun bindReplyRoute(companionId: Long, route: ChannelReplyRoute) {
        if (isActive()) routeSink(companionId, route)
    }

     
    suspend fun revokeReplyRoutes(accountId: String? = null) {
        if (isActive()) routeRevokeSink(accountId)
    }
}

sealed interface ChannelStatus {
    data object Unbound : ChannelStatus
    data object Connecting : ChannelStatus
    data object Connected : ChannelStatus
    data class Disconnected(val reason: String) : ChannelStatus
    data class Failed(val reason: String) : ChannelStatus
}

 
data class ChannelInbound @JvmOverloads constructor(
    val pluginId: String,
    val accountId: String,
    val externalConversationId: String,
    val senderId: String,
    val senderName: String,
    val platformMessageId: String,
    val text: String,
     
    val companionId: Long? = null,
    val attachments: List<PluginAttachment> = emptyList(),
     
    val request: ChannelRequestRef? = null
)

data class ChannelInboundResult(
    val accepted: Boolean,
    val companionId: Long? = null,
    val reason: String? = null
)

 
data class ChannelRequestRef(
    val pluginId: String,
    val replyRoute: ChannelReplyRoute
)

 
data class ChannelReplyRoute(
    val accountId: String,
    val externalConversationId: String,
    val senderId: String = "",
    val platformMessageId: String = "",
     
    val opaqueRoute: String? = null
)

data class ChannelOutbound(
    val companionId: Long,
    val route: ChannelReplyRoute,
    val text: String
)

sealed interface ChannelSendResult {
    data object Sent : ChannelSendResult
     
    data class Failed(val message: String) : ChannelSendResult
    data class Unknown(val message: String) : ChannelSendResult
}

 
fun interface HostChannelGateway {
    suspend fun accept(inbound: ChannelInbound): ChannelInboundResult
}
