package com.xingshi.ai.feature.chat.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xingshi.ai.common.StickerManager
import com.xingshi.ai.common.text.MessageSegmenter
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.CompanionEntity as CompanionModel
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.uicommon.component.CompanionAvatar
import com.xingshi.ai.uicommon.component.UserAvatar
import com.xingshi.ai.uicommon.component.VoiceMessageBubble
import com.xingshi.ai.uicommon.theme.AdaptiveSizing

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.xingshi.ai.feature.chat.ui.viewmodel.DraftAttachmentKind
import com.xingshi.ai.feature.chat.ui.viewmodel.MessageAttachments
import com.xingshi.ai.feature.chat.ui.viewmodel.StoredAttachment
import java.io.File
import kotlin.math.abs
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemeRuntime

@Composable
fun TypingIndicatorBubble(
    companionData: CompanionModel?,
    typingText: String,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean
) {
    val aiBubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val aiBorderColor = MaterialTheme.colorScheme.outline

    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_blink"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        CompanionAvatar(
            avatarUrl = companionData?.avatarUrl,
            name = companionData?.name,
            size = adaptiveSizing.avatarSize
        )
        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.Start) {
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                    .background(aiBubbleColor)
                    .then(
                        if (isDarkTheme) Modifier else Modifier.drawBehind {
                            drawRoundRect(
                                color = aiBorderColor,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(adaptiveSizing.cornerRadius.toPx(), adaptiveSizing.cornerRadius.toPx()),
                                style = Stroke(width = 0.8f)
                            )
                        }
                    )
                    .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = adaptiveSizing.chatBubblePaddingVertical)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typingText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(width = 2.dp, height = 16.dp)
                            .alpha(cursorAlpha)
                            .background(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = themeText("typing"),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = adaptiveSizing.fontSizeCaption.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
fun ReasoningBubble(
    reasoningText: String,
    adaptiveSizing: AdaptiveSizing
) {
    var expanded by remember { mutableStateOf(false) }
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        Spacer(modifier = Modifier.width(adaptiveSizing.avatarSize + 8.dp))
        Column {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                    .background(bgColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { expanded = !expanded }
                    ) {
                        Text(
                            text = if (expanded) themeText("ui_chat_bubbles_01") else themeText("ui_chat_bubbles_02"),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = textColor
                        )
                    }
                    if (expanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = reasoningText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

data class MessageAttachmentView(
    val id: String,
    val name: String,
    val path: String,
    val kind: DraftAttachmentKind,
    val openable: Boolean,
    val missingKey: String?
)

fun messageAttachmentViews(message: ChatMessage): List<MessageAttachmentView> {
    val items = MessageAttachments.decode(message.linkString)
        ?: legacyAttachment(message)?.let { listOf(it) }
        ?: return emptyList()
    return items.map { item ->
        val hasOriginal = item.path.isNotBlank()
        val exists = hasOriginal && File(item.path).isFile
        MessageAttachmentView(
            id = item.id,
            name = item.name,
            path = item.path,
            kind = item.kind,
            openable = exists,
            missingKey = if (hasOriginal && !exists) "ui_attach_file_missing" else null
        )
    }
}

enum class MessageAttachmentRowKind { IMAGE, FILE }

data class MessageAttachmentRow(
    val view: MessageAttachmentView,
    val kind: MessageAttachmentRowKind
)

fun attachmentRows(views: List<MessageAttachmentView>): List<MessageAttachmentRow> = views.map { view ->
    MessageAttachmentRow(
        view = view,
        kind = if (view.kind == DraftAttachmentKind.IMAGE && view.openable) {
            MessageAttachmentRowKind.IMAGE
        } else {
            MessageAttachmentRowKind.FILE
        }
    )
}

fun isLegacyImageBubble(message: ChatMessage): Boolean = message.type == MessageType.IMAGE

fun messageAttachmentBodyText(message: ChatMessage, views: List<MessageAttachmentView>): String? =
    message.content.takeIf { text ->
        text.isNotBlank() && views.none { it.path.isNotBlank() && it.path == text.trim() }
    }

private fun legacyAttachment(message: ChatMessage): StoredAttachment? {
    if (message.type != MessageType.FILE && message.type != MessageType.VIDEO) return null
    val path = message.linkString.ifBlank { message.content }.trim()
    if (path.startsWith("http://") || path.startsWith("https://")) {
        return StoredAttachment(
            id = "legacy-${message.id}", name = "", kind = DraftAttachmentKind.BILIBILI,
            path = path, sourceUrl = path
        )
    }
    if (!path.startsWith("/")) return null
    return StoredAttachment(
        id = "legacy-${message.id}",
        name = File(path).name,
        kind = if (message.type == MessageType.VIDEO) DraftAttachmentKind.VIDEO else DraftAttachmentKind.DOCUMENT,
        path = path
    )
}

private fun openLocalAttachment(context: android.content.Context, view: MessageAttachmentView) {
    val file = File(view.path)
    if (!file.isFile) return
    val mime = when (view.kind) {
        DraftAttachmentKind.IMAGE -> "image/*"
        DraftAttachmentKind.VIDEO -> "video/*"
        DraftAttachmentKind.BILIBILI -> "video/*"
        DraftAttachmentKind.DOCUMENT ->
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.xingshi.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Toast.makeText(context, ThemeRuntime.resolveText("ui_attach_open_failed"), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MessageAttachmentRows(
    rows: List<MessageAttachmentRow>,
    isUser: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    val context = LocalContext.current
    val background = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(modifier = Modifier.height(4.dp))
        val view = row.view
        if (row.kind == MessageAttachmentRowKind.IMAGE) {
            AsyncImage(
                model = File(view.path),
                contentDescription = view.name.ifBlank { themeText("ui_chat_bubbles_03") },
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (view.openable) Modifier.clickable { openLocalAttachment(context, view) } else Modifier),
                contentScale = ContentScale.Fit
            )
        } else {
            Row(
                modifier = Modifier
                    .widthIn(max = 260.dp * adaptiveSizing.messageBubbleMaxWidthRatio / 0.75f)
                    .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                    .background(background)
                    .then(if (view.openable) Modifier.clickable { openLocalAttachment(context, view) } else Modifier)
                    .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = view.name.ifBlank { themeText("ui_bilibili_draft_label") },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
                        color = foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    view.missingKey?.let { key ->
                        Text(
                            text = themeText(key),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = foreground.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatBubble(
    message: ChatMessage,
    companionData: CompanionModel?,
    isUser: Boolean,
    userAvatar: String?,
    userName: String,
    onVoiceClick: () -> Unit = {},
    onRecall: ((ChatMessage) -> Unit)? = null,
    onResynthesizeVoice: ((ChatMessage) -> Unit)? = null,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean
) {
    val time = remember(message.timestamp) {
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.ofEpochMilli(message.timestamp).atZone(java.time.ZoneId.systemDefault()))
    }
    var showMenu by remember { mutableStateOf(false) }

    val attachmentViews = remember(message.id, message.linkString, message.type, message.content) {
        messageAttachmentViews(message)
    }
    val isEmptyContent = message.content.isBlank() && message.type != MessageType.IMAGE &&
            attachmentViews.isEmpty() &&
            !(message.content.startsWith("[") && message.content.endsWith("]"))
    if (isEmptyContent) return

    val userBubbleColor = MaterialTheme.colorScheme.primary
    val aiBubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val aiBorderColor = MaterialTheme.colorScheme.outline

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onRecall != null || onResynthesizeVoice != null) Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    ) else Modifier
                ),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
        if (!isUser) {
        CompanionAvatar(
            avatarUrl = companionData?.avatarUrl,
            name = companionData?.name,
            size = adaptiveSizing.avatarSize
        )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
            val isStickerMessage = message.content.startsWith("[") && message.content.endsWith("]") &&
                    message.content.removeSurrounding("[", "]") !in systemTags
            val displayContent = if (!isUser) {
                message.content
                    .replace(Regex("^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*"), "")
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                    .replace(Regex("(?m)^enc:\\S+$"), "")
                    .replace(Regex("\\n{2,}"), "\n")
                    .trim()
                    .ifBlank { message.content }
            } else {
                message.content
            }
            if (message.type == MessageType.CALL && message.content.startsWith("call-card|")) {
                val parts = message.content.split("|")
                val video = parts.getOrNull(1) == "VIDEO_CALL"
                val seconds = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val interrupted = parts.getOrNull(3) == "interrupted"
                val base = themeText(if (video) "ui_call_card_video" else "ui_call_card_voice")
                val label = if (interrupted) {
                    "$base · ${themeText("ui_call_card_interrupted")}"
                } else {
                    base
                }
                Text(
                    text = "$label · %02d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                        .background(if (isUser) userBubbleColor else aiBubbleColor)
                        .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = adaptiveSizing.chatBubblePaddingVertical)
                )
            } else if (isStickerMessage) {
                StickerMessageBubble(
                    stickerName = message.content.removeSurrounding("[", "]")
                )
            } else if (message.content.startsWith("[语音]") || (message.type == MessageType.VOICE)) {
                val ctx = LocalContext.current
                val storedSeconds = (com.xingshi.ai.feature.chat.VoiceTurnMetadataStore
                    .durationMs(ctx, message.id) / 1000L).toInt()
                val voiceDuration = maxOf(extractVoiceDuration(message.content), storedSeconds)
                val voicePath = message.linkString.ifBlank {
                    java.io.File(ctx.cacheDir, "voice_${message.id}.m4a").absolutePath
                }
                VoiceMessageBubble(
                    audioPath = voicePath,
                    duration = voiceDuration,
                    isUser = isUser
                )
            } else if (isLegacyImageBubble(message)) {
                val imageFile = java.io.File(message.linkString.ifBlank { message.content })
                if (imageFile.exists()) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = themeText("ui_chat_bubbles_03"),
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                            .background(if (isUser) userBubbleColor else aiBubbleColor)
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                    ) {
                        Text(
                            text = themeText("ui_chat_bubbles_04"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (attachmentViews.isNotEmpty()) {
                val typed = messageAttachmentBodyText(message, attachmentViews)
                if (typed != null) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 260.dp * adaptiveSizing.messageBubbleMaxWidthRatio / 0.75f)
                            .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                            .background(if (isUser) userBubbleColor else aiBubbleColor)
                            .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = adaptiveSizing.chatBubblePaddingVertical)
                    ) {
                        Text(
                            text = typed,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = adaptiveSizing.fontSizeBody.sp,
                                lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp
                            ),
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            softWrap = true
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                MessageAttachmentRows(attachmentRows(attachmentViews), isUser, adaptiveSizing)
            } else if (isUser) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 260.dp * adaptiveSizing.messageBubbleMaxWidthRatio / 0.75f)
                        .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                        .background(userBubbleColor)
                        .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = adaptiveSizing.chatBubblePaddingVertical)
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = adaptiveSizing.fontSizeBody.sp,
                            lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                        softWrap = true
                    )
                }
            } else {
                val segments = MessageSegmenter.split(displayContent)
                segments.forEachIndexed { index, segment ->
                    if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .widthIn(max = 260.dp * adaptiveSizing.messageBubbleMaxWidthRatio / 0.75f)
                            .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                            .background(aiBubbleColor)
                            .then(
                                if (isDarkTheme) Modifier else Modifier.drawBehind {
                                    drawRoundRect(
                                        color = aiBorderColor,
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(adaptiveSizing.cornerRadius.toPx(), adaptiveSizing.cornerRadius.toPx()),
                                        style = Stroke(width = 0.8f)
                                    )
                                }
                            )
                            .padding(horizontal = adaptiveSizing.chatBubblePaddingHorizontal, vertical = adaptiveSizing.chatBubblePaddingVertical)
                    ) {
                        Text(
                            text = segment,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = adaptiveSizing.fontSizeBody.sp,
                                lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = adaptiveSizing.fontSizeCaption.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start
            )
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(
                avatarUrl = userAvatar,
                name = userName,
                size = adaptiveSizing.avatarSize
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            offset = DpOffset(x = 0.dp, y = 0.dp)
        ) {
            if (onRecall != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = themeText("ui_chat_bubbles_05"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        showMenu = false
                        onRecall(message)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.DeleteOutline, themeText("ui2_chat_bubbles_01"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                )
            }
            if (onResynthesizeVoice != null && !isUser && message.type == MessageType.TEXT) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = themeText("ui_chat_bubbles_06"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        showMenu = false
                        onResynthesizeVoice(message)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.VolumeUp, themeText("ui_chat_bubbles_06"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                )
            }
        }
    }
}
}

internal fun copyUriToCache(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val fileName = "chat_media_${System.currentTimeMillis()}"
        val cacheFile = java.io.File(context.applicationContext.cacheDir, fileName)
        inputStream.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun extractVoiceDuration(content: String): Int {
    val regex = Regex("\\[语音]\\s*(\\d+)[\"秒]")
    return regex.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

@Composable
internal fun rememberHorizontalSwipeGuard(): NestedScrollConnection {
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (abs(available.y) > abs(available.x)) {
                    Offset(available.x, 0f)
                } else {
                    Offset.Zero
                }
            }
        }
    }
}

@Composable
fun StickerMessageBubble(
    stickerName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(stickerName) {
        val manager = StickerManager.getInstance(context)
        var sticker = manager.findStickerByDescriptionExact(stickerName)
        if (sticker == null && !stickerName.endsWith(".png")) {
            sticker = manager.findStickerByDescriptionExact("$stickerName.png")
        }
        if (sticker == null) {
            sticker = manager.findStickerByDescription(stickerName)
        }
        if (sticker != null) {
            bitmap = manager.loadStickerBitmap(sticker.path)
        } else {
            val importedDir = java.io.File(context.applicationContext.filesDir, "stickers/imported")
            val possibleFiles = listOf(
                "$stickerName.png", "$stickerName.jpg", "$stickerName.jpeg",
                "$stickerName.gif", "$stickerName.webp",
                "sticker_$stickerName.png"
            )
            for (fileName in possibleFiles) {
                val file = java.io.File(importedDir, fileName)
                if (file.exists()) {
                    bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    break
                }
            }
            if (bitmap == null) {
                try {
                    context.assets.list("stickers")?.filter { it.equals("$stickerName.png", ignoreCase = true) || it.equals(stickerName, ignoreCase = true) }?.firstOrNull()?.let {
                        context.assets.open("stickers/$it").use { stream ->
                            bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = stickerName,
            modifier = modifier
                .sizeIn(maxWidth = 140.dp, maxHeight = 140.dp)
                .width(120.dp)
                .height(120.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🎨",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
