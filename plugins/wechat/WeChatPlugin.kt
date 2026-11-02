package com.xingshi.ai.feature.wechat

import android.content.Context
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.feature.wechat.data.WeChatMessageRepository
import com.xingshi.ai.feature.wechat.data.WeChatTokenStore
import com.xingshi.ai.feature.wechat.service.WeChatNotificationHelper
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.feature.wechat.data.WeChatUserMappingManager
import com.xingshi.ai.feature.wechat.service.WeChatServiceLocator
import com.xingshi.ai.feature.wechat.ui.WeChatBindScreen
import com.xingshi.ai.feature.wechat.ui.WeChatSettingsScreen
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 微信聊天通道插件：能力只在安装后加载，宿主不内置平台实现。 */
class WeChatPlugin : XingShiPlugin {
    override val id: String = "wechat"
    override val name: String = "微信"

    private var channel: WeChatChannel? = null

    override fun channel(): XingShiChannel = channel ?: WeChatChannel().also { channel = it }

    override fun page(): PluginPageContent = { ctx ->
        WeChatBindScreen(onNavigateBack = ctx.close)
    }

    override fun settingsPage(): PluginPageContent = { ctx ->
        WeChatSettingsScreen(
            onNavigateBack = ctx.close,
            onBindClick = { ctx.navigate("bind") }
        )
    }

    override suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport =
        PluginReport.Failure("微信是聊天通道插件，不提供一次性工具执行")

    /** 仅卸载时调用：清除本插件登录材料、令牌与联系人映射，宿主聊天原文/附件不动。 */
    override fun cleanup() {
        val ctx = channel?.appContext ?: return
        WeChatServiceLocator.tokenStore(ctx).clearAll()
        WeChatServiceLocator.shutdown()
    }
}

private class WeChatChannel : XingShiChannel {
    override val pluginId: String = "wechat"
    override val displayName: String = "微信"

    var appContext: Context? = null
        private set
    private var context: Context? = null
    private var tokenStore: WeChatTokenStore? = null
    private var repository: WeChatMessageRepository? = null
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    private var host: ChannelHost? = null
    private var currentAccountId: String = ""
    private var started = false
    private val seen = LinkedHashSet<String>()
    private val acceptedImages = LinkedHashSet<ChannelReplyRoute>()
    private val imagesInProgress = HashSet<ChannelReplyRoute>()
    private val seenLock = Any()

    override suspend fun start(host: ChannelHost) {
        if (started) return
        started = true
        this.host = host
        val ctx = host.appContext.applicationContext
        context = ctx
        appContext = ctx
        WeChatNotificationHelper.createChannel(ctx)
        val store = WeChatServiceLocator.tokenStore(ctx)
        tokenStore = store
        repository = WeChatServiceLocator.messageRepository(ctx)
        currentAccountId = store.getAccount()?.accountId.orEmpty()
        installSinks(host, ctx, store)

        if (store.isLoggedIn()) {
            host.reportStatus(ChannelStatus.Connecting)
            host.reportStatus(ChannelStatus.Connected)
            startPolling(host)
        } else {
            host.reportStatus(ChannelStatus.Unbound)
        }
        statusJob = host.scope.launch {
            store.accountFlow.collect { account ->
                val nextAccountId = account?.accountId.orEmpty()
                if (nextAccountId != currentAccountId) {
                    // 退出/换账号：先撤销旧账号尚未回复的路由，旧请求不能由新账号发起平台发送
                    host.revokeReplyRoutes(currentAccountId.takeIf { it.isNotBlank() })
                    currentAccountId = nextAccountId
                }
                if (account == null) {
                    // 退出登录：旧仓库的 processScope 已被取消，下次登录必须重建实例
                    stopPolling()
                    runCatching { repository?.destroy() }
                    repository = null
                    WeChatServiceLocator.resetRepository()
                    host.reportStatus(ChannelStatus.Unbound)
                } else {
                    if (repository == null) {
                        repository = WeChatServiceLocator.messageRepository(ctx)
                        installSinks(host, ctx, store)
                    }
                    host.reportStatus(ChannelStatus.Connected)
                    if (pollJob?.isActive != true) startPolling(host)
                }
            }
        }
    }

    private fun installSinks(host: ChannelHost, ctx: Context, store: WeChatTokenStore) {
        val repo = repository ?: return
        val mapping = WeChatUserMappingManager(store, CompanionRepository(AppDatabase.getDatabase(ctx).companionDao()))
        // 入站交给宿主统一对话入口；插件只保留路由与去重，不写库。
        repo.inboundSink = inbound@{ message ->
            val userId = message.fromUserId ?: return@inbound
            val text = repo.extractText(message)
            if (text.isBlank()) return@inbound
            val platformId = message.messageId?.toString().orEmpty()
            if (isDuplicate(platformId)) return@inbound
            val companionId = mapping.getOrCreateMapping(userId) ?: return@inbound
            val accountId = store.getAccount()?.accountId.orEmpty()
            if (accountId.isBlank()) return@inbound
            val route = ChannelReplyRoute(accountId, userId, userId, platformId)
            host.acceptInbound(
                ChannelInbound(
                    pluginId = pluginId,
                    accountId = accountId,
                    externalConversationId = userId,
                    senderId = userId,
                    senderName = userId,
                    platformMessageId = platformId,
                    text = text,
                    companionId = companionId,
                    request = ChannelRequestRef(pluginId, route)
                )
            )
        }
        repo.imageSink = image@{ message ->
            val userId = message.fromUserId ?: return@image
            val platformId = message.messageId?.toString().orEmpty()
            val accountId = store.getAccount()?.accountId.orEmpty()
            if (accountId.isBlank()) {
                WeChatNotificationHelper.showImageDownloadFailureNotification(
                    ctx, userId, WeChatNotificationHelper.ImageFailureReason.ACCOUNT
                )
                host.reportStatus(ChannelStatus.Failed("微信账号未就绪"))
                return@image
            }
            val route = ChannelReplyRoute(accountId, userId, userId, platformId)
            if (!beginImage(route)) return@image
            var accepted = false
            try {
                val companionId = mapping.getOrCreateMapping(userId) ?: return@image
                val bytes = runCatching { repo.downloadImage(message) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    WeChatNotificationHelper.showImageDownloadFailureNotification(
                        ctx, userId, WeChatNotificationHelper.ImageFailureReason.DOWNLOAD
                    )
                    host.reportStatus(ChannelStatus.Failed("图片下载失败"))
                    return@image
                }
                val file = File(host.privateDir, "inbound_img_${platformId.ifBlank { System.currentTimeMillis().toString() }}.jpg")
                runCatching { file.writeBytes(bytes) }.getOrNull() ?: run {
                    WeChatNotificationHelper.showImageDownloadFailureNotification(
                        ctx, userId, WeChatNotificationHelper.ImageFailureReason.SAVE
                    )
                    host.reportStatus(ChannelStatus.Failed("图片保存失败"))
                    return@image
                }
                val text = repo.extractText(message)
                val result = host.acceptInbound(
                    ChannelInbound(
                        pluginId = pluginId,
                        accountId = accountId,
                        externalConversationId = userId,
                        senderId = userId,
                        senderName = userId,
                        platformMessageId = platformId,
                        text = text,
                        companionId = companionId,
                        attachments = listOf(
                            com.xingshi.ai.plugins.api.PluginAttachment(
                                file.absolutePath,
                                "image/jpeg",
                                com.xingshi.ai.plugins.api.PluginAttachmentKind.IMAGE
                            )
                        ),
                        request = ChannelRequestRef(pluginId, route)
                    )
                )
                accepted = result.accepted
                if (!result.accepted) {
                    WeChatNotificationHelper.showImageDownloadFailureNotification(
                        ctx, userId, WeChatNotificationHelper.ImageFailureReason.REJECTED, result.reason
                    )
                    host.reportStatus(ChannelStatus.Failed(result.reason ?: "图片消息未受理"))
                    return@image
                }
            } finally {
                finishImage(route, accepted)
            }
        }
    }

    private fun isDuplicate(key: String): Boolean = synchronized(seenLock) {
        if (key.isBlank()) false
        else if (!seen.add(key)) true
        else {
            if (seen.size > 500) seen.remove(seen.first())
            false
        }
    }

    private fun beginImage(route: ChannelReplyRoute): Boolean = synchronized(seenLock) {
        route !in acceptedImages && imagesInProgress.add(route)
    }

    private fun finishImage(route: ChannelReplyRoute, accepted: Boolean) = synchronized(seenLock) {
        if (accepted) {
            acceptedImages.add(route)
            if (acceptedImages.size > 500) acceptedImages.remove(acceptedImages.first())
        }
        imagesInProgress.remove(route)
    }

    private fun startPolling(host: ChannelHost) {
        val repo = repository ?: return
        if (pollJob?.isActive == true) return
        pollJob = host.scope.launch {
            while (isActive) {
                val result = runCatching { repo.pollMessages() }
                if (result.isFailure) {
                    host.reportStatus(ChannelStatus.Disconnected(result.exceptionOrNull()?.message ?: "轮询失败"))
                    delay(5000)
                } else {
                    host.reportStatus(ChannelStatus.Connected)
                    delay(1000)
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override suspend fun stop() {
        stopPolling()
        statusJob?.cancel()
        statusJob = null
        runCatching { repository?.destroy() }
        repository = null
        runCatching { WeChatServiceLocator.shutdown() }
        tokenStore = null
        context = null
        host = null
        started = false
    }

    override suspend fun sendOutbound(message: ChannelOutbound): ChannelSendResult {
        val repo = repository ?: return ChannelSendResult.Failed("微信通道未运行")
        val route = message.route
        val userId = route.externalConversationId
        if (userId.isBlank()) return ChannelSendResult.Failed("缺少外部会话")
        if (route.accountId.isNotBlank() && route.accountId != currentAccountId) {
            return ChannelSendResult.Failed("账号已更替，已拒绝旧请求发送")
        }
        return try {
            val token = repo.getContextToken(userId)
            repo.sendTextMessage(userId, message.text, token)
                .fold(
                    onSuccess = { ChannelSendResult.Sent },
                    onFailure = { ChannelSendResult.Unknown("微信平台发送结果未知") }
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e("WeChatChannel", "sendOutbound failed", e)
            ChannelSendResult.Unknown("微信平台发送结果未知")
        }
    }
}
