package com.xingshi.ai.feature.qqbot

import android.content.Context
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.feature.qqbot.data.QQBotTokenStore
import com.xingshi.ai.feature.qqbot.data.QQBotUserMappingManager
import com.xingshi.ai.feature.qqbot.data.model.QQInboundEvent
import com.xingshi.ai.feature.qqbot.data.network.QQBotWebSocketClient
import com.xingshi.ai.feature.qqbot.service.QQBotServiceLocator
import com.xingshi.ai.feature.qqbot.ui.QQBotSettingsScreen
import com.xingshi.ai.plugins.api.ChannelHost
import com.xingshi.ai.plugins.api.ChannelInbound
import com.xingshi.ai.plugins.api.ChannelOutbound
import com.xingshi.ai.plugins.api.ChannelReplyRoute
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.plugins.api.ChannelSendResult
import com.xingshi.ai.plugins.api.ChannelStatus
import com.xingshi.ai.plugins.api.PluginPageContent
import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import com.xingshi.ai.plugins.api.PluginRuntime
import com.xingshi.ai.plugins.api.XingShiChannel
import com.xingshi.ai.plugins.api.XingShiPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

 
class QQBotPlugin : XingShiPlugin {
    override val id: String = "qqbot"
    override val name: String = "QQ 机器人"

    private var channel: QQBotChannel? = null

    override fun channel(): XingShiChannel = channel ?: QQBotChannel().also { channel = it }

    override fun settingsPage(): PluginPageContent = { ctx ->
        QQBotSettingsScreen(onNavigateBack = ctx.close)
    }

    override suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport =
        PluginReport.Failure("QQ 机器人是聊天通道插件，不提供一次性工具执行")

     
    override fun cleanup() {
        val ctx = channel?.appContext ?: return
        QQBotServiceLocator.tokenStore(ctx).clearAll()
        QQBotServiceLocator.shutdown()
    }
}

private class QQBotChannel : XingShiChannel {
    override val pluginId: String = "qqbot"
    override val displayName: String = "QQ 机器人"

    var appContext: Context? = null
        private set
    private var context: Context? = null
    private var store: QQBotTokenStore? = null
    private var repository: com.xingshi.ai.feature.qqbot.data.QQBotMessageRepository? = null
    private var statusJob: Job? = null
    private var routeJob: Job? = null
    private var started = false
    private var currentAccountId: String = ""
     
    private val eventsByRequest = java.util.concurrent.ConcurrentHashMap<String, QQInboundEvent>()

    override suspend fun start(host: ChannelHost) {
        if (started) return
        started = true
        val ctx: Context = host.appContext.applicationContext
        context = ctx
        appContext = ctx
        val tokenStore = QQBotServiceLocator.tokenStore(ctx)
        store = tokenStore
        val repo = QQBotServiceLocator.messageRepository(ctx)
        repository = repo
        currentAccountId = tokenStore.getAccount()?.appId.orEmpty()

        val mapping = QQBotUserMappingManager(tokenStore, CompanionRepository(AppDatabase.getDatabase(ctx).companionDao()))
        val seen = LinkedHashSet<String>()
        val seenLock = Any()

        
        statusJob = host.scope.launch {
            repo.connectionState.collect { state ->
                val status = when (state) {
                    QQBotWebSocketClient.ConnectionState.CONNECTED -> ChannelStatus.Connected
                    QQBotWebSocketClient.ConnectionState.CONNECTING,
                    QQBotWebSocketClient.ConnectionState.RECONNECTING -> ChannelStatus.Connecting
                    QQBotWebSocketClient.ConnectionState.AUTH_FAILED -> ChannelStatus.Failed("鉴权失败")
                    QQBotWebSocketClient.ConnectionState.DISCONNECTED -> ChannelStatus.Disconnected("连接已断开")
                }
                host.reportStatus(status)
            }
        }
        routeJob = host.scope.launch {
            tokenStore.accountFlow.collect { account ->
                val nextAccountId = account?.appId.orEmpty()
                if (nextAccountId != currentAccountId) {
                    
                    host.revokeReplyRoutes(currentAccountId.takeIf { it.isNotBlank() })
                    eventsByRequest.clear()
                    currentAccountId = nextAccountId
                }
                if (account == null) {
                    runCatching { repo.disconnect() }
                    host.reportStatus(ChannelStatus.Unbound)
                } else {
                    host.reportStatus(ChannelStatus.Connecting)
                    repo.connect()
                }
            }
        }
        host.scope.launch {
            repo.incomingEvents.collect { event ->
                
                if (!tokenStore.getAutoReply()) return@collect
                val key = when (event) {
                    is QQInboundEvent.C2CMessage -> event.userOpenid
                    is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
                    is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
                    is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
                }
                val text = runCatching { repo.extractText(event) }.getOrElse { "" }
                val imageUrl = runCatching { repo.extractImageUrl(event) }.getOrNull()
                if (text.isBlank() && imageUrl.isNullOrBlank()) return@collect
                val platformId = runCatching { event.raw.id }.getOrElse { "" }
                val duplicate = synchronized(seenLock) {
                    if (platformId.isBlank()) false
                    else if (!seen.add(platformId)) true
                    else {
                        if (seen.size > 500) seen.remove(seen.first())
                        false
                    }
                }
                if (duplicate) return@collect
                val companionId = runCatching { mapping.getOrCreateMapping(key) }.getOrNull() ?: return@collect
                val attachments = imageUrl?.let { url ->
                    val bytes = runCatching { repo.downloadImage(url) }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) emptyList()
                    else {
                        val file = java.io.File(host.privateDir, "inbound_${platformId.ifBlank { System.currentTimeMillis().toString() }}.jpg")
                        if (runCatching { file.writeBytes(bytes) }.isSuccess) {
                            listOf(com.xingshi.ai.plugins.api.PluginAttachment(
                                file.absolutePath,
                                "image/jpeg",
                                com.xingshi.ai.plugins.api.PluginAttachmentKind.IMAGE
                            ))
                        } else emptyList()
                    }
                } ?: emptyList()
                if (text.isBlank() && attachments.isEmpty()) return@collect
                val accountId = currentAccountId
                if (accountId.isBlank()) return@collect
                val route = QQReplySender.capture(accountId, event)
                
                
                if (platformId.isNotBlank()) {
                    eventsByRequest[platformId] = event
                }
                val result = try {
                    host.acceptInbound(
                        ChannelInbound(
                            pluginId = pluginId,
                            accountId = accountId,
                            externalConversationId = key,
                            senderId = key,
                            senderName = key,
                            platformMessageId = platformId,
                            text = text,
                            companionId = companionId,
                            attachments = attachments,
                            request = ChannelRequestRef(pluginId, route)
                        )
                    )
                } catch (e: Throwable) {
                    
                    if (platformId.isNotBlank()) eventsByRequest.remove(platformId)
                    throw e
                }
                if (!result.accepted && platformId.isNotBlank()) {
                    eventsByRequest.remove(platformId)
                }
            }
        }
    }

    override suspend fun stop() {
        statusJob?.cancel(); statusJob = null
        routeJob?.cancel(); routeJob = null
        runCatching { repository?.disconnect() }
        repository = null
        store = null
        context = null
        eventsByRequest.clear()
        started = false
    }

    override suspend fun sendOutbound(message: ChannelOutbound): ChannelSendResult {
        val repo = repository ?: return ChannelSendResult.Failed("QQ 通道未运行")
        if (message.route.opaqueRoute != null) return QQReplySender { original, body ->
            try {
                repo.sendTextMessage(original, body).fold(
                    onSuccess = { eventsByRequest.remove(original.raw.id); ChannelSendResult.Sent },
                    onFailure = { ChannelSendResult.Unknown("QQ 平台发送结果未知") })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ChannelSendResult.Unknown("QQ 平台发送结果未知") }
        }.deliver(currentAccountId, message.route, message.text)
        if (message.route.accountId.isNotBlank() && message.route.accountId != currentAccountId) {
            return ChannelSendResult.Failed("账号已更替，已拒绝旧请求发送")
        }
        val event = eventsByRequest[message.route.platformMessageId]
            ?: return ChannelSendResult.Failed("缺少原请求事件，无法发送")
        if (message.route.externalConversationId.isNotBlank()) {
            val key = when (event) {
                is QQInboundEvent.C2CMessage -> event.userOpenid
                is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
                is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
                is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
            }
            if (key != message.route.externalConversationId) {
                return ChannelSendResult.Failed("路由不匹配，已拒绝发送")
            }
        }
        return try {
            repo.sendTextMessage(event, message.text).fold(
                onSuccess = {
                    eventsByRequest.remove(message.route.platformMessageId)
                    ChannelSendResult.Sent
                },
                onFailure = { ChannelSendResult.Unknown("QQ 平台发送结果未知") }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e("QQBotChannel", "sendOutbound failed", e)
            ChannelSendResult.Unknown("QQ 平台发送结果未知")
        }
    }
}
