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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.abs
import com.xingshi.ai.theme.framework.themeText

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

    val isEmptyContent = message.content.isBlank() && message.type != MessageType.IMAGE &&
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
                // 真实时长来自持久元数据；旧消息才回退正文标记。
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
            } else if (message.type == MessageType.IMAGE) {
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

/**
 * Copy URI content to app cache directory and return the file path
 */
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

/**
 * NestedScrollConnection that prevents horizontal scroll events from propagating
 * to the parent when the user is primarily scrolling vertically.
 * This stops accidental side-swipes (system back gesture, ViewPager) during chat scrolling.
 */
@Composable
internal fun rememberHorizontalSwipeGuard(): NestedScrollConnection {
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 正在垂直滚动时，消费父容器的横向滚动事件
                // 防止横向手势触发系统返回或 ViewPager 切换
                return if (abs(available.y) > abs(available.x)) {
                    Offset(available.x, 0f) // 消费横向，放行纵向
                } else {
                    Offset.Zero
                }
            }
        }
    }
}

/**
 * 表情包消息显示 - 类似微信，无气泡框包裹，直接显示图片
 */
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
