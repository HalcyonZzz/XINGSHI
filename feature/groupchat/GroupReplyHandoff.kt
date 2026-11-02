package com.xingshi.ai.feature.groupchat

import com.xingshi.ai.domain.GroupReplyChunk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 群回复的正式接收入口：可控服务按原群/原消息/原实例/operationId 推入分片，
 * 应用级接收器负责落库与去重，页面只按群读取数据库。
 */
object GroupReplyHandoff {

    private val _events = MutableSharedFlow<GroupReplyChunk>(replay = 16, extraBufferCapacity = 64)
    val events: SharedFlow<GroupReplyChunk> = _events.asSharedFlow()

    fun publish(chunk: GroupReplyChunk) {
        _events.tryEmit(chunk)
    }

    /** 等待同一 operationId 的终结正文；不跨群/跨消息复用。 */
    suspend fun awaitTerminal(
        groupId: Long,
        sourceMessageId: Long,
        targetCompanionId: Long,
        operationId: String,
        timeoutMs: Long
    ): String? = withTimeoutOrNull(timeoutMs) {
        val builder = StringBuilder()
        events
            .filter {
                it.groupId == groupId && it.sourceMessageId == sourceMessageId &&
                    it.targetCompanionId == targetCompanionId && it.operationId == operationId
            }
            .map { chunk ->
                builder.append(chunk.text)
                if (chunk.terminal) builder.toString().trim() else ""
            }
            .first { it.isNotEmpty() }
    }
}
