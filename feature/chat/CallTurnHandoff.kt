package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object CallTurnHandoff {

    data class Reply(
        val callId: String,
        val companionId: Long,
        val text: String,
        val messageId: Long,
        val request: ChannelRequestRef? = null,
        val inputMessageId: Long = 0L
    )

    private val pending = mutableListOf<Reply>()
    private val lock = Any()
    private val signal = MutableStateFlow(0L)

    private val consumed = java.util.Collections.synchronizedSet(mutableSetOf<Reply>())

    fun publish(reply: Reply) {
        synchronized(lock) {
            pending.add(reply)
            if (pending.size > 64) pending.removeAt(0)
        }
        signal.value = signal.value + 1
    }

    private val timedOutAnonymousCalls = mutableSetOf<String>()

    suspend fun awaitReply(
        callId: String,
        companionId: Long,
        timeoutMs: Long,
        inputMessageId: Long = 0L
    ): Reply? {
        val reply = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val seen = signal.value
                val candidates = synchronized(lock) {
                    pending.filter { reply ->
                        reply.callId == callId && reply.companionId == companionId && reply.text.isNotBlank() &&
                            synchronized(consumed) { reply !in consumed } &&
                            (inputMessageId <= 0L || reply.inputMessageId == inputMessageId)
                    }
                }
                val match = if (inputMessageId > 0L) {
                    candidates.maxByOrNull { it.messageId }
                } else {
                    val anonymousTimedOut = synchronized(timedOutAnonymousCalls) {
                        "$callId@$companionId" in timedOutAnonymousCalls
                    }
                    if (!anonymousTimedOut || candidates.size >= 2) candidates.maxByOrNull { it.messageId } else null
                }
                if (match != null) {
                    synchronized(lock) { pending.remove(match) }
                    synchronized(consumed) {
                        consumed.add(match)
                        if (consumed.size > 512) consumed.remove(consumed.first())
                    }
                    return@withTimeoutOrNull match
                }
                signal.first { it != seen }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (reply == null && inputMessageId <= 0L) {
            synchronized(timedOutAnonymousCalls) { timedOutAnonymousCalls.add("$callId@$companionId") }
        }
        return reply
    }

    suspend fun await(
        callId: String,
        companionId: Long,
        timeoutMs: Long,
        inputMessageId: Long = 0L
    ): String? = awaitReply(callId, companionId, timeoutMs, inputMessageId)?.text
}
