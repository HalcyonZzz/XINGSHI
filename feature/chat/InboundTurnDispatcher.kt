package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.common.SecureLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 入站对话轮次的唯一消费者（应用级）。
 *
 * 以前只有 [ChatViewModel.startMessageConsumer] 消费 [ConversationIntake] 队列，通道入站
 * 在「未打开聊天页」时只完成接受/落库/入队，没有消费者也不会回传。现在消费者固定在应用
 * 作用域：每个角色一条消费循环，页面只登记处理函数，打开/离开页面不新增第二消费者。
 *
 * 页面未登记时走应用级回退：用与聊天页相同的 [AiResponseFinalizer] 落库并交回原请求，
 * 不新建队列或模型链。当前构建无模型，回退回复由 [ChatVerificationBus] 的可控片段驱动。
 */
object InboundTurnDispatcher {

    private const val FALLBACK_REPLY_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageHandlers = ConcurrentHashMap<Long, (ConversationIntake.Turn) -> Unit>()
    /** 处理函数的登记者身份：注销只撤销自身持有的登记，详情页不能删除聊天页的 handler。 */
    private val pageOwners = ConcurrentHashMap<Long, Any>()
    private val ownerLock = Any()
    private val LEGACY_OWNER = Any()
    /** 页面关闭期间到达、又没有外部请求可回传的轮次：留给下一次打开页面，不丢也不阻塞通道。 */
    private val deferredForPage = ConcurrentHashMap<Long, ArrayDeque<ConversationIntake.Turn>>()
    private val consumers = ConcurrentHashMap<Long, Job>()
    /** 仅无页面回退轮次等待可控回复期间存在；页面路径不经过这里，旧片段不会留给后续请求。 */
    private val fallbackBuffers = ConcurrentHashMap<Long, Channel<ChatVerificationBus.Fragment>>()
    private var app: Application? = null

    fun stopAccount() {
        consumers.values.forEach { it.cancel() }; consumers.clear()
        synchronized(ownerLock) { pageHandlers.clear(); pageOwners.clear(); deferredForPage.clear(); legacyDepth = 0 }
        fallbackBuffers.values.forEach { it.close() }; fallbackBuffers.clear()
    }

    fun init(application: Application) {
        app = application
        scope.launch {
            ChatVerificationBus.events.collect { fragment ->
                // 只有正在等待回退轮次时才收片段；页面路径自己消费片段，不在此留旧缓冲。
                fallbackBuffers[fragment.companionId]?.trySend(fragment)
            }
        }
    }

    /**
     * 页面打开时登记处理函数；队列消费仍由这里唯一持有。
     * owner 是登记者身份（通常为 ViewModel 实例）：已有其它 owner 的登记时不能抢占。
     */
    fun registerPage(companionId: Long, owner: Any, handler: (ConversationIntake.Turn) -> Unit) {
        val deferred = synchronized(ownerLock) {
            val current = pageOwners[companionId]
            if (current != null && current !== owner) {
                // 详情页等非所有者不能覆盖正在使用的聊天 handler
                null
            } else {
                pageOwners[companionId] = owner
                pageHandlers[companionId] = handler
                deferredForPage.remove(companionId)
            }
        }
        deferred?.let { queue ->
            while (queue.isNotEmpty()) handler(queue.removeFirst())
        }
        ensureConsuming(companionId)
    }

    /** 只撤销 owner 自己持有的登记；详情页/其它页面不能删除聊天页的 handler。 */
    fun unregisterPage(companionId: Long, owner: Any) {
        synchronized(ownerLock) {
            if (pageOwners[companionId] === owner) {
                pageOwners.remove(companionId)
                pageHandlers.remove(companionId)
            }
        }
    }

    // ── 兼容旧调用方（历史页面/验收探针）的无 owner 重载 ──
    // 旧调用没有身份可用：首次登记生效，嵌套页面的再次登记不替换；每登记一次配合一次注销，
    // 只有嵌套全部退出后才真正释放，返回聊天后原 handler 仍在。

    private var legacyDepth = 0

    fun registerPage(companionId: Long, handler: (ConversationIntake.Turn) -> Unit) {
        synchronized(ownerLock) {
            when {
                pageHandlers[companionId] == null -> {
                    pageOwners[companionId] = LEGACY_OWNER
                    pageHandlers[companionId] = handler
                    legacyDepth = 1
                }
                pageOwners[companionId] === LEGACY_OWNER -> legacyDepth += 1
            }
        }
        ensureConsuming(companionId)
    }

    fun unregisterPage(companionId: Long) {
        synchronized(ownerLock) {
            if (pageOwners[companionId] === LEGACY_OWNER) {
                legacyDepth -= 1
                if (legacyDepth <= 0) {
                    pageOwners.remove(companionId)
                    pageHandlers.remove(companionId)
                }
            }
        }
    }

    /** 测试与诊断：当前页面处理函数所属 owner；无登记返回 null。 */
    internal fun pageOwnerOf(companionId: Long): Any? = pageOwners[companionId]

    /**
     * 有入队就确保消费循环存在。未初始化（例如 JVM 单测）时保持纯队列行为。
     */
    fun ensureConsuming(companionId: Long) {
        if (app == null) return
        consumers.computeIfAbsent(companionId) {
            scope.launch {
                consume(companionId)
            }
        }
    }

    private suspend fun consume(companionId: Long) {
        try {
            while (true) {
                val received = ConversationIntake.receive(companionId).receiveCatching()
                if (received.isClosed) return
                val turn = received.getOrNull() ?: continue
                ConversationIntake.consumed(companionId)
                try {
                    turn.awaitUserMessageId()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.e("InboundTurnDispatcher", "turn identity failed companion=$companionId", e)
                    continue
                }
                val pageHandler = pageHandlers[companionId]
                val backend = com.xingshi.ai.domain.ServiceRegistry.get(BackendChatConsumer::class.java)
                if (backend != null) {
                    backend.accept(turn)
                } else if (pageHandler != null) {
                    pageHandler(turn)
                } else if (turn.request != null) {
                    handleWithoutPage(turn)
                } else {
                    // 无页面且无外部请求：暂存在内存里等下一次打开页面，既不丢也不阻塞通道回复。
                    deferredForPage.getOrPut(companionId) { ArrayDeque() }.addLast(turn)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e("InboundTurnDispatcher", "consumer crashed companion=$companionId", e)
        } finally {
            val self = kotlin.coroutines.coroutineContext[Job]
            consumers.computeIfPresent(companionId) { _, current ->
                if (current == self) null else current
            }
        }
    }

    /** 页面未打开：有原请求的通道轮次用可控回复落库并交回原联系人。 */
    private suspend fun handleWithoutPage(turn: ConversationIntake.Turn) {
        val application = app ?: return
        // 本地/插件入队没有外部请求，不需要外部回复；落库已由接受边界完成。
        val request: ChannelRequestRef = turn.request ?: return
        // 每个回退轮次独立收片段，本轮结束即丢弃，片段不会跨请求复用。
        val buffer = Channel<ChatVerificationBus.Fragment>(capacity = 16)
        fallbackBuffers[turn.companionId] = buffer
        val reply = try {
            awaitControlledReply(turn, buffer)
        } finally {
            fallbackBuffers.remove(turn.companionId, buffer)
        } ?: run {
            SecureLog.d("InboundTurnDispatcher", "no controlled reply for companion=${turn.companionId}")
            return
        }
        runCatching {
            val database = AppDatabase.getDatabase(application)
            val finalizer = AiResponseFinalizer(
                companionId = turn.companionId,
                chatRepository = ChatRepository(database.chatMessageDao()),
                appSettingsStore = AppSettingsStore(application),
                reasoningText = MutableStateFlow(""),
                isReasoning = MutableStateFlow(false),
                application = application
            )
            finalizer.finalizeResponse(
                request = request,
                aiContent = reply,
                reasoning = null,
                logMessage = "app consumer reply",
                inputMessageId = turn.userMessageId
            )
        }.onFailure {
            SecureLog.e("InboundTurnDispatcher", "fallback reply failed", it)
        }
    }

    private suspend fun awaitControlledReply(
        turn: ConversationIntake.Turn,
        buffer: Channel<ChatVerificationBus.Fragment>
    ): String? {
        val builder = StringBuilder()
        return withTimeoutOrNull(FALLBACK_REPLY_TIMEOUT_MS) {
            while (true) {
                val fragment = buffer.receive()
                if (fragment.companionId != turn.companionId) continue
                if (fragment.inputMessageId > 0L && fragment.inputMessageId != turn.userMessageId) continue
                if (fragment.request != null && turn.request != null && fragment.request != turn.request) continue
                builder.append(fragment.text)
                if (fragment.terminal) return@withTimeoutOrNull builder.toString()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }
}
