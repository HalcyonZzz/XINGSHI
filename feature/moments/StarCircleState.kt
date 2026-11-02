package com.xingshi.ai.feature.moments

import com.xingshi.ai.domain.CirclePerson
import com.xingshi.ai.domain.CirclePost
import com.xingshi.ai.domain.StarCircleGateway
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CircleError { UNAVAILABLE, REQUEST_FAILED }

data class CircleCommentTarget(val postId: String, val commentId: String?, val name: String)

data class StarCircleState(
    val viewer: CirclePerson? = null,
    val posts: List<CirclePost> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val busyPosts: Set<String> = emptySet(),
    val error: CircleError? = null,
    val commentTarget: CircleCommentTarget? = null,
    val draft: String = "",
    val sendingComment: Boolean = false
)

class StarCircleStore(
    private val scope: CoroutineScope,
    private val gateway: () -> StarCircleGateway?
) {
    private val mutableState = MutableStateFlow(StarCircleState())
    val state = mutableState.asStateFlow()
    private val requests = Mutex()
    private data class CommentOperation(val target: CircleCommentTarget, val text: String, val id: String)
    private var pendingComment: CommentOperation? = null
    private val pendingLikes = mutableMapOf<String, Pair<Boolean, String>>()

    fun refresh() = readPage(append = false)

    fun loadMore() {
        if (state.value.nextCursor != null) readPage(append = true)
    }

    private fun readPage(append: Boolean) {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                requests.withLock {
                    val service = gateway() ?: throw Unavailable()
                    val cursor = if (append) state.value.nextCursor ?: return@withLock else null
                    val page = service.readFeed(cursor)
                    mutableState.update { current ->
                        val posts = if (append) {
                            (current.posts + page.posts).associateBy { it.id }.values.toList()
                        } else page.posts.distinctBy { it.id }
                        current.copy(
                            viewer = page.viewer, posts = posts, nextCursor = page.nextCursor,
                            loaded = true, error = if (page.pendingOperationFailed) CircleError.REQUEST_FAILED else null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure)
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun toggleLike(postId: String) {
        val current = state.value
        val viewer = current.viewer ?: return
        val post = current.posts.firstOrNull { it.id == postId }
        if (post == null) {
            mutableState.update { it.copy(error = CircleError.REQUEST_FAILED) }
            return
        }
        if (postId in current.busyPosts) return
        val desired = !(post.likedByMe ?: post.likes.any { it.id == viewer.id })
        val operation = pendingLikes[postId]?.takeIf { it.first == desired }
            ?: (desired to UUID.randomUUID().toString())
        pendingLikes[postId] = operation
        mutableState.update { it.copy(busyPosts = it.busyPosts + postId, error = null) }
        scope.launch {
            try {
                requests.withLock {
                    val updated = (gateway() ?: throw Unavailable()).setLike(postId, desired, operation.second)
                    replacePost(postId, updated)
                    pendingLikes.remove(postId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure)
            } finally {
                mutableState.update { it.copy(busyPosts = it.busyPosts - postId) }
            }
        }
    }

    fun beginComment(postId: String, commentId: String? = null) {
        val current = state.value
        if (current.sendingComment) return
        val post = current.posts.firstOrNull { it.id == postId }
        if (post == null) {
            mutableState.update { it.copy(error = CircleError.REQUEST_FAILED) }
            return
        }
        val comment = commentId?.let { id -> post.comments.firstOrNull { it.id == id } }
        if (commentId != null && comment == null) {
            mutableState.update { it.copy(error = CircleError.REQUEST_FAILED) }
            return
        }
        val target = CircleCommentTarget(postId, commentId, comment?.author?.name ?: post.author.name)
        mutableState.update {
            val sameTarget = it.commentTarget?.postId == target.postId && it.commentTarget?.commentId == target.commentId
            it.copy(commentTarget = target, draft = if (sameTarget) it.draft else "")
        }
    }

    fun editDraft(text: String) {
        if (!state.value.sendingComment) mutableState.update { it.copy(draft = text) }
    }

    fun closeComment() {
        if (!state.value.sendingComment) mutableState.update { it.copy(commentTarget = null, draft = "") }
    }

    fun sendComment() {
        val current = state.value
        val target = current.commentTarget ?: return
        val text = current.draft.trim()
        if (text.isEmpty() || current.sendingComment) return
        val operation = pendingComment?.takeIf {
            it.target.postId == target.postId && it.target.commentId == target.commentId && it.text == text
        }
            ?: CommentOperation(target, text, UUID.randomUUID().toString())
        pendingComment = operation
        mutableState.update { it.copy(sendingComment = true, error = null) }
        scope.launch {
            try {
                requests.withLock {
                    val updated = (gateway() ?: throw Unavailable()).comment(
                        target.postId, text, target.commentId, operation.id
                    )
                    replacePost(target.postId, updated)
                    pendingComment = null
                    mutableState.update { it.copy(commentTarget = null, draft = "") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure)
            } finally {
                mutableState.update { it.copy(sendingComment = false) }
            }
        }
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private fun replacePost(postId: String, post: CirclePost) {
        check(post.id == postId)
        mutableState.update { current ->
            current.copy(posts = current.posts.map { if (it.id == postId) post else it })
        }
    }

    private fun failed(failure: Exception) {
        mutableState.update {
            it.copy(error = if (failure is Unavailable) CircleError.UNAVAILABLE else CircleError.REQUEST_FAILED)
        }
    }

    private class Unavailable : IllegalStateException()
}
