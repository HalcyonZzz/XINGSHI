package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通话轮次的正式结果交接：正式终结链落库后，把「同一 call_id / 原请求 / 已存消息」
 * 交回通话控制器；控制器只消费，不再二次落库，也不依赖 Debug 流拼正文。
 */
object CallTurnHandoff {

    data class Reply(
        val callId: String,
        val companionId: Long,
        val text: String,
        val messageId: Long,
        val request: ChannelRequestRef? = null,
        /** 本轮原输入落库 id：正式答复必须与它匹配，不能用回复 id 大小猜测轮次。 */
        val inputMessageId: Long = 0L
    )

    private val pending = mutableListOf<Reply>()
    private val lock = Any()
    /** 发布序号：等待方用 StateFlow 变更唤醒，避免轮询和漏掉发布。 */
    private val signal = MutableStateFlow(0L)

    // 已消费只按 Reply 身份记录，防止同一正式回复被下一轮再次取走。
    private val consumed = java.util.Collections.synchronizedSet(mutableSetOf<Reply>())

    fun publish(reply: Reply) {
        synchronized(lock) {
            pending.add(reply)
            if (pending.size > 64) pending.removeAt(0)
        }
        signal.value = signal.value + 1
    }

    // 首轮超时后，零身份旧探针的迟到结果只允许在出现更新的第二条候选时被采用；
    // 产品正式路径使用 inputMessageId 精确匹配，不依赖该兼容分支。
    private val timedOutAnonymousCalls = mutableSetOf<String>()

    /**
     * 等待本通话、本轮原输入对应的正式结果；inputMessageId 为 0 时进入兼容等待。
     * 同时到达多条时取消息 id 最新的一条，但必须属于同一输入轮次。
     */
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
                    // 没有任何超时历史：按原行为取最新；已超时过：只有第二条才可能是本轮结果。
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
