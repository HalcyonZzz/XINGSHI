package com.xingshi.ai.plugins.api

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * 可选聊天通道能力。工具插件继续只实现 [XingShiPlugin.run]；通道插件额外实现本接口，
 * 由插件自己负责登录态、平台连接与协议，宿主只提供通用运行环境和双向消息交接。
 */
interface XingShiChannel {
    val pluginId: String
    val displayName: String

    /** 宿主给出的运行环境；start 成功返回后应保持连接，直到 stop 被调用。 */
    suspend fun start(host: ChannelHost)

    /** 停止连接、轮询与重连；可重复调用。 */
    suspend fun stop()

    /** 宿主把属于本通道的回复交回插件，由插件执行平台发送；返回真实结果。 */
    suspend fun sendOutbound(message: ChannelOutbound): ChannelSendResult
}

/** 通道运行环境：作用域归宿主，插件不得自行持有全局后台作用域。 */
class ChannelHost(
    val scope: CoroutineScope,
    val appContext: Context,
    val privateDir: File,
    private val statusSink: (ChannelStatus) -> Unit,
    private val inboundSink: suspend (ChannelInbound) -> ChannelInboundResult,
    private val routeSink: (companionId: Long, route: ChannelReplyRoute) -> Unit,
    /** 账号更替/退出时撤销本实例登记的路由；null 表示撤销本插件全部路由。 */
    private val routeRevokeSink: suspend (accountId: String?) -> Unit = {},
    /** 实例资格：通道停止/更新/卸载后，旧实例的回调一律失效。 */
    private val isActive: () -> Boolean = { true }
) {
    fun reportStatus(status: ChannelStatus) {
        if (isActive()) statusSink(status)
    }

    /** 入站消息交给宿主统一对话入口，返回是否被接受；停止后的迟到回调直接拒绝。 */
    suspend fun acceptInbound(inbound: ChannelInbound): ChannelInboundResult =
        if (isActive()) inboundSink(inbound) else ChannelInboundResult(false, reason = "通道已停止")

    /** 登记本通道回复的回流路由；按登记顺序排队，停止后的迟到登记被忽略。 */
    fun bindReplyRoute(companionId: Long, route: ChannelReplyRoute) {
        if (isActive()) routeSink(companionId, route)
    }

    /** 账号更替/退出：撤销旧账号尚未回复的路由，旧请求不能由新账号发起平台发送。 */
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

/** 入站消息：路由字段保留在宿主结构中，不拼进模型正文。 */
data class ChannelInbound @JvmOverloads constructor(
    val pluginId: String,
    val accountId: String,
    val externalConversationId: String,
    val senderId: String,
    val senderName: String,
    val platformMessageId: String,
    val text: String,
    /** 插件按既有映射确定的目标角色；宿主仍负责角色校验与接受边界。 */
    val companionId: Long? = null,
    val attachments: List<PluginAttachment> = emptyList(),
    /** 本次请求的回复路由归属：随消息进入接受、排队与回复交接，不拼进模型正文。 */
    val request: ChannelRequestRef? = null
)

data class ChannelInboundResult(
    val accepted: Boolean,
    val companionId: Long? = null,
    val reason: String? = null
)

/**
 * 一次通道请求的回复归属：路由 + 发起插件。
 * 宿主用它把接受、排队与回复交接串成同一请求，不再按角色弹队首路由。
 */
data class ChannelRequestRef(
    val pluginId: String,
    val replyRoute: ChannelReplyRoute
)

/** 回复路由：足以回到发起消息的外部会话，避免账号/联系人/群串话。 */
data class ChannelReplyRoute(
    val accountId: String,
    val externalConversationId: String,
    val senderId: String = "",
    val platformMessageId: String = "",
    /** Minimal typed reply locator, stored only with the original local route; never model input. */
    val opaqueRoute: String? = null
)

data class ChannelOutbound(
    val companionId: Long,
    val route: ChannelReplyRoute,
    val text: String
)

sealed interface ChannelSendResult {
    data object Sent : ChannelSendResult
    /** Explicitly rejected before a platform send took effect; the original route may retry. */
    data class Failed(val message: String) : ChannelSendResult
    data class Unknown(val message: String) : ChannelSendResult
}

/** 宿主统一对话入口；由 app 注入，通道插件不直接写库。 */
fun interface HostChannelGateway {
    suspend fun accept(inbound: ChannelInbound): ChannelInboundResult
}
