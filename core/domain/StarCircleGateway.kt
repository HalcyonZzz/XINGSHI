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

data class CirclePost(
    val id: String,
    val author: CirclePerson,
    val text: String,
    val createdAt: Long,
    val likes: List<CirclePerson> = emptyList(),
    val comments: List<CircleComment> = emptyList(),
    val likedByMe: Boolean? = null
)

data class CircleFeed(
    val viewer: CirclePerson,
    val posts: List<CirclePost>,
    val nextCursor: String? = null,
    val pendingOperationFailed: Boolean = false
)

interface StarCircleGateway {
    suspend fun readFeed(cursor: String? = null): CircleFeed

    suspend fun setLike(postId: String, liked: Boolean, operationId: String): CirclePost

    suspend fun comment(
        postId: String,
        text: String,
        replyToCommentId: String?,
        operationId: String
    ): CirclePost
}
