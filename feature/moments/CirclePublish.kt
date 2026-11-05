package com.xingshi.ai.feature.moments

import com.xingshi.ai.domain.CircleAttachmentKind
import com.xingshi.ai.domain.CirclePost
import com.xingshi.ai.domain.CirclePublishAttachment
import com.xingshi.ai.domain.CirclePublishConflict
import com.xingshi.ai.domain.CirclePublishFailure
import com.xingshi.ai.domain.CirclePublishMedia
import com.xingshi.ai.domain.CirclePublishOutcome
import com.xingshi.ai.domain.CirclePublishRecord
import com.xingshi.ai.domain.CirclePublishRequest
import com.xingshi.ai.domain.CirclePublishState
import com.xingshi.ai.domain.StarCircleGateway
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CircleMediaPreparation {
    suspend fun describe(media: CirclePublishMedia): CirclePrepareResult

    suspend fun describe(
        media: CirclePublishMedia,
        saved: List<CircleVideoSegment>,
        onSegment: suspend (List<CircleVideoSegment>) -> Unit
    ): CirclePrepareResult = describe(media)

    object Unavailable : CircleMediaPreparation {
        override suspend fun describe(media: CirclePublishMedia) = CirclePrepareResult.Failed(
            if (media.kind == CircleAttachmentKind.IMAGE) "ui_circle_vision_unavailable" else "ui_circle_video_unavailable"
        )
    }
}

data class CircleVideoSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val providerId: String = "",
    val model: String = "",
    val promptVersion: Int = 0,
    val fileKey: String = "",
    val endpoint: String = ""
)

object CircleVideoCheckpoint {
    private const val PREFIX = "circle-video-v1;"

    private const val IDENTITY_SEPARATOR = "\u0000"

    fun isCheckpoint(text: String) = text.startsWith(PREFIX)

    fun decode(text: String): List<CircleVideoSegment> {
        if (!isCheckpoint(text)) return emptyList()
        return text.removePrefix(PREFIX).split(';').mapNotNull { entry ->
            val parts = entry.split(':', limit = 4)
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (parts.size == 3) {
                val description = decoded(parts[2]) ?: return@mapNotNull null
                CircleVideoSegment(start, end, description)
            } else {
                val identity = decoded(parts.getOrNull(2) ?: return@mapNotNull null)?.split(IDENTITY_SEPARATOR)
                    ?: return@mapNotNull null
                val description = decoded(parts.getOrNull(3) ?: return@mapNotNull null) ?: return@mapNotNull null
                CircleVideoSegment(
                    start, end, description,
                    providerId = identity.getOrNull(0).orEmpty(),
                    model = identity.getOrNull(1).orEmpty(),
                    promptVersion = identity.getOrNull(2)?.toIntOrNull() ?: 0,
                    fileKey = identity.getOrNull(3).orEmpty(),
                    endpoint = identity.getOrNull(4).orEmpty()
                )
            }
        }
    }

    fun encode(segments: List<CircleVideoSegment>) = PREFIX + segments.sortedBy { it.startMs }.joinToString(";") {
        val identity = listOf(it.providerId, it.model, it.promptVersion.toString(), it.fileKey, it.endpoint)
            .joinToString(IDENTITY_SEPARATOR)
        "${it.startMs}:${it.endMs}:${encoded(identity)}:${encoded(it.text)}"
    }

    private fun decoded(value: String) = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()

    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}

sealed interface CirclePrepareResult {
    data class Described(val text: String) : CirclePrepareResult
    data class Failed(val failureKey: String) : CirclePrepareResult
}

interface CircleMediaPreparationProvider {
    fun create(): CircleMediaPreparation
}

interface CircleLocalMedia {
    fun associate(accountId: String, postId: String, media: List<CirclePublishMedia>)
    fun paths(accountId: String, postId: String): List<String>

    class Memory : CircleLocalMedia {
        private val values = mutableMapOf<String, List<CirclePublishMedia>>()
        override fun associate(accountId: String, postId: String, media: List<CirclePublishMedia>) { values["$accountId/$postId"] = media }
        override fun paths(accountId: String, postId: String) = values["$accountId/$postId"].orEmpty().map { it.path }
    }
}

class CircleLocalMediaOwner(private val root: File) : CircleLocalMedia {
    override fun associate(accountId: String, postId: String, media: List<CirclePublishMedia>) {
        val dir = directory(accountId, postId)
        if (!dir.isDirectory && !dir.mkdirs()) error("无法保存星圈本地媒体")
        media.forEachIndexed { index, item ->
            val source = File(item.path)
            if (!source.isFile) return@forEachIndexed
            val dest = File(dir, "%02d_%s".format(index + 1, safeName(item.fileName)))
            if (dest.exists()) dest.delete()
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
        }
    }

    override fun paths(accountId: String, postId: String): List<String> =
        directory(accountId, postId).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.map { it.absolutePath }.orEmpty()

    private fun directory(accountId: String, postId: String) = File(File(root, encoded(accountId)), encoded(postId))
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80).ifBlank { "media" }
}

object CirclePublishRules {
    fun kindOf(mimeType: String): CircleAttachmentKind? = when {
        mimeType.lowercase().startsWith("image/") -> CircleAttachmentKind.IMAGE
        mimeType.lowercase().startsWith("video/") -> CircleAttachmentKind.VIDEO
        else -> null
    }

    fun validate(body: String, media: List<CirclePublishMedia>): String? = when {
        body.isBlank() && media.isEmpty() -> "ui_circle_publish_empty"
        media.any { kindOf(it.mimeType) == null } -> "ui_circle_publish_unsupported"
        media.any { it.kind == CircleAttachmentKind.IMAGE } && media.any { it.kind == CircleAttachmentKind.VIDEO } -> "ui_circle_publish_mixed"
        media.count { it.kind == CircleAttachmentKind.VIDEO } > 1 -> "ui_circle_publish_one_video"
        media.count { it.kind == CircleAttachmentKind.IMAGE } > 9 -> "ui_circle_publish_too_many_images"
        media.any { it.fileName.isBlank() || it.path.isBlank() } -> "ui_circle_publish_media_missing"
        else -> null
    }
}

internal class CirclePublisher(
    private val scope: CoroutineScope,
    private val gateway: () -> StarCircleGateway?,
    private val preparation: CircleMediaPreparation,
    private val localMedia: CircleLocalMedia,
    private val state: MutableStateFlow<StarCircleState>,
    private val upsertPost: (CirclePost) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val publishing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lock = Mutex()

    fun open() = state.update { it.copy(publishOpen = true, publishFailure = null) }

    fun close() {
        if (state.value.publishing) return
        state.update { it.copy(publishOpen = false, publishFailure = null) }
    }

    fun editText(text: String) {
        if (state.value.publishing) return
        state.update { it.copy(publishText = text, publishFailure = null) }
    }

    fun add(path: String, fileName: String, mimeType: String): String? {
        if (state.value.publishing) return "ui_circle_publish_busy"
        val kind = CirclePublishRules.kindOf(mimeType) ?: return "ui_circle_publish_unsupported"
        val media = state.value.publishMedia
        val failure = when {
            kind == CircleAttachmentKind.IMAGE && media.any { it.kind == CircleAttachmentKind.VIDEO } -> "ui_circle_publish_mixed"
            kind == CircleAttachmentKind.VIDEO && media.any { it.kind == CircleAttachmentKind.IMAGE } -> "ui_circle_publish_mixed"
            kind == CircleAttachmentKind.VIDEO && media.isNotEmpty() -> "ui_circle_publish_one_video"
            kind == CircleAttachmentKind.IMAGE && media.size >= 9 -> "ui_circle_publish_too_many_images"
            else -> null
        }
        if (failure != null) return failure
        state.update {
            it.copy(
                publishMedia = it.publishMedia + CirclePublishMedia(
                    id = UUID.randomUUID().toString(), kind = kind, fileName = fileName,
                    mimeType = mimeType, path = path
                ),
                publishFailure = null
            )
        }
        return null
    }

    fun remove(id: String) {
        if (state.value.publishing) return
        val current = state.value
        val removed = current.publishMedia.firstOrNull { it.id == id } ?: return
        state.update { it.copy(publishMedia = it.publishMedia.filterNot { item -> item.id == id }, publishFailure = null) }
        scope.launch(io) { runCatching { File(removed.path).delete() } }
    }

    fun submit() {
        if ((!state.value.publishOpen && !state.value.publishPending) || !publishing.compareAndSet(false, true)) return
        state.update { it.copy(publishing = true, publishFailure = null, publishProgress = null) }
        scope.launch {
            try {
                withContext(io) {
                    lock.withLock {
                        val service = gateway() ?: throw UnavailablePublish()
                        val account = state.value.viewer?.id ?: throw UnavailablePublish()
                        val pending = service.pendingPublishes().filter { it.accountId == account }
                        pending.firstOrNull { it.state == CirclePublishState.READY }?.let {
                            submitRecord(service, it)
                            return@withLock
                        }
                        if (!state.value.publishOpen) {
                            val record = pending.firstOrNull { it.state == CirclePublishState.PREPARING } ?: return@withLock
                            submitRecord(service, prepare(service, record) ?: return@withLock)
                            return@withLock
                        }
                        val body = state.value.publishText.takeIf { it.isNotBlank() }.orEmpty()
                        val media = state.value.publishMedia
                        val resumePending = pending.firstOrNull { it.state == CirclePublishState.PREPARING }
                        if (body.isEmpty() && media.isEmpty() && resumePending != null) {
                            submitRecord(service, prepare(service, resumePending) ?: return@withLock)
                            return@withLock
                        }
                        CirclePublishRules.validate(body, media)?.let {
                            state.update { current -> current.copy(publishFailure = it) }
                            return@withLock
                        }
                        var record = pending.firstOrNull { it.state == CirclePublishState.PREPARING && it.matches(body, media) }
                        if (record == null) {
                            pending.filter { it.state == CirclePublishState.PREPARING }.forEach { service.completePublish(it.operationId) }
                            record = CirclePublishRecord(UUID.randomUUID().toString(), account, body, media)
                            service.savePublish(record)
                        }
                        submitRecord(service, prepare(service, record) ?: return@withLock)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.update { it.copy(publishFailure = failureKey(failure)) }
            } finally {
                state.update { it.copy(publishing = false, publishProgress = null) }
                publishing.set(false)
            }
        }
    }

    suspend fun resume(records: List<CirclePublishRecord>) {
        val account = state.value.viewer?.id ?: return
        val owned = records.filter { it.accountId == account }
        if (owned.isEmpty() || !publishing.compareAndSet(false, true)) return
        state.update { it.copy(publishing = true) }
        try {
            withContext(io) {
                lock.withLock {
                    val service = gateway() ?: return@withLock
                    var prepared = false
                    for (record in owned) {
                        if (record.state == CirclePublishState.PREPARING) {
                            if (prepare(service, record) != null) prepared = true
                        } else prepared = true
                    }
                    if (prepared) state.update { it.copy(publishPending = true, publishFailure = null) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.update { it.copy(publishFailure = failureKey(failure)) }
        } finally {
            state.update { it.copy(publishing = false, publishProgress = null) }
            publishing.set(false)
        }
    }

    private suspend fun prepare(service: StarCircleGateway, initial: CirclePublishRecord): CirclePublishRecord? {
        var record = initial
        for ((index, item) in record.media.withIndex()) {
            val checkpoint = item.kind == CircleAttachmentKind.VIDEO && CircleVideoCheckpoint.isCheckpoint(item.text)
            if (item.text.isNotBlank() && !checkpoint) continue
            if (!File(item.path).isFile) {
                state.update { it.copy(publishFailure = "ui_circle_publish_media_missing") }
                return null
            }
            state.update { it.copy(publishProgress = "${index + 1}/${record.media.size}") }
            val result = preparation.describe(item, CircleVideoCheckpoint.decode(item.text)) { segments ->
                record = record.copy(
                    media = record.media.map {
                        if (it.id == item.id) it.copy(text = CircleVideoCheckpoint.encode(segments)) else it
                    }
                )
                service.savePublish(record)
            }
            when (result) {
                is CirclePrepareResult.Failed -> {
                    state.update { it.copy(publishFailure = result.failureKey) }
                    return null
                }
                is CirclePrepareResult.Described -> {
                    val text = result.text.trim()
                    if (text.isEmpty()) {
                        state.update {
                            it.copy(
                                publishFailure = if (item.kind == CircleAttachmentKind.IMAGE) "ui_circle_vision_unavailable"
                                else "ui_circle_video_unavailable"
                            )
                        }
                        return null
                    }
                    record = record.copy(media = record.media.map { if (it.id == item.id) it.copy(text = text) else it })
                    service.savePublish(record)
                }
            }
        }
        return record
    }

    private fun CirclePublishRecord.frozen() = copy(
        state = CirclePublishState.READY,
        request = request ?: CirclePublishRequest(
            operationId, body,
            media.map { CirclePublishAttachment(it.kind, it.fileName, it.text) }
        )
    )

    private suspend fun submitRecord(service: StarCircleGateway, original: CirclePublishRecord) {
        val record = original.frozen()
        when (val outcome = service.publish(record)) {
            is CirclePublishOutcome.Published -> {
                val post = outcome.post
                localMedia.associate(record.accountId, post.id, record.media)
                service.completePublish(record.operationId)
                upsertPost(post)
                state.update { current ->
                    if (current.publishText.takeIf { it.isNotBlank() }.orEmpty() == record.body && current.publishMedia.map { it.id } == record.media.map { it.id }) {
                        current.copy(publishOpen = false, publishText = "", publishMedia = emptyList(), publishFailure = null, publishPending = false)
                    } else current.copy(publishFailure = null, publishPending = false)
                }
            }
            is CirclePublishOutcome.Failed -> state.update {
                it.copy(
                    publishFailure = when (outcome.failure) {
                        CirclePublishFailure.CONFLICT -> "ui_circle_publish_conflict"
                        CirclePublishFailure.REJECTED -> "ui_circle_publish_rejected"
                        CirclePublishFailure.UNCONFIRMED -> "ui_circle_publish_failed"
                    },
                    publishPending = if (outcome.failure == CirclePublishFailure.UNCONFIRMED) it.publishPending else false
                )
            }
        }
    }

    private fun failureKey(failure: Exception) = when (failure) {
        is CirclePublishConflict -> "ui_circle_publish_conflict"
        else -> "ui_circle_publish_failed"
    }

    private fun CirclePublishRecord.matches(body: String, media: List<CirclePublishMedia>) =
        this.body == body && this.media.map { it.id } == media.map { it.id } && this.media.map { it.path } == media.map { it.path }

    private class UnavailablePublish : IllegalStateException("星圈服务尚未连接")
}
