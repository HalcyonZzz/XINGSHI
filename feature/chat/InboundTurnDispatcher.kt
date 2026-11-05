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

object InboundTurnDispatcher {

    private const val FALLBACK_REPLY_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageHandlers = ConcurrentHashMap<Long, (ConversationIntake.Turn) -> Unit>()
    private val pageOwners = ConcurrentHashMap<Long, Any>()
    private val ownerLock = Any()
    private val LEGACY_OWNER = Any()
    private val deferredForPage = ConcurrentHashMap<Long, ArrayDeque<ConversationIntake.Turn>>()
    private val consumers = ConcurrentHashMap<Long, Job>()
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
                fallbackBuffers[fragment.companionId]?.trySend(fragment)
            }
        }
    }

    fun registerPage(companionId: Long, owner: Any, handler: (ConversationIntake.Turn) -> Unit) {
        val deferred = synchronized(ownerLock) {
            val current = pageOwners[companionId]
            if (current != null && current !== owner) {
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

    fun unregisterPage(companionId: Long, owner: Any) {
        synchronized(ownerLock) {
            if (pageOwners[companionId] === owner) {
                pageOwners.remove(companionId)
                pageHandlers.remove(companionId)
            }
        }
    }

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

    internal fun pageOwnerOf(companionId: Long): Any? = pageOwners[companionId]

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

    private suspend fun handleWithoutPage(turn: ConversationIntake.Turn) {
        val application = app ?: return
        val request: ChannelRequestRef = turn.request ?: return
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
