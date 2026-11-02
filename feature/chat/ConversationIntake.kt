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

    /** 输入方式与来源分开保存：来源表达页面/插件/外部通道，输入方式表达文字/语音消息/语音或视频通话。 */
    enum class InputKind { TEXT, VOICE_MESSAGE, VOICE_CALL, VIDEO_CALL }

    enum class Rejection { BANNED, BLOCKED, QUEUE_FULL, INVALID, ATTACHMENT_FAILED, CANCELLED, STORAGE_FAILED }

    data class Turn(
        val companionId: Long,
        val text: String,
        val source: Source,
        /** 原通道请求归属：随接受、排队与回复交接走，不拼进模型正文。 */
        val request: ChannelRequestRef? = null,
        val inputKind: InputKind = InputKind.TEXT,
        /** 同轮视觉观察：与原话分开表达，不拼进模型正文冒充对方说的话。 */
        val visionObservation: String? = null,
        /** 通话来源的稳定 call_id：语音/视频共用，迟到的回复仍归属原通话。 */
        val callId: String? = null,
        val durationMs: Long? = null,
        val capturedAt: Long = System.currentTimeMillis(),
        val accountPartition: String = com.xingshi.ai.common.AccountStorage.partition
    ) {
        /** 正式输入身份就绪信号：消费者必须先取得非零 id，不能把 0 当成正式归属。 */
        private val identityReady = CompletableDeferred<Long>()

        @Volatile
        private var directUserMessageId: Long = 0L

        /** 接受后落库得到的本人消息 id，供正式回复按原输入轮次归属；未落库前为 0。 */
        var userMessageId: Long
            get() = directUserMessageId
            set(value) {
                directUserMessageId = value
                if (value > 0L) identityReady.complete(value)
            }

        /** 消费端入口：等待落库身份；保存失败时抛出，由消费者明确收尾而不是按 0 完成。 */
        suspend fun awaitUserMessageId(): Long {
            val known = directUserMessageId
            return if (known > 0L) known else identityReady.await()
        }

        /** 落库失败：结束身份等待，避免消费者把该轮当成身份未知却继续处理。 */
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
        /** 已接受时落库的本人消息 id，供公共元数据绑定同一消息。 */
        val userMessageId: Long = 0L
    )

    /**
     * 通道与页面共用同一条检查流水线。
     * 本机只保留「本次检查」三态（通过 / 拦截 / 未完成），不自动累计多日封禁；
     * 针对恶意行为的封禁权保留在开发者侧，不把本地自动处罚当作默认行为。
     */
    val pipeline = MessagePipelineRunner { _ -> }

    private val queues = ConcurrentHashMap<Long, Channel<Turn>>()
    private val depths = ConcurrentHashMap<Long, MutableStateFlow<Int>>()

    fun stopAccount() { queues.values.forEach { it.cancel() }; queues.clear(); depths.clear() }

    private fun queue(companionId: Long): Channel<Turn> =
        queues.getOrPut(companionId) { Channel(capacity = 100) }

    fun depth(companionId: Long): StateFlow<Int> =
        depths.getOrPut(companionId) { MutableStateFlow(0) }.asStateFlow()

    fun receive(companionId: Long): ReceiveChannel<Turn> = queue(companionId)

    /** 消费者取走一条后同步深度；队列本身跨页面保留。 */
    fun consumed(companionId: Long) {
        depths[companionId]?.update { maxOf(0, it - 1) }
    }

    /** 框架的插件发送口已落库后入队；返回 false 表示队列满，框架按未提交保留材料。 */
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
        // 身份/责任必须在对外发布前交给接受方；消费者可能在 trySend 后立即恢复。
        onTurn?.invoke(turn)
        val sent = queue(companionId).trySend(turn).isSuccess
        if (sent) {
            depths[companionId]?.update { it + 1 }
            // 未打开聊天页时也要有消费者：应用级消费循环随入队按需启动，页面不重复创建。
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
        /** 入队文本与落库文本可不同：页面路径落原话、入队合并后的插件结果。 */
        enqueueText: String? = null,
        /** 框架发送口的接受守卫：应用级入口与页面入口共用同一条取消/入队合同。 */
        guard: com.xingshi.ai.plugins.PluginSendGuard? = null,
        /** 已落库形态的本人消息（如语音：正文为转写、类型仍为 VOICE）；提供时不再另写一条文字消息。 */
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
        // 所有入口共用同一份正式本人消息形态：语音保留 VOICE/原件，通话保留 call_id 归属。
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
        // 已接受边界内的落库不可取消：取消不能把已入队消息变成半写状态
        var savedUserMessageId = 0L
        var savedAttachments = emptyList<Pair<PluginAttachment, File>>()
        var attachmentFailure: String? = null
        // 视觉插件接管本轮时不再单独提交裸标记/原文轮次，回复由唯一消费轮次交回原请求。
        var visionStarted = false
        withContext(NonCancellable) {
            // 先保存原件：原件不存在/复制失败时不得排入媒体标记、也不得启动视觉请求
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
                // 视觉链路只启动一次：同一入站不能既排原文/裸标记轮次又排视觉结果轮次，
                // 两者共用的一次性回复路由只够其中一轮消费。
                persisted.firstOrNull { it.first.kind == PluginAttachmentKind.IMAGE }?.let { (_, dest) ->
                    runCatching {
                        val manager = pluginManager
                        if (manager?.hasImageConsumer() == true) {
                            // 原问题随视觉请求进入同一轮次，不能只保存在本地后另排一轮。
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
        // 视觉结果会经应用级发送口排入唯一一轮；这里再排一轮会让同一请求出现两个回合。
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

    /** 可控回复交接：按原请求路由交回发起通道；本地消息没有外部请求时不占用任何路由。 */
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
