package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.xingshi.ai.common.BanManager
import com.xingshi.ai.common.TimeoutBudgets
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.database.repository.CompanionRepository
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

    enum class Rejection { BANNED, BLOCKED, QUEUE_FULL, INVALID, ATTACHMENT_FAILED, CANCELLED, STORAGE_FAILED }

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
         
        val userMessageId: Long = 0L
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
        val pluginManager = com.xingshi.ai.plugins.PluginFramework.manager()
        val accountFiles = app.filesDir
        if (text.isBlank() && attachments.isEmpty()) {
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
        
        val formalUserMessage = presetUserMessage?.copy(companionId = companion.id)
        if (attachments.isEmpty()) {
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
                return@withContext Outcome(false, Rejection.STORAGE_FAILED, "消息保存失败，请重试")
            }
            queuedTurn?.userMessageId = savedUserMessageId
            return@withContext Outcome(true, userMessageId = savedUserMessageId)
        }
        
        var savedUserMessageId = 0L
        var savedAttachments = emptyList<Pair<PluginAttachment, File>>()
        var attachmentFailure: String? = null
        
        var visionStarted = false
        withContext(NonCancellable) {
            
            val persisted = mutableListOf<Pair<PluginAttachment, File>>()
            for (attachment in attachments) {
                val dest = persistAttachment(accountFiles, attachment)
                if (dest == null) {
                    attachmentFailure = "附件保存失败：${attachment.path.substringAfterLast('/')}"
                    break
                }
                persisted += attachment to dest
            }
            if (attachmentFailure == null) {
                savedAttachments = persisted
                database.withTransaction {
                if (text.isNotBlank()) {
                    savedUserMessageId = chatRepository.sendMessage(
                        formalUserMessage ?: ChatMessage(
                            companionId = companion.id,
                            content = text,
                            isFromUser = true,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                var firstAttachmentId = 0L
                persisted.forEach { (attachment, dest) ->
                    val attachmentId = chatRepository.sendMessage(
                        ChatMessage(
                            companionId = companion.id,
                            content = dest.absolutePath,
                            isFromUser = true,
                            timestamp = System.currentTimeMillis(),
                            type = attachment.type(),
                            fileFormat = when (attachment.kind) {
                                PluginAttachmentKind.IMAGE -> com.xingshi.ai.database.model.FileFormat.IMAGE
                                PluginAttachmentKind.AUDIO -> com.xingshi.ai.database.model.FileFormat.AUDIO
                                PluginAttachmentKind.VIDEO -> com.xingshi.ai.database.model.FileFormat.VIDEO
                                else -> com.xingshi.ai.database.model.FileFormat.FILE
                            },
                            linkString = dest.absolutePath
                        )
                    )
                    if (firstAttachmentId == 0L) firstAttachmentId = attachmentId
                }
                if (savedUserMessageId == 0L) savedUserMessageId = firstAttachmentId
                persisted.firstOrNull { it.first.kind == PluginAttachmentKind.IMAGE }?.let { (_, image) ->
                    backendConsumer?.prepareImage(companion.id, savedUserMessageId, text, image.absolutePath, request)
                }
                }
                
                
                persisted.firstOrNull { it.first.kind == PluginAttachmentKind.IMAGE }?.let { (_, dest) ->
                    runCatching {
                        val manager = pluginManager
                        if (manager?.hasImageConsumer() == true) {
                            
                            manager.startForSentImage(companion.id, dest.absolutePath, request, text, savedUserMessageId)
                            visionStarted = true
                        }
                    }
                }
                runCatching { companions.updateTimestamp(companion.id) }
            } else {
                persisted.forEach { (_, file) -> runCatching { file.delete() } }
            }
        }
        attachmentFailure?.let { reason ->
            return@withContext Outcome(false, Rejection.ATTACHMENT_FAILED, reason)
        }

        if (savedUserMessageId <= 0L) {
            return@withContext Outcome(false, Rejection.STORAGE_FAILED, "消息保存失败，请重试")
        }
        
        if (visionStarted) {
            return@withContext Outcome(true, userMessageId = savedUserMessageId)
        }
        if (savedAttachments.any { it.first.kind == PluginAttachmentKind.IMAGE } &&
            backendConsumer != null) {
            return@withContext Outcome(true, userMessageId = savedUserMessageId)
        }
        val queuedText = enqueueText ?: text.ifBlank { attachmentMarker(savedAttachments.map { it.first }) }
        var queuedTurn: Turn? = null
        val queued = enqueueAccepted(companion.id, queuedText, source, request, inputKind,
            visionObservation, callId, durationMs = durationMs, capturedAt = capturedAt, onTurn = { queuedTurn = it }, accountPartition = accountPartition)
        if (!queued) {
            return@withContext Outcome(false, Rejection.QUEUE_FULL, "消息队列已满，请稍后再试")
        }
        queuedTurn?.userMessageId = savedUserMessageId
        Outcome(true, userMessageId = savedUserMessageId)
    }

     
    suspend fun deliverReply(turn: Turn, text: String): ChannelSendResult? {
        val request = turn.request ?: return null
        return com.xingshi.ai.plugins.PluginChannels.deliverReply(turn.companionId, request, text)
    }

    private suspend fun persistAttachment(accountFiles: File, attachment: PluginAttachment): File? {
        val source = File(attachment.path)
        if (!source.isFile) return null
        val dir = File(accountFiles, "attachments/inbound").apply { mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}_${source.name}")
        return runCatching { source.copyTo(dest, overwrite = true) }.getOrNull()?.let { dest }
    }

    private fun PluginAttachment.type(): MessageType = when (kind) {
        PluginAttachmentKind.IMAGE -> MessageType.IMAGE
        PluginAttachmentKind.AUDIO -> MessageType.VOICE
        PluginAttachmentKind.VIDEO -> MessageType.VIDEO
        PluginAttachmentKind.FILE -> MessageType.FILE
    }

    private fun attachmentMarker(attachments: List<PluginAttachment>): String = when {
        attachments.any { it.kind == PluginAttachmentKind.IMAGE } -> "[图片]"
        attachments.any { it.kind == PluginAttachmentKind.AUDIO } -> "[语音]"
        attachments.any { it.kind == PluginAttachmentKind.VIDEO } -> "[视频]"
        else -> "[文件]"
    }

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
