package com.xingshi.ai.feature.chat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xingshi.ai.feature.chat.ui.viewmodel.AttachmentCopy
import com.xingshi.ai.feature.chat.ui.viewmodel.AttachmentCopyResult
import com.xingshi.ai.feature.chat.ui.viewmodel.DraftAttachment
import com.xingshi.ai.feature.chat.ui.viewmodel.DraftAttachmentKind
import com.xingshi.ai.theme.framework.themeText
import kotlinx.coroutines.CancellationException

internal val DraftFileMimeTypes = arrayOf(
    "text/markdown",
    "text/x-markdown",
    "text/plain",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)

@Composable
internal fun DraftAttachmentStrip(
    attachments: List<DraftAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = attachments, key = { it.id }) { attachment ->
            DraftAttachmentItem(attachment = attachment, onRemove = onRemove)
        }
    }
}

@Composable
private fun DraftAttachmentItem(
    attachment: DraftAttachment,
    onRemove: (String) -> Unit
) {
    Box(modifier = Modifier.size(width = 96.dp, height = 76.dp)) {
        if (attachment.kind == DraftAttachmentKind.IMAGE) {
            AsyncImage(
                model = java.io.File(attachment.path),
                contentDescription = attachment.fileName,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (attachment.kind == DraftAttachmentKind.BILIBILI) {
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFB7299)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "B", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (attachment.kind == DraftAttachmentKind.BILIBILI) {
                        attachment.fileName.ifBlank { themeText("ui_bilibili_draft_label") }
                    } else {
                        attachment.fileName
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onRemove(attachment.id) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = themeText("ui_attach_remove"),
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private val draftCopySeq = java.util.concurrent.atomic.AtomicLong()

internal suspend fun copyUriToDraftCache(
    context: android.content.Context,
    uri: android.net.Uri,
    fallbackName: String = "attachment"
): Pair<String, String>? {
    return try {
        val resolver = context.contentResolver
        val displayName = resolver.displayName(uri) ?: fallbackName
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80)
            .ifBlank { fallbackName }
        val dir = java.io.File(context.applicationContext.cacheDir, "draft_attachments")
        if (!dir.exists()) dir.mkdirs()
        val cacheFile = java.io.File(dir, "${System.currentTimeMillis()}_${draftCopySeq.incrementAndGet()}_$safeName")
        val kind = AttachmentCopy.kindOf(resolver.getType(uri).orEmpty(), displayName)
        val copied = resolver.openInputStream(uri)?.use { input ->
            AttachmentCopy.copy(input, cacheFile, AttachmentCopy.limitBytes(kind)) == AttachmentCopyResult.Copied
        } ?: false
        if (copied) cacheFile.absolutePath to displayName else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private fun android.content.ContentResolver.displayName(uri: android.net.Uri): String? = try {
    query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
    }
} catch (e: Exception) {
    null
}
