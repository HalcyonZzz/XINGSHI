package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.BanManager
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.common.TimeoutBudgets
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.feature.chat.DocumentAnalysisService
import com.xingshi.ai.feature.chat.VideoAnalysisService
import com.xingshi.ai.feature.chat.VisionAnalysisService
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.plugins.api.ChannelSendResult
import com.xingshi.ai.plugins.api.PluginAttachment
import com.xingshi.ai.plugins.api.PluginAttachmentKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.room.withTransaction

object ConversationIntake {

    enum class Source { LOCAL, CHANNEL, PLUGIN }

    enum class InputKind { TEXT, VOICE_MESSAGE, VOICE_CALL, VIDEO_CALL }

    enum class Rejection { BANNED, BLOCKED, QUEUE_FULL, INVALID, ATTACHMENT_FAILED, CANCELLED }

    data class Turn(
        val companionId: Long,
        val text: String,
        val source: Source,
        val request: ChannelRequestRef? = null,
        val inputKind: InputKind = InputKind.TEXT,
        val visionObservation: String? = null,
        val callId: String? = null,
        val durationMs: Long? = null,
        val capturedAt: Long = System.currentTimeMillis(),
        val accountPartition: String = com.xingshi.ai.common.AccountStorage.partition
    ) {
        private val identityReady = CompletableDeferred<Long>()

        @Volatile
        private var directUserMessageId: Long = 0L

        var userMessageId: Long
            get() = directUserMessageId
            set(value) {
                directUserMessageId = value
                if (value > 0L) identityReady.complete(value)
            }

        suspend fun awaitUserMessageId(): Long {
            val known = directUserMessageId
            return if (known > 0L) known else identityReady.await()
        }

        fun failUserMessageId(reason: String = "消息保存失败") {
            if (!identityReady.isCompleted) {
                identityReady.completeExceptionally(IllegalStateException(reason))
            }
        }
    }

    data class Outcome(
        val accepted: Boolean,
        val rejection: Rejection? = null,
        val reason: String? = null,
        val userMessageId: Long = 0L,
        val failureKey: String? = null
    )

    val pipeline = MessagePipelineRunner { _ -> }

    private val queues = ConcurrentHashMap<Long, Channel<Turn>>()
    private val depths = ConcurrentHashMap<Long, MutableStateFlow<Int>>()

    fun stopAccount() { queues.values.forEach { it.cancel() }; queues.clear(); depths.clear() }

    private fun queue(companionId: Long): Channel<Turn> =
        queues.getOrPut(companionId) { Channel(capacity = 100) }

    fun depth(companionId: Long): StateFlow<Int> =
        depths.getOrPut(companionId) { MutableStateFlow(0) }.asStateFlow()

    fun receive(companionId: Long): ReceiveChannel<Turn> = queue(companionId)

    fun consumed(companionId: Long) {
        depths[companionId]?.update { maxOf(0, it - 1) }
    }

    fun enqueueAccepted(
        companionId: Long,
        text: String,
        source: Source = Source.PLUGIN,
        request: ChannelRequestRef? = null,
        inputKind: InputKind = InputKind.TEXT,
        visionObservation: String? = null,
        callId: String? = null,
        durationMs: Long? = null,
        capturedAt: Long = System.currentTimeMillis(),
        onTurn: ((Turn) -> Unit)? = null,
        accountPartition: String = com.xingshi.ai.common.AccountStorage.partition
    ): Boolean {
        if (accountPartition != com.xingshi.ai.common.AccountStorage.partition) return false
        val turn = Turn(companionId, text, source, request, inputKind, visionObservation, callId, durationMs, capturedAt, accountPartition)
        onTurn?.invoke(turn)
        val sent = queue(companionId).trySend(turn).isSuccess
        if (sent) {
            depths[companionId]?.update { it + 1 }
            InboundTurnDispatcher.ensureConsuming(companionId)
        }
        return sent
    }

    suspend fun submit(
        app: Application,
        companionId: Long,
        text: String,
        attachments: List<PluginAttachment> = emptyList(),
        draftAttachments: List<DraftAttachment> = emptyList(),
        source: Source,
        request: ChannelRequestRef? = null,
        enqueueText: String? = null,
        guard: com.xingshi.ai.plugins.PluginSendGuard? = null,
        presetUserMessage: ChatMessage? = null,
        inputKind: InputKind = InputKind.TEXT,
        visionObservation: String? = null,
        callId: String? = null,
        durationMs: Long? = null,
        capturedAt: Long = System.currentTimeMillis(),
        existingUserMessageId: Long? = null,
        accountPartition: String = com.xingshi.ai.common.AccountStorage.partition
    ): Outcome = withContext(Dispatchers.IO) {
        if (accountPartition != com.xingshi.ai.common.AccountStorage.partition) throw com.xingshi.ai.network.StaleBackendSession()
        val backendConsumer = com.xingshi.ai.domain.ServiceRegistry.get(BackendChatConsumer::class.java)
        if (text.isBlank() && attachments.isEmpty() && draftAttachments.isEmpty()) {
            return@withContext Outcome(false, Rejection.INVALID, "空消息")
        }
        if (BanManager.isBanned(app)) {
            return@withContext Outcome(false, Rejection.BANNED, banMessage(app))
        }
        val database = AppDatabase.getDatabase(app)
        val backendLink = database.backendHandoffDao().link(companionId)
        if (backendLink?.deletingOperationId != null || backendLink?.let {
                app.getSharedPreferences("backend_handoffs", android.content.Context.MODE_PRIVATE).contains("deleted:${it.instanceId}")
            } == true) {
            return@withContext Outcome(false, Rejection.INVALID, "助手删除尚未完成，已暂停新输入")
        }
        val companions = CompanionRepository(database.companionDao())
        val companion = runCatching { companions.getCompanionById(companionId) }.getOrNull()
            ?: return@withContext Outcome(false, Rejection.INVALID, "目标角色不存在")
        if (existingUserMessageId != null && database.chatMessageDao().getMessageById(existingUserMessageId)?.companionId != companionId) {
            return@withContext Outcome(false, Rejection.INVALID, "原附件输入身份不存在")
        }

        if (text.isNotBlank()) {
            val passed = try {
                withTimeoutOrNull(TimeoutBudgets.PIPELINE_EXECUTE_MS) {
                    pipeline.execute(MessagePipeline.PipelineInput(rawText = text, companionId = companionId))
                }
            } catch (_: Exception) {
                false
            }
            if (passed != true) {
                return@withContext Outcome(
                    false,
                    Rejection.BLOCKED,
                    pipeline.pipelineState.value.error ?: "安全检查未完成"
                )
            }
        }

        if (accountPartition != com.xingshi.ai.common.AccountStorage.partition) throw com.xingshi.ai.network.StaleBackendSession()
        val chatRepository = ChatRepository(database.chatMessageDao())
        if (attachments.isNotEmpty() || draftAttachments.isNotEmpty()) {
            val prepared = if (draftAttachments.isNotEmpty()) {
                draftAttachments.map {
                    PreparedAttachment(it.kind, it.path, it.fileName, it.mimeType, sourceUrl = it.sourceUrl)
                }
            } else {
                attachments.map { it.toPrepared() }
            }
            return@withContext submitAttachmentBatch(app, companion.id, text, prepared, database, backendConsumer,
                request, capturedAt, accountPartition)
        }
        val formalUserMessage = presetUserMessage?.copy(companionId = companion.id)
        val queuedText = enqueueText ?: text
        var queueFull = false
        var queuedTurn: Turn? = null
        val enqueue: () -> Boolean? = {
            if (enqueueAccepted(
                    companion.id, queuedText, source, request, inputKind, visionObservation, callId,
                    durationMs = durationMs, capturedAt = capturedAt,
                    onTurn = { queuedTurn = it }, accountPartition = accountPartition
                )
            ) {
                true
            } else {
                queueFull = true
                null
            }
        }
        val accepted = if (guard == null) enqueue() == true else guard.accept(enqueue) == true
        if (!accepted) {
            return@withContext if (queueFull) {
                Outcome(false, Rejection.QUEUE_FULL, "消息队列已满，请稍后再试")
            } else {
                Outcome(false, Rejection.CANCELLED, "发送已撤销")
            }
        }
        var savedUserMessageId = 0L
        var saveFailure: Throwable? = null
        withContext(NonCancellable) {
            runCatching {
                database.withTransaction {
                savedUserMessageId = existingUserMessageId ?: chatRepository.sendMessage(
                    formalUserMessage ?: ChatMessage(
                        companionId = companion.id,
                        content = text,
                        isFromUser = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
                queuedTurn?.let { turn -> backendConsumer?.persistAccepted(turn, savedUserMessageId) }
                runCatching { companions.updateTimestamp(companion.id) }
                }
            }.onFailure { saveFailure = it }
        }
        if (saveFailure != null || savedUserMessageId <= 0L) {
            queuedTurn?.failUserMessageId("消息保存失败：${saveFailure?.message ?: "未知错误"}")
            val key = saveFailure?.let(::attachmentSubmitFailureKey) ?: "ui_attach_save_failed"
            return@withContext Outcome(false, Rejection.ATTACHMENT_FAILED, failureKey = key)
        }
        queuedTurn?.userMessageId = savedUserMessageId
        Outcome(true, userMessageId = savedUserMessageId)
    }

    suspend fun submitDraftAttachments(
        app: Application,
        companionId: Long,
        text: String,
        attachments: List<DraftAttachment>,
        capturedAt: Long = System.currentTimeMillis(),
        accountPartition: String = com.xingshi.ai.common.AccountStorage.partition
    ): Outcome = submit(app = app, companionId = companionId, text = text, source = Source.LOCAL,
        draftAttachments = attachments, capturedAt = capturedAt, accountPartition = accountPartition)

    private suspend fun submitAttachmentBatch(
        app: Application,
        companionId: Long,
        text: String,
        items: List<PreparedAttachment>,
        database: AppDatabase,
        backendConsumer: BackendChatConsumer?,
        request: ChannelRequestRef?,
        capturedAt: Long,
        accountPartition: String
    ): Outcome {
        if (items.size > AttachmentPreparation.MAX_ATTACHMENT_COUNT) {
            return Outcome(false, Rejection.ATTACHMENT_FAILED, failureKey = AttachmentPreparation.OVERSIZED_KEY)
        }
        val readinessFailure = attachmentReadinessFailure(app, items)
        if (readinessFailure != null) {
            return Outcome(false, Rejection.ATTACHMENT_FAILED, failureKey = readinessFailure)
        }
        val consumer = backendConsumer ?: return Outcome(false, Rejection.INVALID, failureKey = "ui_attach_failed")
        var savedAttachments = emptyList<Pair<PreparedAttachment, File?>>()
        var failureKey: String? = null
        var identity = 0L
        val persisted = mutableListOf<Pair<PreparedAttachment, File?>>()
        try {
            for (item in items) {
                if (item.kind == DraftAttachmentKind.BILIBILI) {
                    persisted += item to null
                    continue
                }
                val persistedCopy = persistAttachment(app.filesDir, item.path, item.kind)
                val dest = persistedCopy.file
                if (dest == null) {
                    failureKey = persistedCopy.failureKey
                    break
                }
                persisted += item.copy(path = dest.absolutePath) to dest
            }
        } catch (t: Throwable) {
            persisted.forEach { (_, file) -> file?.let { runCatching { it.delete() } } }
            throw t
        }
        savedAttachments = persisted
        if (failureKey == null) {
            withContext(NonCancellable) {
                val chatRepository = ChatRepository(database.chatMessageDao())
                runCatching {
                    database.withTransaction {
                        identity = chatRepository.sendMessage(MessageAttachments.userMessage(
                            companionId = companionId,
                            text = text,
                            timestamp = capturedAt,
                            items = persisted.map { (item, dest) -> item.toStored(dest) }
                        ))
                        check(identity > 0L) { "附件输入身份未建立" }
                        consumer.prepareAttachments(companionId, identity, request)
                        runCatching { CompanionRepository(database.companionDao()).updateTimestamp(companionId) }
                    }
                }.onFailure {
                    SecureLog.e("ConversationIntake", "attachment submit failed: ${it.javaClass.simpleName}", it)
                    failureKey = attachmentSubmitFailureKey(it)
                }
            }
        }
        val rejected = failureKey
        if (rejected != null) {
            savedAttachments.forEach { (_, file) -> file?.let { runCatching { it.delete() } } }
            return Outcome(false, Rejection.ATTACHMENT_FAILED, failureKey = rejected)
        }
        if (accountPartition != com.xingshi.ai.common.AccountStorage.partition) throw com.xingshi.ai.network.StaleBackendSession()
        consumer.wake()
        return Outcome(true, userMessageId = identity)
    }

    private suspend fun attachmentReadinessFailure(app: Application, items: List<PreparedAttachment>): String? {
        if (items.any { it.kind == DraftAttachmentKind.IMAGE }) {
            if (!VisionAnalysisService.isPluginInstalled()) return "ui_attach_vision_missing"
            val config = ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao()).getVisionConfig()
            if (config == null || config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                return "ui_call_vision_config"
            }
        }
        if (items.any { it.kind == DraftAttachmentKind.DOCUMENT } && !DocumentAnalysisService.isPluginInstalled()) {
            return "ui_doc_plugin_missing"
        }
        if (items.any { it.kind == DraftAttachmentKind.BILIBILI }) {
            if (com.xingshi.ai.plugins.PluginFramework.manager()?.bilibiliSourcePlugin() == null) {
                return "ui_bilibili_plugin_missing"
            }
            if (!VideoAnalysisService.isPluginInstalled()) return "ui_video_plugin_missing"
            val config = ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao()).getVideoConfig()
            if (config == null || config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                return "ui_call_video_config"
            }
        }
        if (items.any { it.kind == DraftAttachmentKind.VIDEO }) {
            if (!VideoAnalysisService.isPluginInstalled()) return "ui_video_plugin_missing"
            val config = ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao()).getVideoConfig()
            if (config == null || config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                return "ui_call_video_config"
            }
        }
        return null
    }

    suspend fun deliverReply(turn: Turn, text: String): ChannelSendResult? {
        val request = turn.request ?: return null
        return com.xingshi.ai.plugins.PluginChannels.deliverReply(turn.companionId, request, text)
    }

    private data class PersistedAttachment(val file: File?, val failureKey: String? = null)

    private suspend fun persistAttachment(accountFiles: File, path: String, kind: DraftAttachmentKind): PersistedAttachment {
        val source = File(path)
        if (!source.isFile) return PersistedAttachment(null, "ui_attach_copy_failed")
        val dir = File(accountFiles, "attachments/inbound").apply { mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}_${nextAttachmentSeq()}_${source.name}")
        return when (val result = AttachmentCopy.copy(source, dest, kind)) {
            AttachmentCopyResult.Copied -> PersistedAttachment(dest)
            else -> PersistedAttachment(null, attachmentCopyFailureKey(result))
        }
    }

    private val attachmentSeq = java.util.concurrent.atomic.AtomicLong()
    private fun nextAttachmentSeq(): Long = attachmentSeq.incrementAndGet()

    private fun PluginAttachment.toPrepared(): PreparedAttachment = PreparedAttachment(
        kind = when {
            kind == PluginAttachmentKind.IMAGE || mimeType.lowercase().startsWith("image/") -> DraftAttachmentKind.IMAGE
            kind == PluginAttachmentKind.VIDEO || mimeType.lowercase().startsWith("video/") -> DraftAttachmentKind.VIDEO
            else -> DraftAttachmentKind.DOCUMENT
        },
        path = path,
        fileName = File(path).name,
        mimeType = mimeType
    )

    private fun banMessage(app: Application): String {
        val banInfo = BanManager.getBanInfo(app)
        return if (banInfo.remainingDays > 0) {
            "账号已被封禁（剩余${banInfo.remainingDays}天${banInfo.remainingHours}小时），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
        } else if (banInfo.remainingHours > 0) {
            "账号已被封禁（剩余${banInfo.remainingHours}小时${banInfo.remainingMinutes}分钟），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
        } else {
            "账号已被封禁，原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。请完成安全答题以解除封禁。"
        }
    }
}

internal fun attachmentCopyFailureKey(result: AttachmentCopyResult): String = when (result) {
    AttachmentCopyResult.TooLarge -> "ui_attach_too_large"
    else -> "ui_attach_copy_failed"
}

internal fun attachmentSubmitFailureKey(failure: Throwable): String = when {
    failure is com.xingshi.ai.network.StaleBackendSession -> "ui_attach_account_switched"
    failure is AttachmentCapacityExceeded -> AttachmentPreparation.OVERSIZED_KEY
    failure is com.xingshi.ai.database.repository.ChatMessageCapacityExceeded ->
        com.xingshi.ai.database.repository.MESSAGE_BODY_OVERSIZED_KEY
    else -> "ui_attach_save_failed"
}
