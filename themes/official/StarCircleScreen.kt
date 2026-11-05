package com.xingshi.ai.feature.moments.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.domain.CircleAttachment
import com.xingshi.ai.domain.CircleAttachmentKind
import com.xingshi.ai.domain.CircleComment
import com.xingshi.ai.domain.CirclePerson
import com.xingshi.ai.domain.CirclePost
import com.xingshi.ai.domain.CirclePublishMedia
import com.xingshi.ai.feature.chat.ui.viewmodel.AttachmentCopy
import com.xingshi.ai.feature.chat.ui.viewmodel.AttachmentCopyResult
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
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StarCircleScreen(viewModel: StarCircleViewModel = viewModel()) {
    val store = viewModel.store
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copySeq = remember { java.util.concurrent.atomic.AtomicLong() }
    var uiMessage by remember { mutableStateOf<String?>(null) }

    suspend fun importMedia(uri: Uri?, fallbackMime: String) {
        if (uri == null) return
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        when (val copied = withContext(Dispatchers.IO) { copyCircleDraft(context, uri, mime, copySeq.incrementAndGet()) }) {
            is CircleDraftCopy.Copied -> {
                val failure = store.addPublishMedia(copied.path, copied.name, mime)
                if (failure != null) {
                    runCatching { File(copied.path).delete() }
                    uiMessage = failure
                }
            }
            CircleDraftCopy.TooLarge -> uiMessage = "ui_circle_publish_media_too_large"
            CircleDraftCopy.Failed -> uiMessage = "ui_circle_publish_copy_failed"
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris: List<Uri> ->
        scope.launch { uris.forEach { importMedia(it, "image/*") } }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        scope.launch { importMedia(uri, "video/*") }
    }
    val pickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val pickVideo = { videoPicker.launch("video/*") }
    val visibleMessage = state.publishFailure ?: uiMessage

    ThemePageSlot(
        page = ThemePages.STAR_CIRCLE,
        host = {
            ThemePageHost(
                page = ThemePages.STAR_CIRCLE,
                items = state.posts.map { post ->
                    ThemePageItem(
                        id = post.id, kind = "circle_post", title = post.text,
                        subtitle = post.author.name, avatar = post.author.avatar.orEmpty(), timestamp = post.createdAt,
                        mediaPath = state.localMedia[post.id]?.firstOrNull().orEmpty()
                    )
                },
                state = mapOf(
                    "feed" to circleFeedJson(state),
                    "viewer_id" to state.viewer?.id.orEmpty(),
                    "loading" to state.loading.toString(),
                    "has_more" to (state.nextCursor != null).toString(),
                    "error" to state.error?.name.orEmpty(),
                    "comment_post_id" to state.commentTarget?.postId.orEmpty(),
                    "comment_id" to state.commentTarget?.commentId.orEmpty(),
                    "comment_name" to state.commentTarget?.name.orEmpty(),
                    "draft" to state.draft,
                    "sending_comment" to state.sendingComment.toString(),
                    "busy_posts" to JSONArray(state.busyPosts.toList()).toString(),
                    "publish_open" to state.publishOpen.toString(),
                    "publish_text" to state.publishText,
                    "publish_media" to circleMediaJson(state.publishMedia),
                    "publish_media_count" to state.publishMedia.size.toString(),
                    "publishing" to state.publishing.toString(),
                    "publish_failure" to visibleMessage?.let { themeText(it) }.orEmpty(),
                    "publish_progress" to state.publishProgress.orEmpty()
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
                            "open_publish" -> store.openPublish()
                            "close_publish" -> store.closePublish()
                            "edit_publish_text" -> store.editPublishText(action.payload["text"].orEmpty())
                            "remove_publish_media" -> action.payload["id"]?.let(store::removePublishMedia)
                            "publish" -> store.publish()
                            "pick_image" -> pickImage()
                            "pick_video" -> pickVideo()
                        }
                    }
                }
            )
        },
        fallback = {
            CircleFeedContent(state, store, visibleMessage, pickImage, pickVideo, onDismissMessage = { uiMessage = null })
        }
    )
}

@Composable
private fun CircleFeedContent(
    state: StarCircleState,
    store: StarCircleStore,
    message: String?,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onDismissMessage: () -> Unit
) {
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
            IconButton(onClick = store::openPublish, enabled = !state.publishing) {
                Icon(Icons.Outlined.Add, themeText("ui_circle_publish"), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (state.publishOpen) {
            CirclePublishPanel(state, store, message, onPickImage, onPickVideo, onDismissMessage)
        }
        if (state.publishPending && !state.publishOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    themeText("ui_circle_publish_ready"), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = store::publish, enabled = !state.publishing) {
                    Text(themeText("ui_circle_publish"))
                }
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
                        localPaths = state.localMedia[post.id].orEmpty(),
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

@Composable
private fun CirclePublishPanel(
    state: StarCircleState,
    store: StarCircleStore,
    message: String?,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val hasVideo = state.publishMedia.any { it.kind == CircleAttachmentKind.VIDEO }
    val hasImages = state.publishMedia.any { it.kind == CircleAttachmentKind.IMAGE }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    themeText("ui_circle_publish_title"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = store::closePublish, enabled = !state.publishing, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, themeText("ui_circle_cancel"), Modifier.size(18.dp))
                }
            }
            OutlinedTextField(
                value = state.publishText, onValueChange = store::editPublishText,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(themeText("ui_circle_publish_hint")) },
                enabled = !state.publishing, maxLines = 5,
                shape = RoundedCornerShape(16.dp)
            )
            CircleMediaStrip(state.publishMedia, store::removePublishMedia)
            if (state.publishPending) {
                Text(
                    themeText("ui_circle_publish_ready"), Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPickImage, enabled = !state.publishing && !hasVideo && state.publishMedia.size < 9) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(themeText("ui_circle_add_image"))
                }
                TextButton(onClick = onPickVideo, enabled = !state.publishing && state.publishMedia.isEmpty()) {
                    Icon(Icons.Outlined.Videocam, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(themeText("ui_circle_add_video"))
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = store::publish,
                    enabled = !state.publishing && (state.publishPending || state.publishText.isNotBlank() || (hasImages || hasVideo))
                ) { Text(themeText("ui_circle_publish")) }
            }
            if (state.publishing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    val progress = state.publishProgress?.split("/")?.mapNotNull(String::toIntOrNull)
                    Text(
                        if (progress != null && progress.size == 2) themeText("ui_circle_publish_progress", progress[0], progress[1])
                        else themeText("ui_circle_publishing"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (message != null) {
                Text(
                    themeText(message), Modifier.fillMaxWidth().clickable(onClick = onDismissMessage),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CircleMediaStrip(media: List<CirclePublishMedia>, onRemove: (String) -> Unit) {
    if (media.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(media, key = { it.id }) { item ->
            Box(Modifier.size(84.dp)) {
                if (item.kind == CircleAttachmentKind.IMAGE) {
                    AsyncImage(
                        model = File(item.path),
                        contentDescription = item.fileName,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Videocam, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.fileName, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)).clickable { onRemove(item.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, themeText("ui_circle_remove_media"), tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CirclePostRow(
    post: CirclePost,
    localPaths: List<String>,
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
            if (post.text.isNotBlank()) Text(post.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            CirclePostAttachments(post.attachments, localPaths)
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
private fun CirclePostAttachments(attachments: List<CircleAttachment>, localPaths: List<String>) {
    if (attachments.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEachIndexed { index, attachment ->
            val local = localPaths.getOrNull(index)?.takeIf { File(it).isFile }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (attachment.kind == CircleAttachmentKind.IMAGE && local != null) {
                        AsyncImage(local, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(
                            if (attachment.kind == CircleAttachmentKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Videocam,
                            null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        attachment.fileName.ifBlank {
                            themeText(if (attachment.kind == CircleAttachmentKind.IMAGE) "ui_circle_image" else "ui_circle_video")
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface
                    )
                    if (attachment.text.isNotBlank()) {
                        Text(attachment.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (local == null) {
                        Text(
                            themeText("ui_circle_media_unavailable"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                        )
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

private fun circleFeedJson(state: StarCircleState): String {
    fun person(value: CirclePerson) = JSONObject().put("id", value.id).put("name", value.name).put("avatar", value.avatar.orEmpty())
    fun attachment(value: CircleAttachment) = JSONObject().put("kind", value.kind.name)
        .put("file_name", value.fileName).put("text", value.text)
    return JSONArray().apply {
        state.posts.forEach { post ->
            put(JSONObject().put("id", post.id).put("author", person(post.author)).put("text", post.text).put("created_at", post.createdAt)
                .put("liked_by_me", post.likedByMe ?: JSONObject.NULL)
                .put("local_media", JSONArray(state.localMedia[post.id].orEmpty()))
                .put("attachments", JSONArray().apply { post.attachments.forEach { put(attachment(it)) } })
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

private fun circleMediaJson(media: List<CirclePublishMedia>): String = JSONArray().apply {
    media.forEach { item ->
        put(JSONObject().put("id", item.id).put("kind", item.kind.name).put("file_name", item.fileName)
            .put("mime_type", item.mimeType).put("path", item.path).put("text", item.text))
    }
}.toString()

private sealed interface CircleDraftCopy {
    data class Copied(val path: String, val name: String) : CircleDraftCopy
    data object TooLarge : CircleDraftCopy
    data object Failed : CircleDraftCopy
}

private suspend fun copyCircleDraft(
    context: android.content.Context,
    uri: Uri,
    mimeType: String,
    seq: Long
): CircleDraftCopy {
    val resolver = context.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull() ?: if (mimeType.startsWith("video/")) "video" else "image"
    val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80).ifBlank { "media" }
    val dir = File(context.applicationContext.cacheDir, "circle_draft").apply { mkdirs() }
    val target = File(dir, "${System.currentTimeMillis()}_${seq}_$safeName")
    val input = try {
        resolver.openInputStream(uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        null
    } ?: return CircleDraftCopy.Failed
    return try {
        val kind = AttachmentCopy.kindOf(mimeType, displayName)
        when (AttachmentCopy.copy(input, target, AttachmentCopy.limitBytes(kind))) {
            AttachmentCopyResult.Copied -> CircleDraftCopy.Copied(target.absolutePath, displayName)
            AttachmentCopyResult.TooLarge -> CircleDraftCopy.TooLarge
            AttachmentCopyResult.Failed -> CircleDraftCopy.Failed
        }
    } finally {
        runCatching { input.close() }
    }
}
