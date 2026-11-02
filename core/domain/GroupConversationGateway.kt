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
    /** 本次已落库的原输入；重启/失败补交仍保留同一身份，不把旧群史视作新输入。 */
    val sourceMessageId: Long? = null
)

/** 群增量同步确认：已对齐到的本地消息 id，页面据此确认进度而不是假定全量已完成。 */
data class GroupSyncProgress(
    val groupId: Long,
    val knownThroughMessageId: Long,
    val complete: Boolean
)

enum class GroupMemberEventType { JOIN, LEAVE }

/** 建群/后入群/离群事件；只携带生效快照所需最小身份。 */
data class GroupMemberEvent(
    val groupId: Long,
    val companionId: Long,
    val type: GroupMemberEventType,
    val occurredAt: Long,
    /** 稳定操作身份：重放/重复事件按此去重。 */
    val operationId: String = "",
    /** 事件生效时的成员快照；为空时调用方按当前群成员处理。 */
    val members: List<GroupParticipant> = emptyList()
)

/** 群回复流式分片；operationId 与请求一致，terminal 表示正式终结。 */
data class GroupReplyChunk(
    val groupId: Long,
    val sourceMessageId: Long,
    val targetCompanionId: Long,
    val operationId: String,
    val text: String,
    val terminal: Boolean
)

interface GroupConversationGateway {
    /** Freeze the already persisted source and mention set before any incremental network work. */
    fun stage(snapshot: GroupConversationSnapshot) = Unit
    suspend fun synchronize(snapshot: GroupConversationSnapshot)

    suspend fun requestReply(
        groupId: Long,
        sourceMessageId: Long,
        companionId: Long,
        operationId: String
    )

    /** 私聊/换群前补齐该实例合法群增量；默认无操作以保持旧网关二进制兼容。 */
    suspend fun synchronizeBeforePrivateChat(companionId: Long) = Unit

    /** 只补已知边界之后的增量；旧实现可忽略，返回 null 表示未提供进度。 */
    suspend fun synchronizeDelta(groupId: Long, knownThroughMessageId: Long): GroupSyncProgress? = null

    /** 确认消费进度，避免未经授权历史被当作默认全量同步内容。 */
    suspend fun confirmSyncProgress(groupId: Long, throughMessageId: Long) = Unit

    /** 建群/后入群事件；旧实现默认无事件。 */
    suspend fun memberEvents(groupId: Long): List<GroupMemberEvent> = emptyList()

    /** 群回复接收；旧实现默认没有接收流。 */
    val replyEvents: Flow<GroupReplyChunk> get() = emptyFlow()
}

interface FormalGroupDeliverySink {
    suspend fun save(groupId: Long, companionId: Long, messageId: String, content: String, createdAt: Long, payload: String): Long
}
