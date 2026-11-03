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

     
    private val routes = mutableMapOf<Long, ArrayDeque<RouteEntry>>()

    fun bind(app: Application, gateway: HostChannelGateway?) {
        appRef = app
        if (gateway != null) this.gateway = gateway
    }

     
    fun bindApp(app: Application) {
        if (appRef == null) appRef = app
    }

    fun statusOf(pluginId: String): ChannelStatus = _statuses.value[pluginId] ?: ChannelStatus.Unbound

     
    fun hasActiveConnection(): Boolean = _statuses.value.values.any {
        it is ChannelStatus.Connecting || it is ChannelStatus.Connected
    }

    private fun setStatus(pluginId: String, status: ChannelStatus) {
        _statuses.value = _statuses.value + (pluginId to status)
        val app = appRef ?: return
        
        if (hasActiveConnection()) {
            runCatching { com.xingshi.ai.plugins.host.PluginChannelService.start(app) }
        }
    }

     
    suspend fun restore(force: Set<String> = emptySet()) = mutex.withLock {
        val manager = PluginFramework.manager() ?: return@withLock
        val installed = manager.installedIds()
        
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
        
        routes.entries.forEach { (_, queue) -> queue.removeAll { it.pluginId == pluginId } }
        routes.entries.removeAll { it.value.isEmpty() }
        
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

     
    suspend fun deliverPersistedReply(companionId: Long, request: ChannelRequestRef, text: String): ChannelSendResult? {
        val entry = mutex.withLock {
            val current = running[request.pluginId] ?: return@withLock null
            routes[companionId]?.removeAll { it.pluginId == request.pluginId && it.route == request.replyRoute }
            RouteEntry(request.pluginId, current.generation, request.replyRoute)
        } ?: return null
        return sendAtomically(companionId, entry, text)
    }

     
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

     
    fun hasPendingReplyRoute(companionId: Long): Boolean {
        val queue = routes[companionId]
        return queue != null && queue.isNotEmpty()
    }

    private suspend fun acceptInboundWithRoute(
        inbound: ChannelInbound,
        pluginId: String,
        generation: Long
    ): ChannelInboundResult {
        
        
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
