package com.xingshi.ai.plugins

import android.app.Application
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.plugins.api.ChannelHost
import com.xingshi.ai.plugins.api.ChannelInbound
import com.xingshi.ai.plugins.api.ChannelInboundResult
import com.xingshi.ai.plugins.api.ChannelOutbound
import com.xingshi.ai.plugins.api.ChannelReplyRoute
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.plugins.api.ChannelSendResult
import com.xingshi.ai.plugins.api.ChannelStatus
import com.xingshi.ai.plugins.api.HostChannelGateway
import com.xingshi.ai.plugins.api.XingShiChannel
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 通道插件运行期：安装后仅启动仍安装的通道，重复加载不产生重复连接；
 * 卸载/更新先停旧实例再起新实例，迟到恢复不会复活已移除通道。
 *
 * 每次启动分配执行代次；停止即作废旧代次的回调资格（状态、入站、路由、出站），
 * 卸载/更新/停止不会留下能继续交接的旧实例。
 */
object PluginChannels {

    private val mutex = Mutex()

    private val _statuses = MutableStateFlow<Map<String, ChannelStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ChannelStatus>> = _statuses.asStateFlow()

    private var appRef: Application? = null
    private var gateway: HostChannelGateway? = null

    private class Running(
        val channel: XingShiChannel,
        val scope: CoroutineScope,
        val generation: Long,
        val parentJob: Job
    )

    private val running = mutableMapOf<String, Running>()
    private var generationSeq = 0L

    private class RouteEntry(
        val pluginId: String,
        val generation: Long,
        val route: ChannelReplyRoute
    )

    /** 回复路由按登记顺序排队：同一角色下多联系人/多平台交错时逐条对应，后到不覆盖先到。 */
    private val routes = mutableMapOf<Long, ArrayDeque<RouteEntry>>()

    fun bind(app: Application, gateway: HostChannelGateway?) {
        appRef = app
        if (gateway != null) this.gateway = gateway
    }

    /** 只补充应用上下文；服务/Receiver 重复初始化时不得抹掉 Application 已注入的入口。 */
    fun bindApp(app: Application) {
        if (appRef == null) appRef = app
    }

    fun statusOf(pluginId: String): ChannelStatus = _statuses.value[pluginId] ?: ChannelStatus.Unbound

    /** 是否仍有通道在连接或已连接：通用前台承载据此决定是否常驻。 */
    fun hasActiveConnection(): Boolean = _statuses.value.values.any {
        it is ChannelStatus.Connecting || it is ChannelStatus.Connected
    }

    private fun setStatus(pluginId: String, status: ChannelStatus) {
        _statuses.value = _statuses.value + (pluginId to status)
        val app = appRef ?: return
        // 只在确需前台承载时启动；停止由承载服务自身按状态流判断，避免“刚拉起就停”的竞态
        if (hasActiveConnection()) {
            runCatching { com.xingshi.ai.plugins.host.PluginChannelService.start(app) }
        }
    }

    /**
     * 冷启动/恢复：只启动仍安装且已加载出通道能力的插件。
     * [force] 为本次安装/更新涉及的插件；其它已在运行的实例保持不变，避免无关安装重启通道。
     */
    suspend fun restore(force: Set<String> = emptySet()) = mutex.withLock {
        val manager = PluginFramework.manager() ?: return@withLock
        val installed = manager.installedIds()
        // 已不存在的通道先停掉，避免卸载后复活
        for ((id, entry) in running.toList()) {
            if (id !in installed) {
                stopLocked(id)
                setStatus(id, ChannelStatus.Unbound)
            }
        }
        for (id in installed) {
            if (running.containsKey(id) && id !in force) continue
            val channel = manager.loadedPlugin(id)?.channel() ?: continue
            val current = running[id]
            if (current != null && id !in force && current.channel === channel) continue
            startLocked(channel)
        }
    }

    suspend fun start(pluginId: String) = mutex.withLock {
        val channel = PluginFramework.manager()?.loadedPlugin(pluginId)?.channel() ?: return@withLock
        if (running.containsKey(pluginId)) return@withLock
        startLocked(channel)
    }

    private suspend fun startLocked(channel: XingShiChannel) {
        val app = appRef ?: return
        stopLocked(channel.pluginId)
        val pluginId = channel.pluginId
        val dir = File(app.filesDir, "plugins/runtime/$pluginId").apply { mkdirs() }
        val parentJob = SupervisorJob()
        val channelScope = CoroutineScope(parentJob + Dispatchers.IO)
        generationSeq += 1
        val generation = generationSeq
        val active = {
            running[pluginId]?.let { it.channel === channel && it.generation == generation } == true
        }
        val host = ChannelHost(
            scope = channelScope,
            appContext = app,
            privateDir = dir,
            statusSink = { status -> if (active()) setStatus(pluginId, status) },
            inboundSink = { inbound ->
                if (!active()) {
                    ChannelInboundResult(false, reason = "通道已停止")
                } else {
                    acceptInboundWithRoute(inbound, pluginId, generation)
                }
            },
            routeSink = { companionId, route ->
                if (active()) bindRouteLocked(pluginId, generation, companionId, route)
            },
            routeRevokeSink = { accountId ->
                revokeRoutes(pluginId, generation, accountId)
            },
            isActive = active
        )
        running[pluginId] = Running(channel, channelScope, generation, parentJob)
        SecureLog.d("PluginChannels", "channel start plugin=$pluginId generation=$generation")
        // 连接状态由通道在确认凭据后自行上报：未绑定的通道不应拉起前台承载
        try {
            channel.start(host)
        } catch (e: Exception) {
            stopLocked(pluginId)
            setStatus(pluginId, ChannelStatus.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun stop(pluginId: String) = mutex.withLock { stopLocked(pluginId) }

    private suspend fun stopLocked(pluginId: String) {
        val entry = running.remove(pluginId) ?: return
        // 只删除目标插件自己的路由，再删空队列：同角色下另一插件的待回复不能被误删
        routes.entries.forEach { (_, queue) -> queue.removeAll { it.pluginId == pluginId } }
        routes.entries.removeAll { it.value.isEmpty() }
        // 先撤销资格（running 已移除、scope 取消），再等待通道自身收尾
        SecureLog.d("PluginChannels", "channel stop plugin=$pluginId generation=${entry.generation}")
        entry.parentJob.cancel()
        withContext(Dispatchers.IO) { runCatching { entry.channel.stop() } }
        setStatus(pluginId, ChannelStatus.Unbound)
    }

    suspend fun stopAll() = mutex.withLock {
        running.keys.toList().forEach { stopLocked(it) }
    }

    private fun bindRouteLocked(pluginId: String, generation: Long, companionId: Long, route: ChannelReplyRoute) {
        val queue = routes.getOrPut(companionId) { ArrayDeque() }
        // 同一登记不重复入队（平台重投/页面重开不放大队列）
        if (queue.any { it.pluginId == pluginId && it.generation == generation && it.route == route }) return
        queue.addLast(RouteEntry(pluginId, generation, route))
        while (queue.size > MAX_ROUTES_PER_COMPANION) queue.removeFirst()
    }

    private suspend fun revokeRoutes(pluginId: String, generation: Long, accountId: String?) = mutex.withLock {
        routes.entries.forEach { (_, queue) ->
            queue.removeAll { entry ->
                entry.pluginId == pluginId && entry.generation == generation &&
                    (accountId == null || entry.route.accountId == accountId)
            }
        }
        routes.entries.removeAll { it.value.isEmpty() }
    }

    /** 原请求精确回复：只交付该请求登记的路由，不按角色弹别的请求/别的插件的队首。 */
    suspend fun deliverReply(companionId: Long, request: ChannelRequestRef, text: String): ChannelSendResult? {
        val entry: RouteEntry = mutex.withLock {
            val queue = routes[companionId] ?: return@withLock null
            var found: RouteEntry? = null
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.pluginId == request.pluginId && candidate.route == request.replyRoute) {
                    iterator.remove()
                    found = candidate
                    break
                }
            }
            if (found == null) return@withLock null
            if (queue.isEmpty()) routes.remove(companionId)
            found
        } ?: return null
        return sendAtomically(companionId, entry, text)
    }

    /** Durable formal inbox handoff restores only this exact route after process restart. */
    suspend fun deliverPersistedReply(companionId: Long, request: ChannelRequestRef, text: String): ChannelSendResult? {
        val entry = mutex.withLock {
            val current = running[request.pluginId] ?: return@withLock null
            routes[companionId]?.removeAll { it.pluginId == request.pluginId && it.route == request.replyRoute }
            RouteEntry(request.pluginId, current.generation, request.replyRoute)
        } ?: return null
        return sendAtomically(companionId, entry, text)
    }

    /** Durable receipt state must precede sending; only definite non-sends may retry automatically. */
    suspend fun deliverFormalReply(companionId: Long, request: ChannelRequestRef, text: String, previousState: String,
        persist: suspend (String) -> Unit): String {
        if (previousState == "DONE") return "DONE"
        if (previousState in setOf("EXTERNAL_UNKNOWN", "SENDING_EXTERNAL")) {
            persist("EXTERNAL_UNKNOWN")
            return "EXTERNAL_UNKNOWN"
        }
        persist("SENDING_EXTERNAL")
        val next = when (deliverPersistedReply(companionId, request, text)) {
            ChannelSendResult.Sent -> "DONE"
            is ChannelSendResult.Failed -> "EXTERNAL_FAILED"
            is ChannelSendResult.Unknown -> "EXTERNAL_UNKNOWN"
            null -> "EXTERNAL_PENDING"
        }
        persist(next)
        return next
    }

    /** 兼容旧通道：宿主只拿到角色时按登记顺序取最早路由，无路由返回 null。 */
    suspend fun deliverReply(companionId: Long, text: String): ChannelSendResult? {
        val entry: RouteEntry = mutex.withLock {
            val queue = routes[companionId] ?: return@withLock null
            if (queue.isEmpty()) return@withLock null
            val found = queue.removeFirst()
            if (queue.isEmpty()) routes.remove(companionId)
            found
        } ?: return null
        return sendAtomically(companionId, entry, text)
    }

    /**
     * 实际发送与撤销共用同一临界区：先校验本次执行代次，再把“接受发送”与平台调用放在同一互斥边界内。
     * stop 先完成时，调度恢复后的发送会因代次失效而不再调用平台；已完成接受的发送由 stop 等待其收尾。
     */
    private suspend fun sendAtomically(
        companionId: Long,
        entry: RouteEntry,
        text: String
    ): ChannelSendResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val runningEntry = running[entry.pluginId]
            if (runningEntry == null || runningEntry.generation != entry.generation) {
                return@withLock null
            }
            runCatching {
                runningEntry.channel.sendOutbound(ChannelOutbound(companionId, entry.route, text))
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                ChannelSendResult.Unknown("平台发送结果未知")
            }
        }
    }

    /** 本地聊天产生的回复不占外部通道路由；仅供宿主判断是否存在待回流外部回复。 */
    fun hasPendingReplyRoute(companionId: Long): Boolean {
        val queue = routes[companionId]
        return queue != null && queue.isNotEmpty()
    }

    private suspend fun acceptInboundWithRoute(
        inbound: ChannelInbound,
        pluginId: String,
        generation: Long
    ): ChannelInboundResult {
        // 原请求的回复资格必须与接受同生命周期：入站携带请求时先登记本代次路由，
        // 宿主拒绝或接受异常时收回，避免只改测试手工登记才能回复。
        val companionId = inbound.companionId
        val boundRequest = inbound.request?.takeIf { it.pluginId == pluginId && companionId != null }
        if (boundRequest != null && companionId != null) {
            mutex.withLock { bindRouteLocked(pluginId, generation, companionId, boundRequest.replyRoute) }
        }
        val result = try {
            acceptInbound(inbound)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (boundRequest != null && companionId != null) {
                    mutex.withLock { removeRouteLocked(pluginId, generation, companionId, boundRequest.replyRoute) }
                }
            }
            if (currentCoroutineContext().isActive) {
                return ChannelInboundResult(false, reason = e.message ?: "接收已取消")
            }
            throw e
        } catch (e: Exception) {
            if (boundRequest != null && companionId != null) {
                mutex.withLock { removeRouteLocked(pluginId, generation, companionId, boundRequest.replyRoute) }
            }
            return ChannelInboundResult(false, reason = e.message ?: e.javaClass.simpleName)
        }
        if (!result.accepted && boundRequest != null && companionId != null) {
            mutex.withLock { removeRouteLocked(pluginId, generation, companionId, boundRequest.replyRoute) }
        }
        return result
    }

    private suspend fun acceptInbound(inbound: ChannelInbound): ChannelInboundResult {
        val sink = gateway ?: return ChannelInboundResult(false, reason = "宿主对话入口未接入")
        return sink.accept(inbound)
    }

    private fun removeRouteLocked(pluginId: String, generation: Long, companionId: Long, route: ChannelReplyRoute) {
        val queue = routes[companionId] ?: return
        queue.removeAll { it.pluginId == pluginId && it.generation == generation && it.route == route }
        if (queue.isEmpty()) routes.remove(companionId)
    }

    private const val MAX_ROUTES_PER_COMPANION = 100
}
