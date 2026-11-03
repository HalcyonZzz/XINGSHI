package com.xingshi.ai.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class GroupParticipant(
    val companionId: Long,
    val name: String,
    val avatar: String? = null
)

data class GroupHistoryMessage(
    val id: Long,
    val companionId: Long?,
    val speakerName: String,
    val text: String,
    val occurredAt: Long,
    val mediaType: String = "text",
    val mediaPath: String = "",
    val mentionedCompanionIds: List<Long> = emptyList(),
    val live: Boolean = false,
    val authorStableId: String? = null,
    val authorInstanceId: String? = null
)

data class GroupConversationSnapshot(
    val groupId: Long,
    val groupName: String,
    val createdAt: Long,
    val members: List<GroupParticipant>,
    val messages: List<GroupHistoryMessage>,
     
    val sourceMessageId: Long? = null
)

 
data class GroupSyncProgress(
    val groupId: Long,
    val knownThroughMessageId: Long,
    val complete: Boolean
)

enum class GroupMemberEventType { JOIN, LEAVE }

 
data class GroupMemberEvent(
    val groupId: Long,
    val companionId: Long,
    val type: GroupMemberEventType,
    val occurredAt: Long,
     
    val operationId: String = "",
     
    val members: List<GroupParticipant> = emptyList()
)

 
data class GroupReplyChunk(
    val groupId: Long,
    val sourceMessageId: Long,
    val targetCompanionId: Long,
    val operationId: String,
    val text: String,
    val terminal: Boolean
)

interface GroupConversationGateway {
     
    fun stage(snapshot: GroupConversationSnapshot) = Unit
    suspend fun synchronize(snapshot: GroupConversationSnapshot)

    suspend fun requestReply(
        groupId: Long,
        sourceMessageId: Long,
        companionId: Long,
        operationId: String
    )

     
    suspend fun synchronizeBeforePrivateChat(companionId: Long) = Unit

     
    suspend fun synchronizeDelta(groupId: Long, knownThroughMessageId: Long): GroupSyncProgress? = null

     
    suspend fun confirmSyncProgress(groupId: Long, throughMessageId: Long) = Unit

     
    suspend fun memberEvents(groupId: Long): List<GroupMemberEvent> = emptyList()

     
    val replyEvents: Flow<GroupReplyChunk> get() = emptyFlow()
}

interface FormalGroupDeliverySink {
    suspend fun save(groupId: Long, companionId: Long, messageId: String, content: String, createdAt: Long, payload: String): Long
}
