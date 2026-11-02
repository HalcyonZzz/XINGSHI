package com.xingshi.ai.feature.moments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.domain.CircleComment
import com.xingshi.ai.domain.CirclePerson
import com.xingshi.ai.domain.CirclePost
import com.xingshi.ai.feature.moments.CircleError
import com.xingshi.ai.feature.moments.StarCircleState
import com.xingshi.ai.feature.moments.StarCircleStore
import com.xingshi.ai.feature.moments.StarCircleViewModel
import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.themeText
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StarCircleScreen(viewModel: StarCircleViewModel = viewModel()) {
    val store = viewModel.store
    val state by store.state.collectAsState()
    ThemePageSlot(
        page = ThemePages.STAR_CIRCLE,
        host = {
            ThemePageHost(
                page = ThemePages.STAR_CIRCLE,
                items = state.posts.map { post ->
                    ThemePageItem(
                        id = post.id, kind = "circle_post", title = post.text,
                        subtitle = post.author.name, avatar = post.author.avatar.orEmpty(), timestamp = post.createdAt
                    )
                },
                state = mapOf(
                    "feed" to circleFeedJson(state.posts),
                    "viewer_id" to state.viewer?.id.orEmpty(),
                    "loading" to state.loading.toString(),
                    "has_more" to (state.nextCursor != null).toString(),
                    "error" to state.error?.name.orEmpty(),
                    "comment_post_id" to state.commentTarget?.postId.orEmpty(),
                    "comment_id" to state.commentTarget?.commentId.orEmpty(),
                    "comment_name" to state.commentTarget?.name.orEmpty(),
                    "draft" to state.draft,
                    "sending_comment" to state.sendingComment.toString(),
                    "busy_posts" to JSONArray(state.busyPosts.toList()).toString()
                ),
                content = { CircleComposer(state, store) },
                dispatch = { action ->
                    if (action is ThemePageAction.Invoke) {
                        val postId = action.payload["post_id"]
                        when (action.id) {
                            "refresh" -> store.refresh()
                            "load_more" -> store.loadMore()
                            "toggle_like" -> postId?.let(store::toggleLike)
                            "comment" -> postId?.let { store.beginComment(it, action.payload["comment_id"]?.takeIf(String::isNotBlank)) }
                            "edit_comment" -> store.editDraft(action.payload["text"].orEmpty())
                            "send_comment" -> store.sendComment()
                            "close_comment" -> store.closeComment()
                            "dismiss_error" -> store.dismissError()
                        }
                    }
                }
            )
        },
        fallback = { CircleFeedContent(state, store) }
    )
}

@Composable
private fun CircleFeedContent(state: StarCircleState, store: StarCircleStore) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(themeText("nav_star_circle"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = store::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, themeText("circle_refresh"))
            }
        }
        if (state.error != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    themeText(if (state.error == CircleError.UNAVAILABLE) "circle_unavailable" else "circle_request_failed"),
                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = store::refresh, enabled = !state.loading) { Text(themeText("circle_retry")) }
            }
        }
        if (state.posts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Outlined.WbTwilight, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(themeText("circle_empty"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(themeText("circle_empty_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.posts, key = { it.id }) { post ->
                    CirclePostRow(
                        post = post,
                        liked = post.likedByMe ?: post.likes.any { it.id == state.viewer?.id },
                        busy = post.id in state.busyPosts,
                        onLike = { store.toggleLike(post.id) },
                        onComment = { store.beginComment(post.id) },
                        onReply = { store.beginComment(post.id, it.id) }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.nextCursor != null) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = store::loadMore, enabled = !state.loading) {
                            Text(themeText(if (state.loading) "circle_loading" else "circle_load_more"))
                        }
                    }
                }
            }
        }
        CircleComposer(state, store)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CirclePostRow(
    post: CirclePost,
    liked: Boolean,
    busy: Boolean,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onReply: (CircleComment) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(20.dp)) {
        CircleAvatar(post.author, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(post.author.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(post.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val time = remember(post.createdAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(post.createdAt)) }
                Text(time, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onLike, enabled = !busy) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        themeText(if (liked) "circle_unlike" else "circle_like"),
                        Modifier.size(20.dp), tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onComment) {
                    Icon(Icons.Outlined.ChatBubbleOutline, themeText("circle_comment"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (post.likes.isNotEmpty() || post.comments.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (post.likes.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.FavoriteBorder, themeText("circle_like_count", post.likes.size), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                post.likes.forEach { person -> CircleAvatar(person, 28.dp) }
                            }
                        }
                        if (post.likes.isNotEmpty() && post.comments.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        post.comments.forEach { comment ->
                            val replyText = themeText("circle_reply_word")
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append(comment.author.name) }
                                    comment.replyTo?.let { target ->
                                        append(" $replyText ")
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append(target.name) }
                                    }
                                    append(": ${comment.text}")
                                },
                                Modifier.fillMaxWidth().clickable { onReply(comment) }.padding(vertical = 3.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleAvatar(person: CirclePerson, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = person.name },
        contentAlignment = Alignment.Center
    ) {
        if (person.avatar.isNullOrBlank()) {
            Text(person.name.take(1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        } else AsyncImage(person.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun CircleComposer(state: StarCircleState, store: StarCircleStore) {
    val target = state.commentTarget ?: return
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(target) { focus.requestFocus(); keyboard?.show() }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(themeText("circle_reply_to", target.name), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { store.closeComment(); keyboard?.hide() }, enabled = !state.sendingComment, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, themeText("circle_cancel"), Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft, onValueChange = store::editDraft,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(themeText("circle_comment_hint")) },
                    enabled = !state.sendingComment, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { store.sendComment() }),
                    shape = RoundedCornerShape(16.dp)
                )
                IconButton(onClick = store::sendComment, enabled = state.draft.isNotBlank() && !state.sendingComment) {
                    if (state.sendingComment) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.Send, themeText("circle_send"), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun circleFeedJson(posts: List<CirclePost>): String {
    fun person(value: CirclePerson) = JSONObject().put("id", value.id).put("name", value.name).put("avatar", value.avatar.orEmpty())
    return JSONArray().apply {
        posts.forEach { post ->
            put(JSONObject().put("id", post.id).put("author", person(post.author)).put("text", post.text).put("created_at", post.createdAt)
                .put("liked_by_me", post.likedByMe ?: JSONObject.NULL)
                .put("likes", JSONArray().apply { post.likes.forEach { put(person(it)) } })
                .put("comments", JSONArray().apply {
                    post.comments.forEach { comment ->
                        put(JSONObject().put("id", comment.id).put("author", person(comment.author)).put("text", comment.text)
                            .put("created_at", comment.createdAt).put("reply_to_comment_id", comment.replyToCommentId ?: JSONObject.NULL)
                            .put("reply_to", comment.replyTo?.let(::person) ?: JSONObject.NULL))
                    }
                }))
        }
    }.toString()
}
