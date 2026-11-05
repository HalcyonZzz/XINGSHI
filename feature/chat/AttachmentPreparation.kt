package com.xingshi.ai.feature.chat.ui.viewmodel

import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.feature.chat.DocumentAnalysisService
import com.xingshi.ai.feature.chat.VideoAnalysisService
import com.xingshi.ai.feature.chat.VisionAnalysisService
import com.xingshi.ai.plugins.api.DocumentFailureCode
import com.xingshi.ai.plugins.api.VideoFailureCode
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class PreparedAttachment(
    val kind: DraftAttachmentKind,
    val path: String,
    val fileName: String,
    val mimeType: String,
    val text: String = "",
    val format: String = "",
    val segments: List<PreparedSegment> = emptyList(),
    val sourceUrl: String = "",
    val linkText: String = "",
    val videoPath: String = ""
)

internal data class PreparedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val providerId: String = "",
    val model: String = "",
    val promptVersion: Int = 0,
    val fileKey: String = "",
    val endpoint: String = ""
)

data class StoredAttachment(
    val id: String,
    val name: String,
    val kind: DraftAttachmentKind,
    val path: String = "",
    val sourceUrl: String = ""
)

object MessageAttachments {
    fun encode(items: List<StoredAttachment>): String = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("id", item.id)
                put("name", item.name)
                put("kind", item.kind.name)
                put("path", item.path)
                if (item.sourceUrl.isNotBlank()) put("source_url", item.sourceUrl)
            })
        }
    }.toString()

    fun decode(raw: String): List<StoredAttachment>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching {
            Json.parseToJsonElement(trimmed).jsonArray.map { rawItem ->
                val item = rawItem.jsonObject
                val kind = DraftAttachmentKind.valueOf(item.text("kind"))
                val path = item.text("path")
                require(kind == DraftAttachmentKind.BILIBILI || path.isNotBlank())
                StoredAttachment(
                    id = item.text("id"),
                    name = item.text("name"),
                    kind = kind,
                    path = path,
                    sourceUrl = item.text("source_url")
                )
            }.takeIf { items ->
                items.isNotEmpty() && items.all { it.id.isNotBlank() } && items.map { it.id }.toSet().size == items.size
            }
        }.getOrNull()
    }

    fun mediaReferences(message: ChatMessage): List<String> =
        listOf(message.linkString, message.content) + decode(message.linkString).orEmpty().map { it.path }

    fun userMessage(
        companionId: Long,
        text: String,
        timestamp: Long,
        items: List<StoredAttachment>
    ): ChatMessage = ChatMessage(
        companionId = companionId,
        content = text,
        isFromUser = true,
        timestamp = timestamp,
        type = MessageType.TEXT,
        linkString = encode(items)
    )
}

internal fun StoredAttachment.toPrepared(): PreparedAttachment = PreparedAttachment(
    kind = kind,
    path = path,
    fileName = name,
    mimeType = when (kind) {
        DraftAttachmentKind.IMAGE -> "image/*"
        DraftAttachmentKind.VIDEO -> "video/*"
        DraftAttachmentKind.DOCUMENT, DraftAttachmentKind.BILIBILI -> ""
    },
    sourceUrl = sourceUrl
)

internal fun PreparedAttachment.toStored(copied: File?): StoredAttachment = StoredAttachment(
    id = UUID.randomUUID().toString(),
    name = fileName,
    kind = kind,
    path = if (kind == DraftAttachmentKind.BILIBILI) videoPath else copied?.absolutePath.orEmpty(),
    sourceUrl = if (kind == DraftAttachmentKind.BILIBILI) sourceUrl else ""
)

internal data class AttachmentPreparationState(
    val clientMessageId: String,
    val sourceEventId: String,
    val content: String,
    val occurredAt: String,
    val items: List<PreparedAttachment>
) {
    fun nextMissing(): Int? = items.indexOfFirst { it.text.isBlank() }.takeIf { it >= 0 }
}

internal sealed interface ResolvedAttachment {
    data class Text(
        val text: String,
        val format: String = "",
        val segments: List<PreparedSegment> = emptyList()
    ) : ResolvedAttachment
    data class Failed(val statusKey: String) : ResolvedAttachment
}

internal sealed interface AttachmentPreparationOutcome {
    data class Completed(val state: AttachmentPreparationState) : AttachmentPreparationOutcome
    data class Incomplete(val statusKey: String, val state: AttachmentPreparationState) : AttachmentPreparationOutcome
}

internal class AttachmentCapacityExceeded : IllegalStateException("附件记录超过单行可读容量")

internal object AttachmentPreparation {
    const val INPUT_MODE = "ATTACHMENTS"
    const val RETRY_HINT = "ui_attach_retry_hint"

    const val STORED_PAYLOAD_BYTE_BUDGET: Int = 1200 * 1024

    const val MAX_ATTACHMENT_COUNT: Int = 12

    const val OVERSIZED_KEY: String = "ui_attach_too_large"

    private val videoExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2", "mpg", "mpeg", "wmv", "flv")

    fun kindOf(mimeType: String, fileName: String): DraftAttachmentKind {
        val mime = mimeType.lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") -> DraftAttachmentKind.IMAGE
            mime.startsWith("video/") || extension in videoExtensions -> DraftAttachmentKind.VIDEO
            else -> DraftAttachmentKind.DOCUMENT
        }
    }

    fun create(
        clientMessageId: String,
        sourceEventId: String,
        content: String,
        occurredAt: String,
        items: List<PreparedAttachment>
    ): AttachmentPreparationState {
        require(items.isNotEmpty()) { "附件准备至少需要一项" }
        return AttachmentPreparationState(clientMessageId, sourceEventId, content, occurredAt, items)
    }

    fun encode(state: AttachmentPreparationState): JsonObject =
        buildJsonObject {
            put("client_message_id", state.clientMessageId)
            put("source_event_id", state.sourceEventId)
            put("content", state.content)
            put("occurred_at", state.occurredAt)
            put("preparation", buildJsonArray {
                state.items.forEach { item -> add(buildJsonObject {
                    put("kind", item.kind.name)
                    put("path", item.path)
                    put("file_name", item.fileName)
                    put("mime_type", item.mimeType)
                    if (item.text.isNotBlank()) put("text", item.text)
                    if (item.format.isNotBlank()) put("format", item.format)
                    if (item.sourceUrl.isNotBlank()) put("source_url", item.sourceUrl)
                    if (item.linkText.isNotBlank()) put("link_text", item.linkText)
                    if (item.videoPath.isNotBlank()) put("video_path", item.videoPath)
                    if (item.segments.isNotEmpty()) put("segments", buildJsonArray {
                        item.segments.forEach { segment -> add(buildJsonObject {
                            put("start_ms", segment.startMs)
                            put("end_ms", segment.endMs)
                            put("text", segment.text)
                            if (segment.providerId.isNotBlank()) put("provider_id", segment.providerId)
                            if (segment.model.isNotBlank()) put("model", segment.model)
                            if (segment.promptVersion > 0) put("prompt_version", segment.promptVersion)
                            if (segment.fileKey.isNotBlank()) put("file_key", segment.fileKey)
                            if (segment.endpoint.isNotBlank()) put("endpoint", segment.endpoint)
                        }) }
                    })
                }) }
            })
        }

    fun decode(payload: JsonObject): AttachmentPreparationState? {
        val items = payload["preparation"]?.let { value -> runCatching { value.jsonArray }.getOrNull() } ?: return null
        val parsed = items.map { raw ->
            val item = runCatching { raw.jsonObject }.getOrNull() ?: return null
            val kind = runCatching { DraftAttachmentKind.valueOf(item.text("kind")) }.getOrNull() ?: return null
            val segments = if (item.containsKey("segments")) {
                runCatching {
                    item.getValue("segments").jsonArray.map { raw ->
                        val segment = raw.jsonObject
                        PreparedSegment(
                            startMs = segment.text("start_ms").toLong(),
                            endMs = segment.text("end_ms").toLong(),
                            text = segment.text("text"),
                            providerId = segment.text("provider_id"),
                            model = segment.text("model"),
                            promptVersion = segment.text("prompt_version").toIntOrNull() ?: 0,
                            fileKey = segment.text("file_key"),
                            endpoint = segment.text("endpoint")
                        )
                    }
                }.getOrNull() ?: return null
            } else {
                emptyList()
            }
            PreparedAttachment(
                kind = kind,
                path = item.text("path"),
                fileName = item.text("file_name"),
                mimeType = item.text("mime_type"),
                text = item.text("text"),
                format = item.text("format"),
                segments = segments,
                sourceUrl = item.text("source_url"),
                linkText = item.text("link_text"),
                videoPath = item.text("video_path")
            )
        }
        if (parsed.isEmpty()) return null
        return AttachmentPreparationState(
            clientMessageId = payload.text("client_message_id"),
            sourceEventId = payload.text("source_event_id"),
            content = payload.text("content"),
            occurredAt = payload.text("occurred_at"),
            items = parsed
        )
    }

    fun withResult(
        state: AttachmentPreparationState,
        index: Int,
        text: String,
        format: String,
        segments: List<PreparedSegment> = emptyList()
    ): AttachmentPreparationState {
        val updated = state.items.toMutableList()
        val item = updated[index]
        updated[index] = item.copy(
            text = text,
            format = if (item.kind == DraftAttachmentKind.DOCUMENT) format else "",
            segments = if (item.kind == DraftAttachmentKind.VIDEO) segments else emptyList()
        )
        return state.copy(items = updated)
    }

    fun withSegments(
        state: AttachmentPreparationState,
        index: Int,
        segments: List<PreparedSegment>
    ): AttachmentPreparationState {
        val updated = state.items.toMutableList()
        val item = updated[index]
        updated[index] = item.copy(segments = segments)
        return state.copy(items = updated)
    }

    private fun withItem(
        state: AttachmentPreparationState,
        index: Int,
        item: PreparedAttachment
    ): AttachmentPreparationState {
        val updated = state.items.toMutableList()
        updated[index] = item
        return state.copy(items = updated)
    }

    fun request(state: AttachmentPreparationState, includeRoute: Boolean): JsonObject? {
        if (state.items.any { !it.isReady() }) return null
        return buildJsonObject {
            put("client_message_id", state.clientMessageId)
            put("source_event_id", state.sourceEventId)
            put("content", state.content)
            put("occurred_at", state.occurredAt)
            if (includeRoute) put("reply_route_ref", state.clientMessageId)
            put("media", buildJsonObject {
                put("input_mode", INPUT_MODE)
                put("attachments", buildJsonArray {
                    state.items.forEach { item ->
                        if (item.kind == DraftAttachmentKind.BILIBILI) {
                            add(buildJsonObject {
                                put("kind", DraftAttachmentKind.BILIBILI.name)
                                put("source_url", item.sourceUrl)
                                put("link_text", item.linkText)
                                put("text", item.text)
                            })
                        } else {
                            add(buildJsonObject {
                                put("kind", item.kind.name)
                                put("file_name", item.fileName)
                                if (item.kind == DraftAttachmentKind.DOCUMENT) put("file_format", item.format)
                                put("text", item.text)
                            })
                        }
                    }
                })
            })
        }
    }

    fun sendRejectionKey(state: AttachmentPreparationState): String? =
        OVERSIZED_KEY.takeIf { state.items.size > MAX_ATTACHMENT_COUNT } ?: payloadRejectionKey(encode(state))

    fun payloadRejectionKey(payload: JsonObject): String? =
        OVERSIZED_KEY.takeIf { payload.toString().toByteArray(Charsets.UTF_8).size > STORED_PAYLOAD_BYTE_BUDGET }

    private fun PreparedAttachment.isReady(): Boolean =
        text.isNotBlank() &&
            (kind != DraftAttachmentKind.BILIBILI || (sourceUrl.isNotBlank() && linkText.isNotBlank()))

    internal class CapacityExceeded : Exception()

    suspend fun prepareMissing(
        state: AttachmentPreparationState,
        resolve: suspend (
            index: Int,
            item: PreparedAttachment,
            checkpoint: suspend (List<PreparedSegment>) -> Unit,
            persistItem: suspend (PreparedAttachment) -> Unit
        ) -> ResolvedAttachment,
        persist: suspend (AttachmentPreparationState) -> Unit
    ): AttachmentPreparationOutcome {
        var current = state
        while (true) {
            val index = current.nextMissing() ?: return AttachmentPreparationOutcome.Completed(current)
            val item = current.items[index]
            var checkpointed: List<PreparedSegment> = item.segments
            val checkpoint: suspend (List<PreparedSegment>) -> Unit = { segments ->
                val next = withSegments(current, index, segments)
                if (sendRejectionKey(next) != null) throw CapacityExceeded()
                current = next
                checkpointed = segments
                persist(current)
            }
            val persistItem: suspend (PreparedAttachment) -> Unit = { updated ->
                val next = withItem(current, index, updated)
                if (sendRejectionKey(next) != null) throw CapacityExceeded()
                current = next
                persist(current)
            }
            val resolved = try {
                resolve(index, item, checkpoint, persistItem)
            } catch (_: CapacityExceeded) {
                return AttachmentPreparationOutcome.Incomplete(OVERSIZED_KEY, current)
            }
            when (resolved) {
                is ResolvedAttachment.Failed -> return AttachmentPreparationOutcome.Incomplete(resolved.statusKey, current)
                is ResolvedAttachment.Text -> {
                    val segments = if (item.kind == DraftAttachmentKind.VIDEO) {
                        resolved.segments.ifEmpty { checkpointed }
                    } else {
                        emptyList()
                    }
                    val next = withResult(current, index, resolved.text, resolved.format, segments)
                    if (sendRejectionKey(next) != null) {
                        return AttachmentPreparationOutcome.Incomplete(OVERSIZED_KEY, current)
                    }
                    current = next
                    persist(current)
                }
            }
        }
    }

    fun documentFailureKey(code: DocumentFailureCode): String = when (code) {
        DocumentFailureCode.NOT_FOUND, DocumentFailureCode.IO_ERROR -> "ui_doc_read_failed"
        DocumentFailureCode.UNSUPPORTED -> "ui_doc_unsupported"
        DocumentFailureCode.CORRUPT -> "ui_doc_corrupt"
        DocumentFailureCode.ENCRYPTED -> "ui_doc_encrypted"
        DocumentFailureCode.EMPTY -> "ui_doc_empty"
        DocumentFailureCode.TOO_LARGE -> "ui_doc_too_large"
    }

    fun documentOutcomeKey(outcome: DocumentAnalysisService.Outcome): String = when (outcome) {
        DocumentAnalysisService.Outcome.NotInstalled -> "ui_doc_plugin_missing"
        DocumentAnalysisService.Outcome.Cancelled -> "ui_doc_read_failed"
        is DocumentAnalysisService.Outcome.Extracted -> "ui_doc_read_failed"
        is DocumentAnalysisService.Outcome.Failed -> documentFailureKey(outcome.code)
    }

    fun visionFailureKey(outcome: VisionAnalysisService.Outcome): String = when (outcome) {
        VisionAnalysisService.Outcome.NotInstalled -> "ui_attach_vision_missing"
        VisionAnalysisService.Outcome.NotConfigured -> "ui_call_vision_config"
        is VisionAnalysisService.Outcome.Described -> "ui_attach_vision_failed"
        is VisionAnalysisService.Outcome.Failed -> "ui_attach_vision_failed"
    }

    fun blankTextKey(kind: DraftAttachmentKind): String = when (kind) {
        DraftAttachmentKind.IMAGE -> "ui_attach_vision_failed"
        DraftAttachmentKind.DOCUMENT -> "ui_doc_empty"
        DraftAttachmentKind.VIDEO -> "ui_video_empty"
        DraftAttachmentKind.BILIBILI -> "ui_bilibili_failed"
    }

    fun bilibiliFailureKey(code: com.xingshi.ai.plugins.api.BilibiliFailureCode): String = when (code) {
        com.xingshi.ai.plugins.api.BilibiliFailureCode.AMBIGUOUS -> "ui_bilibili_ambiguous"
        else -> "ui_bilibili_failed"
    }

    fun videoFailureKey(code: VideoFailureCode): String = when (code) {
        VideoFailureCode.NOT_CONFIGURED -> "ui_call_video_config"
        VideoFailureCode.TOO_LARGE -> "ui_video_too_large"
        VideoFailureCode.AUTH -> "ui_video_auth_failed"
        VideoFailureCode.EMPTY -> "ui_video_empty"
        else -> "ui_video_failed"
    }

    fun videoOutcomeKey(outcome: VideoAnalysisService.Outcome): String = when (outcome) {
        VideoAnalysisService.Outcome.NotInstalled -> "ui_video_plugin_missing"
        VideoAnalysisService.Outcome.Cancelled -> "ui_video_failed"
        is VideoAnalysisService.Outcome.Described -> "ui_video_empty"
        is VideoAnalysisService.Outcome.Failed -> videoFailureKey(outcome.code)
    }

    fun videoText(segments: List<PreparedSegment>): String =
        VideoAnalysisService.videoText(segments.map { VideoAnalysisService.SegmentText(it.startMs, it.endMs, it.text) })
}

private fun JsonObject.text(key: String): String =
    get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
