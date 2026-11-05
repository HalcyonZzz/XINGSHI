package com.xingshi.ai.feature.groupchat

import com.xingshi.ai.domain.GroupReplyChunk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

object GroupReplyHandoff {

    private val _events = MutableSharedFlow<GroupReplyChunk>(replay = 16, extraBufferCapacity = 64)
    val events: SharedFlow<GroupReplyChunk> = _events.asSharedFlow()

    fun publish(chunk: GroupReplyChunk) {
        _events.tryEmit(chunk)
    }

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
