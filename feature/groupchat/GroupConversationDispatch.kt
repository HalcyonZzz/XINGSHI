package com.xingshi.ai.feature.groupchat

import com.xingshi.ai.domain.GroupConversationGateway
import com.xingshi.ai.domain.GroupConversationSnapshot
import kotlinx.coroutines.CancellationException

data class GroupMentions(val companionIds: Set<Long> = emptySet(), val everyone: Boolean = false) {
    fun targets(memberIds: List<Long>): List<Long> =
        memberIds.distinct().filter { everyone || it in companionIds }
}

suspend fun dispatchGroupConversation(
    gateway: GroupConversationGateway,
    snapshot: GroupConversationSnapshot,
    sourceMessageId: Long,
    mentions: GroupMentions,
     
    syncSinceMessageId: Long = 0L
): List<Long> {
    require(snapshot.messages.any { it.id == sourceMessageId })
    val visibleSnapshot = if (syncSinceMessageId > 0L) {
        snapshot.copy(messages = snapshot.messages.filter { it.id > syncSinceMessageId })
    } else {
        snapshot
    }
    val frozen = visibleSnapshot.copy(messages = visibleSnapshot.messages.map { message ->
        if (message.id == sourceMessageId) message.copy(mentionedCompanionIds = mentions.targets(snapshot.members.map { it.companionId })) else message
    })
    gateway.synchronize(frozen)
    val failed = mutableListOf<Long>()
    for (target in mentions.targets(snapshot.members.map { it.companionId })) {
        try {
            gateway.requestReply(
                snapshot.groupId, sourceMessageId, target,
                "group:${snapshot.groupId}:message:$sourceMessageId:reply:$target"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed += target
        }
    }
    return failed
}
