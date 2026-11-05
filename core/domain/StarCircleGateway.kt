package com.xingshi.ai.domain

data class CirclePerson(
    val id: String,
    val name: String,
    val avatar: String? = null
)

data class CircleComment(
    val id: String,
    val author: CirclePerson,
    val text: String,
    val createdAt: Long,
    val replyToCommentId: String? = null,
    val replyTo: CirclePerson? = null
)

enum class CircleAttachmentKind { IMAGE, VIDEO }

data class CircleAttachment(
    val kind: CircleAttachmentKind,
    val fileName: String,
    val text: String = ""
)

data class CirclePost(
    val id: String,
    val author: CirclePerson,
    val text: String,
    val createdAt: Long,
    val likes: List<CirclePerson> = emptyList(),
    val comments: List<CircleComment> = emptyList(),
    val likedByMe: Boolean? = null,
    val attachments: List<CircleAttachment> = emptyList()
)

data class CircleFeed(
    val viewer: CirclePerson,
    val posts: List<CirclePost>,
    val nextCursor: String? = null,
    val pendingOperationFailed: Boolean = false
)

data class CirclePublishMedia(
    val id: String,
    val kind: CircleAttachmentKind,
    val fileName: String,
    val mimeType: String,
    val path: String,
    val text: String = ""
)

data class CirclePublishAttachment(
    val kind: CircleAttachmentKind,
    val fileName: String,
    val text: String
)

data class CirclePublishRequest(
    val operationId: String,
    val body: String,
    val attachments: List<CirclePublishAttachment>
)

enum class CirclePublishState { PREPARING, READY }

data class CirclePublishRecord(
    val operationId: String,
    val accountId: String,
    val body: String,
    val media: List<CirclePublishMedia> = emptyList(),
    val state: CirclePublishState = CirclePublishState.PREPARING,
    val request: CirclePublishRequest? = null,
    val failure: String = ""
)

enum class CirclePublishFailure { REJECTED, CONFLICT, UNCONFIRMED }

sealed interface CirclePublishOutcome {
    data class Published(val post: CirclePost) : CirclePublishOutcome
    data class Failed(val failure: CirclePublishFailure) : CirclePublishOutcome
}

class CirclePublishConflict(message: String = "星圈发布内容与既有操作不一致") : IllegalStateException(message)

interface StarCircleGateway {
    suspend fun readFeed(cursor: String? = null): CircleFeed

    suspend fun setLike(postId: String, liked: Boolean, operationId: String): CirclePost

    suspend fun comment(
        postId: String,
        text: String,
        replyToCommentId: String?,
        operationId: String
    ): CirclePost

    suspend fun publish(record: CirclePublishRecord): CirclePublishOutcome =
        throw UnsupportedOperationException("星圈发布尚未接入")

    suspend fun pendingPublishes(): List<CirclePublishRecord> = emptyList()

    suspend fun savePublish(record: CirclePublishRecord) {
        throw UnsupportedOperationException("星圈发布尚未接入")
    }

    suspend fun completePublish(operationId: String) {
        throw UnsupportedOperationException("星圈发布尚未接入")
    }
}
