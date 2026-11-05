package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.room.withTransaction
import com.xingshi.ai.common.AccountStorage
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.*
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.repository.*
import com.xingshi.ai.domain.BackendSessionStore
import com.xingshi.ai.feature.chat.CallTurnHandoff
import com.xingshi.ai.feature.chat.DocumentAnalysisService
import com.xingshi.ai.feature.chat.VideoAnalysisService
import com.xingshi.ai.feature.chat.VisionAnalysisService
import com.xingshi.ai.feature.chat.ChatFailureDisplay
import com.xingshi.ai.feature.chat.ChatStatusDisplay
import com.xingshi.ai.feature.chat.VoiceCallCoordinator
import com.xingshi.ai.network.*
import com.xingshi.ai.plugins.OptionalCapabilityCall
import com.xingshi.ai.plugins.PluginChannels
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.DocumentFailureCode
import com.xingshi.ai.plugins.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID

class BackendChatConsumer(private val app: Application, private val http: BackendHttpClient,
    private val sessions: BackendSessionStore, private val groups: BackendGroupGateway) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val requests = Mutex()
    private val replayLock = Mutex()
    private val inputMonitor = BackendInputMonitor(http)
    private val partition = AccountStorage.partition
    private val generation = sessions.current()?.generation
    private val feedback = ChatFeedbackState()
    val status = feedback.status
    val nextActions = feedback.nextActions
    private val db = AppDatabase.getDatabase(app)
    private val prefs = app.getSharedPreferences("backend_chat_controls", android.content.Context.MODE_PRIVATE)
    private val accountHandoffs = app.getSharedPreferences("backend_handoffs", android.content.Context.MODE_PRIVATE)
    private fun deleted(link: BackendCompanionLink) = link.deletingOperationId != null || accountHandoffs.contains("deleted:${link.instanceId}")
    private val bilibiliWorkRoot: File get() = File(app.cacheDir, "bilibili")
    private var polling: Job? = null
    private val offeredCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val replayJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val preparationJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    @Volatile private var stopped = false
    private fun active() = !stopped && AccountStorage.partition == partition && sessions.current()?.generation == generation
    private fun checkActive() { if (!active()) throw StaleBackendSession() }
    private fun emitStatus(id: Long, value: String, nextAction: String? = null) = feedback.set(id, value, nextAction)
    fun wake() { signal.trySend(Unit) }
    private fun responseFinalizer(companionId: Long) = AiResponseFinalizer(companionId, ChatRepository(db.chatMessageDao()), AppSettingsStore(app), MutableStateFlow(""), MutableStateFlow(false), app)
    fun stop() { stopped = true; scope.cancel(); signal.close(); preparationJobs.clear() }
    suspend fun stopAndJoin() { stop(); scope.coroutineContext[Job]?.join() }
    suspend fun pauseCompanion(companionId: Long, operation: String) = requests.withLock {
        checkActive()
        replayJobs.remove(companionId)?.cancelAndJoin()
        db.backendHandoffDao().link(companionId)?.let { db.backendHandoffDao().saveLink(it.copy(deletingOperationId = operation)) }
    }

    suspend fun forgetCompanion(companionId: Long) = requests.withLock {
        val editor = prefs.edit()
        prefs.all.filterKeys { it.startsWith("end:") }.forEach { (key, value) ->
            if (Json.parseToJsonElement(value as String).jsonObject.number("companion_id") == companionId) editor.remove(key)
        }
        check(editor.commit())
        feedback.forget(companionId)
    }

    fun start() {
        if (polling != null) return
        scope.launch {
            runCatching {
                db.backendHandoffDao().preparingInputs().filter { it.error != null }.forEach {
                    db.backendHandoffDao().updateInput(it.copy(error = null))
                }
            }
        }
        polling = scope.launch {
            if (active()) {
                try {
                    sweepBilibiliWork()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    SecureLog.e("BackendChatConsumer", "bilibili orphan sweep failed", t)
                }
            }
            var idle = 0
            while (isActive && active()) {
                val busy = runCatching { requests.withLock { cycle() } }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    feedback.networkFallback()
                    false
                }
                idle = if (busy) 0 else (idle + 1).coerceAtMost(3)
                val pause = if (busy) 1_000L else listOf(1_000L, 5_000L, 15_000L, 30_000L)[idle]
                if (withTimeoutOrNull(pause) { signal.receive() } != null) idle = 0
            }
        }
    }

    suspend fun accept(turn: ConversationIntake.Turn) {
        if (turn.accountPartition != partition) throw StaleBackendSession()
        checkActive()
        val localId = turn.awaitUserMessageId()
        try {
            persistAccepted(turn, localId)
        } catch (e: AttachmentCapacityExceeded) {
            SecureLog.e("BackendChatConsumer", "inbound handoff rejected: ${e.message}")
            return
        }
        wake()
    }

    suspend fun persistAccepted(turn: ConversationIntake.Turn, localId: Long) = db.withTransaction {
            checkActive()
            check(turn.accountPartition == partition) { "账号已切换" }
            val preparing = db.backendHandoffDao().input(localId)
            if (preparing != null && preparing.state != "PREPARING") return@withTransaction
            val callRound = turn.callId?.let { id ->
                val next = prefs.getLong("round:$id", 0) + 1
                check(prefs.edit().putLong("round:$id", next).commit()); next
            }
            val clientId = preparing?.clientMessageId ?: UUID.randomUUID().toString()
            val sourceId = preparing?.sourceEventId ?: UUID.randomUUID().toString()
            val occurredAt = inputMonitor.occurredAt(db.chatMessageDao().getMessageById(localId)?.timestamp ?: turn.capturedAt)
            val payload = buildJsonObject {
                put("client_message_id", clientId); put("source_event_id", sourceId); put("content", turn.text); put("occurred_at", occurredAt)
                if (preparing?.encryptedRoute?.isNotBlank() == true || turn.request != null) put("reply_route_ref", clientId)
                if (turn.inputKind != ConversationIntake.InputKind.TEXT) put("media", buildJsonObject {
                    put("input_mode", turn.inputKind.name); put("transcript", turn.text)
                    if (turn.inputKind == ConversationIntake.InputKind.VOICE_MESSAGE) put("duration_ms", turn.durationMs ?: 0)
                    turn.callId?.let { put("call_id", it); put("call_round", callRound) }
                    if (turn.inputKind == ConversationIntake.InputKind.VIDEO_CALL) {
                        put("observation", turn.visionObservation.orEmpty()); put("observation_source", "PHONE_CAMERA")
                        put("observation_at", Instant.ofEpochMilli(turn.capturedAt).toString())
                    }
                })
            }
            AttachmentPreparation.payloadRejectionKey(payload)?.let { rejected ->
                emitStatus(turn.companionId, rejected)
                throw AttachmentCapacityExceeded()
            }
            val handoff = BackendInputHandoff(localId, turn.companionId, clientId, sourceId,
                ChatMessageCrypto.encrypt(payload.toString()), preparing?.encryptedRoute ?: turn.request?.let { ChatMessageCrypto.encrypt(routeJson(it).toString()) }.orEmpty(), turn.callId.orEmpty())
            if (preparing == null) db.backendHandoffDao().insertInput(handoff) else db.backendHandoffDao().updateInput(handoff)
            emitStatus(turn.companionId, ChatStatusDisplay.LOCAL_SAVED, "")
    }

    internal suspend fun prepareAttachments(companionId: Long, localId: Long, request: ChannelRequestRef?) {
        checkActive()
        val stored = db.chatMessageDao().getMessageById(localId) ?: error("附件消息身份不存在")
        val message = ChatMessageCrypto.decryptFromStorage(stored)
        val attachments = MessageAttachments.decode(message.linkString)?.map { it.toPrepared() }
            ?: error("附件消息内容不完整")
        val client = UUID.randomUUID().toString(); val source = UUID.randomUUID().toString()
        val occurredAt = inputMonitor.occurredAt(message.timestamp)
        val state = AttachmentPreparation.create(client, source, message.content, occurredAt, attachments)
        val payload = AttachmentPreparation.encode(state)
        AttachmentPreparation.payloadRejectionKey(payload)?.let { rejected ->
            emitStatus(companionId, rejected)
            throw AttachmentCapacityExceeded()
        }
        db.backendHandoffDao().insertInput(BackendInputHandoff(localId, companionId, client, source,
            ChatMessageCrypto.encrypt(payload.toString()),
            request?.let { ChatMessageCrypto.encrypt(routeJson(it).toString()) }.orEmpty(), state = "PREPARING"))
    }

    internal suspend fun retryAttachments(companionId: Long) = requests.withLock {
        checkActive()
        val failed = db.backendHandoffDao().preparingInputs().filter { it.companionId == companionId && it.error != null }
        if (failed.isEmpty()) return@withLock
        feedback.retry(companionId)
        failed.forEach { db.backendHandoffDao().updateInput(it.copy(error = null)) }
        wake()
    }

    private suspend fun cycle(): Boolean {
        checkActive()
        for (input in db.backendHandoffDao().preparingInputs()) {
            checkActive()
            if (input.error != null || preparationJobs.containsKey(input.localMessageId)) continue
            launchPreparation(input)
        }
        val before = db.backendHandoffDao().pendingInputs()
        for (input in before) {
            checkActive()
            submit(input)
        }
        var cursor: String? = null
        var delivered = false
        do {
            val page = http.json("GET", "/api/account/delivery?limit=50" + (cursor?.let { "&cursor=${pathSegment(it)}" } ?: ""))
            for (raw in page.array("items")) {
                checkActive()
                val item = raw.jsonObject
                try {
                    consume(item)
                    http.json("POST", "/api/account/delivery/acknowledgements", buildJsonObject {
                        put("items", buildJsonArray { add(buildJsonObject { put("instance_id", item.text("instance_id")); put("conversation_id", item.text("conversation_id")); put("message_id", item.text("message_id")) }) })
                    })
                    delivered = true
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure is StaleBackendSession) throw failure
                }
            }
            cursor = page.text("next_cursor").ifBlank { null }
        } while (cursor != null)
        for (receipt in db.backendHandoffDao().pendingReceipts()) processFollowUp(receipt, recovering = true)
        flushControls()
        return delivered || db.backendHandoffDao().pendingInputs().isNotEmpty()
    }

    private fun launchPreparation(input: BackendInputHandoff) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                prepare(input)
            } catch (e: PreparationAbandoned) {
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is StaleBackendSession) throw e
                runCatching { failPreparation(input, preparationFailureKey(input)) }
            } catch (e: LinkageError) {
                runCatching { failPreparation(input, preparationFailureKey(input)) }
            } finally {
                preparationJobs.remove(input.localMessageId)
            }
        }
        if (preparationJobs.putIfAbsent(input.localMessageId, job) == null) job.start() else job.cancel()
    }

    private suspend fun preparationFailureKey(input: BackendInputHandoff): String {
        val state = runCatching {
            AttachmentPreparation.decode(Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject)
        }.getOrNull()
        val kinds = state?.items?.map { it.kind }.orEmpty()
        return when {
            kinds.isNotEmpty() && kinds.all { it == DraftAttachmentKind.IMAGE } -> "ui_attach_vision_failed"
            kinds.any { it == DraftAttachmentKind.BILIBILI } -> "ui_bilibili_failed"
            kinds.any { it == DraftAttachmentKind.VIDEO } -> "ui_video_failed"
            else -> "ui_doc_read_failed"
        }
    }

    private suspend fun prepare(input: BackendInputHandoff) {
        checkActive()
        val link = db.backendHandoffDao().link(input.companionId)
        if (link == null) {
            failPreparation(input, "ui_attach_failed")
            db.backendHandoffDao().deleteInputs(input.companionId)
            return
        }
        if (deleted(link)) return reclaimBilibiliWork(input.encryptedPayload)
        val state = AttachmentPreparation.decode(Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject)
            ?: return failPreparation(input, "ui_doc_read_failed")
        AttachmentPreparation.sendRejectionKey(state)?.let { rejected ->
            reclaimBilibiliWork(input.encryptedPayload)
            return failPreparation(input, rejected)
        }
        val outcome = AttachmentPreparation.prepareMissing(
            state = state,
            resolve = { index, item, checkpoint, persistItem -> resolveAttachment(input.localMessageId, index, item, checkpoint, persistItem) },
            persist = { progress ->
                checkActive()
                if (deleted(link)) throw PreparationAbandoned()
                val current = db.backendHandoffDao().input(input.localMessageId) ?: throw PreparationAbandoned()
                db.backendHandoffDao().updateInput(current.copy(encryptedPayload = ChatMessageCrypto.encrypt(AttachmentPreparation.encode(progress).toString())))
                reclaimCommittedBilibiliWork(bilibiliWorkRoot, progress)
            }
        )
        when (outcome) {
            is AttachmentPreparationOutcome.Incomplete -> {
                if (outcome.statusKey == AttachmentPreparation.OVERSIZED_KEY) {
                    val payload = try {
                        db.backendHandoffDao().input(input.localMessageId)?.encryptedPayload
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        null
                    } ?: input.encryptedPayload
                    reclaimBilibiliWork(payload)
                }
                if (active()) failPreparation(input, outcome.statusKey)
            }
            is AttachmentPreparationOutcome.Completed -> {
                checkActive()
                if (deleted(link)) return
                AttachmentPreparation.sendRejectionKey(outcome.state)?.let { rejected ->
                    return failPreparation(input, rejected)
                }
                val current = db.backendHandoffDao().input(input.localMessageId) ?: return
                val missing = outcome.state.items.firstOrNull { it.text.isBlank() }
                val payload = AttachmentPreparation.request(outcome.state, includeRoute = current.encryptedRoute.isNotBlank())
                    ?: return failPreparation(input, AttachmentPreparation.blankTextKey(missing?.kind ?: DraftAttachmentKind.DOCUMENT))
                AttachmentPreparation.payloadRejectionKey(payload)?.let { rejected ->
                    return failPreparation(input, rejected)
                }
                if (db.chatMessageDao().getMessageById(input.localMessageId) == null) {
                    return failPreparation(input, ATTACH_MESSAGE_MISSING_KEY)
                }
                db.backendHandoffDao().updateInput(current.copy(encryptedPayload = ChatMessageCrypto.encrypt(payload.toString()), state = "PENDING", error = null))
                wake()
            }
        }
    }

    private suspend fun resolveAttachment(
        localId: Long,
        index: Int,
        item: PreparedAttachment,
        checkpoint: suspend (List<PreparedSegment>) -> Unit,
        persistItem: suspend (PreparedAttachment) -> Unit
    ): ResolvedAttachment = when (item.kind) {
        DraftAttachmentKind.IMAGE -> {
            val outcome = VisionAnalysisService.analyze(app, item.path, "")
            when {
                outcome is VisionAnalysisService.Outcome.Described && outcome.text.isNotBlank() -> ResolvedAttachment.Text(outcome.text)
                outcome is VisionAnalysisService.Outcome.Described -> ResolvedAttachment.Failed(AttachmentPreparation.blankTextKey(item.kind))
                else -> ResolvedAttachment.Failed(AttachmentPreparation.visionFailureKey(outcome))
            }
        }
        DraftAttachmentKind.DOCUMENT -> {
            val outcome = DocumentAnalysisService.extract(item.path, item.fileName, item.mimeType)
            when {
                outcome is DocumentAnalysisService.Outcome.Extracted && outcome.text.isNotBlank() ->
                    ResolvedAttachment.Text(outcome.text, outcome.format.name)
                outcome is DocumentAnalysisService.Outcome.Extracted ->
                    ResolvedAttachment.Failed(AttachmentPreparation.blankTextKey(item.kind))
                else -> ResolvedAttachment.Failed(AttachmentPreparation.documentOutcomeKey(outcome))
            }
        }
        DraftAttachmentKind.VIDEO -> prepareVideo(item.path, item.fileName, item.mimeType, item.segments, checkpoint)
        DraftAttachmentKind.BILIBILI -> prepareBilibili(localId, index, item, checkpoint, persistItem)
    }

    private suspend fun prepareBilibili(
        localId: Long,
        index: Int,
        item: PreparedAttachment,
        checkpoint: suspend (List<PreparedSegment>) -> Unit,
        persistItem: suspend (PreparedAttachment) -> Unit,
        fetch: suspend (PreparedAttachment, File) -> BilibiliFetchOutcome = ::fetchBilibili
    ): ResolvedAttachment {
        if (item.sourceUrl.isBlank()) return ResolvedAttachment.Failed("ui_bilibili_failed")
        val prepared = if (item.videoPath.isNotBlank()) {
            item
        } else {
            val workDir = BilibiliWorkDir.acquire(bilibiliWorkRoot, localId, index)
            when (val fetched = acquireFetchedBilibili(workDir, item, fetch, persistItem)) {
                is BilibiliFetchOutcome.Failed -> return ResolvedAttachment.Failed(fetched.statusKey)
                is BilibiliFetchOutcome.Ready -> fetched.item
            }
        }
        val outcome = try {
            prepareFetchedBilibili(prepared, checkpoint)
        } catch (t: Throwable) {
            if (t.endsBilibiliWorkResponsibility()) BilibiliWorkDir.reclaim(bilibiliWorkRoot, prepared.videoPath)
            throw t
        }
        val resolved = if (outcome is ResolvedAttachment.Text) {
            attachBilibiliVideo(localId, index, prepared)?.let { ResolvedAttachment.Failed(it) } ?: outcome
        } else {
            outcome
        }
        if (bilibiliReclaimDecision(resolved)) BilibiliWorkDir.reclaim(bilibiliWorkRoot, prepared.videoPath)
        return resolved
    }

    private suspend fun prepareFetchedBilibili(
        item: PreparedAttachment,
        checkpoint: suspend (List<PreparedSegment>) -> Unit
    ): ResolvedAttachment {
        val video = File(item.videoPath)
        if (!video.isFile) return ResolvedAttachment.Failed(BILIBILI_VIDEO_LOST_KEY)
        return prepareVideo(video.absolutePath, video.name, "video/mp4", item.segments, checkpoint)
    }

    private fun reclaimBilibiliWork(encryptedPayload: String) {
        val payload = runCatching { ChatMessageCrypto.decrypt(encryptedPayload) }.getOrNull() ?: return
        reclaimBilibiliWorkFor(bilibiliWorkRoot, listOf(payload))
    }

    suspend fun reclaimBilibiliWorkFor(companionId: Long) {
        reclaimBilibiliWorkFor(bilibiliWorkRoot, storedInputPayloads(companionId))
    }

    private fun sweepBilibiliWork() {
        if (!bilibiliWorkRoot.isDirectory) return
        val messageIds = existingMessageIds()
        sweepOrphanBilibiliWork(
            bilibiliWorkRoot,
            storedInputPayloads(null, existingMessagesOnly = true)
        ) { dir -> keepRetryableBilibiliWork(dir, messageIds, ::hasPreparingInput, BilibiliFetchCache::read) }
    }

    private fun hasPreparingInput(localMessageId: Long): Boolean {
        val sql = "SELECT EXISTS(SELECT 1 FROM backend_input_handoffs WHERE localMessageId = ? AND state = 'PREPARING')"
        return db.openHelper.readableDatabase.query(sql, arrayOf<Any?>(localMessageId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    private fun existingMessageIds(): Set<Long> {
        val sql = "SELECT id FROM chat_messages"
        return db.openHelper.readableDatabase.query(sql, emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    }

    private fun storedInputPayloads(companionId: Long?, existingMessagesOnly: Boolean = false): List<String> {
        val conditions = buildList {
            if (companionId != null) add("companionId = ?")
            if (existingMessagesOnly) {
                add("EXISTS (SELECT 1 FROM chat_messages m WHERE m.id = backend_input_handoffs.localMessageId)")
            }
        }
        val sql = "SELECT encryptedPayload FROM backend_input_handoffs" +
            if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val bindArgs = companionId?.let { arrayOf<Any?>(it) } ?: emptyArray()
        val payloads = db.openHelper.readableDatabase.query(sql, bindArgs).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return payloads.mapNotNull { runCatching { ChatMessageCrypto.decrypt(it) }.getOrNull() }
    }

    private suspend fun attachBilibiliVideo(localId: Long, index: Int, item: PreparedAttachment): String? {
        val source = File(item.videoPath)
        if (!source.isFile) return null
        val stored = db.chatMessageDao().getMessageById(localId) ?: return ATTACH_MESSAGE_MISSING_KEY
        val message = ChatMessageCrypto.decryptFromStorage(stored)
        val dir = File(app.filesDir, "attachments/inbound").apply { mkdirs() }
        return attachBilibiliOriginal(
            message = message,
            index = index,
            source = source,
            destDir = dir,
            copy = { from, to -> AttachmentCopy.copy(from, to, DraftAttachmentKind.BILIBILI) },
            update = { db.chatMessageDao().updateMessage(ChatMessageCrypto.encryptForStorage(it)) }
        )
    }

    private suspend fun fetchBilibili(item: PreparedAttachment, cacheDir: File): BilibiliFetchOutcome {
        val manager = PluginFramework.manager() ?: return BilibiliFetchOutcome.Failed("ui_bilibili_plugin_missing")
        val pluginId = manager.bilibiliSourcePluginId() ?: return BilibiliFetchOutcome.Failed("ui_bilibili_plugin_missing")
        val plugin = manager.bilibiliSourcePlugin() ?: return BilibiliFetchOutcome.Failed("ui_bilibili_plugin_missing")
        val target = when (val resolved = plugin.resolve(item.sourceUrl)) {
            is BilibiliTargetResult.Resolved -> resolved.target
            BilibiliTargetResult.Ambiguous -> return BilibiliFetchOutcome.Failed("ui_bilibili_ambiguous")
            else -> return BilibiliFetchOutcome.Failed("ui_bilibili_failed")
        }
        val version = manager.capabilityVersion(pluginId) ?: return BilibiliFetchOutcome.Failed("ui_bilibili_plugin_missing")
        return runBilibiliFetch(manager, pluginId, version, item) { runtime ->
            plugin.fetch(BilibiliFetchRequest(target, cacheDir.absolutePath), runtime)
        }
    }

    private suspend fun prepareVideo(
        path: String,
        fileName: String,
        mimeType: String,
        savedSegments: List<PreparedSegment>,
        checkpoint: suspend (List<PreparedSegment>) -> Unit
    ): ResolvedAttachment {
        val providerConfig = VideoAnalysisService.providerConfig(
            ApiConfigRepository(db.apiConfigDao()).getVideoConfig()
        ) ?: return ResolvedAttachment.Failed("ui_call_video_config")
        val saved = savedSegments.map {
            VideoAnalysisService.SegmentText(
                startMs = it.startMs,
                endMs = it.endMs,
                text = it.text,
                providerId = it.providerId,
                model = it.model,
                promptVersion = it.promptVersion,
                fileKey = it.fileKey,
                endpoint = it.endpoint
            )
        }
        val collected = saved.toMutableList()
        val sliceDir = File(File(app.cacheDir, "video_segments"), UUID.randomUUID().toString())
        return try {
            val outcome = VideoAnalysisService.prepare(
                path = path,
                fileName = fileName,
                mimeType = mimeType,
                sliceDir = sliceDir.absolutePath,
                config = providerConfig,
                savedSegments = saved
            ) { _, segment ->
                collected.removeAll { it.startMs == segment.startMs && it.endMs == segment.endMs }
                collected += segment
                collected.sortBy { it.startMs }
                checkpoint(collected.map {
                    PreparedSegment(
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.text,
                        providerId = it.providerId,
                        model = it.model,
                        promptVersion = it.promptVersion,
                        fileKey = it.fileKey,
                        endpoint = it.endpoint
                    )
                })
            }
            when {
                outcome is VideoAnalysisService.Outcome.Described && outcome.segments.isNotEmpty() &&
                    outcome.segments.all { it.text.isNotBlank() } -> {
                    val segments = outcome.segments.map {
                        PreparedSegment(
                            startMs = it.startMs,
                            endMs = it.endMs,
                            text = it.text,
                            providerId = it.providerId,
                            model = it.model,
                            promptVersion = it.promptVersion,
                            fileKey = it.fileKey,
                            endpoint = it.endpoint
                        )
                    }
                    ResolvedAttachment.Text(AttachmentPreparation.videoText(segments), segments = segments)
                }
                outcome is VideoAnalysisService.Outcome.Described -> ResolvedAttachment.Failed("ui_video_empty")
                else -> ResolvedAttachment.Failed(AttachmentPreparation.videoOutcomeKey(outcome))
            }
        } finally {
            sliceDir.deleteRecursively()
        }
    }

    private suspend fun failPreparation(input: BackendInputHandoff, statusKey: String) {
        val current = db.backendHandoffDao().input(input.localMessageId) ?: return
        feedback.fail(input.companionId, statusKey)
        db.backendHandoffDao().updateInput(current.copy(error = statusKey))
    }

    internal class PreparationAbandoned : IllegalStateException("附件准备责任已失效")

    private suspend fun submit(input: BackendInputHandoff) {
        val link = db.backendHandoffDao().link(input.companionId) ?: run { emitStatus(input.companionId, ChatStatusDisplay.LINK_MISSING, ""); return }
        if (deleted(link)) return
        try {
            val progress = BackendInputProgress(input.humanMessageId, input.generationId, input.state, input.error)
            if (inputMonitor.needsAcceptance(progress)) groups.synchronizeBeforePrivateChat(input.companionId)
            val body = withoutLegacyRoute(Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject)
            val result = inputMonitor.advance(chatPath(link), body, progress)
            checkActive()
            val current = db.backendHandoffDao().input(input.localMessageId) ?: return
            val presentation = result.failure?.let(ChatFailureDisplay::present)
            db.backendHandoffDao().updateInput(current.copy(humanMessageId = result.humanMessageId,
                generationId = result.generationId, state = result.state, error = presentation?.code?.ifBlank { null }))
            val statusKey = when {
                presentation != null -> presentation.reasonKey
                result.state == "COMPLETED" -> ChatStatusDisplay.RESULT_READY
                result.state == "UNKNOWN" -> ChatStatusDisplay.UNKNOWN_READING
                result.stage == "QUEUED" -> ChatStatusDisplay.QUEUED
                result.stage == "PREPARING" -> ChatStatusDisplay.PREPARING
                result.stage == "GENERATING" -> ChatStatusDisplay.GENERATING
                result.generationId == null -> ChatStatusDisplay.ACCEPTED
                else -> ChatStatusDisplay.GENERATING_ACCEPTED
            }
            val nextAction: String? = when {
                presentation != null || result.state == "FAILED" || result.state == "UNKNOWN" -> when {
                    result.nextAction.isNotBlank() -> result.nextAction
                    else -> presentation?.nextAction.orEmpty().takeUnless { it == ChatFailureDisplay.NONE }.orEmpty()
                }
                result.state == "COMPLETED" -> ""
                else -> null
            }
            emitStatus(input.companionId, statusKey, nextAction)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is StaleBackendSession) throw failure
            if (failure is BackendHttpException && failure.status == 401) throw failure
            val current = db.backendHandoffDao().input(input.localMessageId) ?: return
            val httpStatus = (failure as? BackendHttpException)?.status
            if (httpStatus != null && httpStatus in 400..499 && httpStatus !in setOf(408, 429) || httpStatus == 429) {
                val code = (failure as BackendHttpException).code
                val statusKey = ChatFailureDisplay.reasonKeyForCode(code)
                    ?: if (httpStatus == 429) ChatStatusDisplay.SERVICE_BUSY else ChatStatusDisplay.REQUEST_REJECTED
                db.backendHandoffDao().updateInput(current.copy(state = "FAILED", error = code.ifBlank { null }))
                emitStatus(input.companionId, statusKey, ChatFailureDisplay.nextActionForCode(code))
            } else {
                val statusKey = if (failure is BackendHttpException) ChatStatusDisplay.BACKEND_UNCONFIRMED else ChatStatusDisplay.NETWORK_UNCONFIRMED
                db.backendHandoffDao().updateInput(current.copy(error = null))
                emitStatus(input.companionId, statusKey)
            }
        }
    }

    private fun withoutLegacyRoute(payload: JsonObject): JsonObject =
        if (payload.containsKey("provider") || payload.containsKey("model"))
            JsonObject(payload.filterKeys { it != "provider" && it != "model" }) else payload

    private suspend fun consume(item: JsonObject) {
        checkActive()
        check(item.text("role") == "ASSISTANT") { "不属于正式助手收件" }
        val link = db.backendHandoffDao().linkForInstance(item.text("instance_id")) ?: error("尚未恢复该助手身份")
        check(link.conversationId == item.text("conversation_id") && !deleted(link)) { "收件归属已失效" }
        val messageId = item.text("message_id")
        var receipt = db.backendHandoffDao().receipt(messageId)
        if (receipt == null) {
            val createdAt = epochMillis(item.text("created_at"))
            receipt = if (item.text("scene_kind") == "GROUP") {
                val groupId = groups.localGroup(item.text("group_ref")) ?: error("原群身份无法恢复")
                com.xingshi.ai.domain.ServiceRegistry.getOrThrow(com.xingshi.ai.domain.FormalGroupDeliverySink::class.java)
                    .save(groupId, link.companionId, messageId, item.text("content"), createdAt, item.toString())
                db.backendHandoffDao().receipt(messageId) ?: error("正式群回复未持久保存")
            } else {
                val finalizer = responseFinalizer(link.companionId)
                finalizer.saveFormal(messageId, item.text("content"), createdAt, item.obj("media_input").text("call_id").takeIf { it.isNotBlank() }?.let { "call:$it" }.orEmpty(), item.toString())
            }
        }
        db.withTransaction {
            item.array("input_message_ids").forEach { id -> db.backendHandoffDao().inputForHuman(id.jsonPrimitive.content)?.let { db.backendHandoffDao().updateInput(it.copy(state = "COMPLETED")) } }
        }
        emitStatus(link.companionId, ChatStatusDisplay.REPLY_SAVED, "")
        processFollowUp(receipt, recovering = false)
    }

    private suspend fun processFollowUp(receipt: BackendDeliveryReceipt, recovering: Boolean) {
        checkActive()
        val link = db.backendHandoffDao().link(receipt.companionId) ?: return
        if (deleted(link)) return
        if (receipt.state == "DONE") return
        if (receipt.state == "EXTERNAL_UNKNOWN") { emitStatus(receipt.companionId, ChatStatusDisplay.EXTERNAL_UNKNOWN, ""); return }
        if (receipt.state == "REPLAY_AVAILABLE") { emitStatus(receipt.companionId, ChatStatusDisplay.REPLAY_AVAILABLE, ""); return }
        if (receipt.state == "PLAY_PENDING" && receipt.messageId in offeredCalls) return
        val item = Json.parseToJsonElement(ChatMessageCrypto.decrypt(receipt.encryptedPayload)).jsonObject
        val inputs = item.array("input_message_ids").mapNotNull { id -> db.backendHandoffDao().inputForHuman(id.jsonPrimitive.content) }
        val media = item.obj("media_input")
        val callId = media.text("call_id")
        if (callId.isNotBlank()) {
            if (recovering || VoiceCallCoordinator.active.value?.callId != callId || receipt.state == "PLAY_PENDING") {
                db.backendHandoffDao().updateReceipt(receipt.copy(state = "REPLAY_AVAILABLE"))
            } else {
                db.backendHandoffDao().updateReceipt(receipt.copy(state = "PLAY_PENDING"))
                offeredCalls += receipt.messageId
                inputs.forEach { input -> CallTurnHandoff.publish(CallTurnHandoff.Reply(callId, receipt.companionId, item.text("content"), receipt.localMessageId, inputMessageId = input.localMessageId)) }
            }
            return
        }
        val routed = inputs.filter { it.encryptedRoute.isNotBlank() }
        if (routed.isNotEmpty() && item.text("trigger_kind") != "PROACTIVE") {
            val routes = routed.map { parseRoute(Json.parseToJsonElement(ChatMessageCrypto.decrypt(it.encryptedRoute)).jsonObject) }.distinct()
            if (routes.size != 1) {
                db.backendHandoffDao().updateReceipt(receipt.copy(state = "EXTERNAL_ROUTE_CONFLICT"))
                emitStatus(receipt.companionId, ChatStatusDisplay.ROUTE_CONFLICT, "")
                return
            }
            val state = PluginChannels.deliverFormalReply(receipt.companionId, routes.single(), item.text("content"), receipt.state) { next ->
                checkActive(); db.backendHandoffDao().updateReceipt(receipt.copy(state = next))
            }
            emitStatus(receipt.companionId, when (state) {
                "DONE" -> ChatStatusDisplay.DELIVERED
                "EXTERNAL_UNKNOWN" -> ChatStatusDisplay.EXTERNAL_UNKNOWN
                "EXTERNAL_FAILED" -> ChatStatusDisplay.EXTERNAL_FAILED
                else -> ChatStatusDisplay.CHANNEL_PENDING
            }, "")
            return
        }
        val inputModes = inputs.mapNotNull { input ->
            runCatching {
                Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject.obj("media").text("input_mode")
            }.getOrNull()
        }
        if (AutoSpeechReply.speaksReply(inputModes)) {
            responseFinalizer(receipt.companionId).attachAutoSpeech(receipt.localMessageId, inputModes)?.let { emitStatus(receipt.companionId, it) }
        }
        db.backendHandoffDao().updateReceipt(receipt.copy(state = "DONE"))
    }

    suspend fun cancel(companionId: Long) = requests.withLock {
        checkActive()
        val input = db.backendHandoffDao().pendingInputs().lastOrNull { it.companionId == companionId && it.generationId != null } ?: return@withLock
        val link = db.backendHandoffDao().link(companionId) ?: return@withLock
        http.json("POST", chatPath(link) + "/generations/${pathSegment(input.generationId!!)}/cancel")
        wake()
    }

    suspend fun regenerate(companionId: Long) = requests.withLock {
        checkActive()
        val input = db.backendHandoffDao().failedGeneration(companionId) ?: error("没有明确失败的生成，请先查证原请求")
        val link = db.backendHandoffDao().link(companionId) ?: error("助手身份尚未恢复")
        check(!deleted(link)) { "助手正在删除" }
        val key = "regenerate:${input.generationId}"
        val operation = prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(key, it).commit()) { "无法保存重新生成身份" }
        }
        val result = http.json("POST", chatPath(link) + "/generations/regenerate", buildJsonObject {
            put("generation_id", input.generationId); put("regeneration_request_id", operation)
        })
        checkActive()
        db.backendHandoffDao().updateInput(input.copy(generationId = result.obj("generation").text("generation_id"), state = "ACCEPTED", error = null))
        check(prefs.edit().remove(key).commit())
        emitStatus(companionId, ChatStatusDisplay.REGENERATION_ACCEPTED, "")
        wake()
    }

    suspend fun markCallPlayed(localMessageId: Long) {
        checkActive()
        db.backendHandoffDao().receiptForLocal(localMessageId)?.let { receipt ->
            db.backendHandoffDao().updateReceipt(receipt.copy(state = "DONE")); offeredCalls.remove(receipt.messageId)
        }
    }

    suspend fun replayPendingSpeech(companionId: Long) = scope.async { replayLock.withLock {
        checkActive()
        check(VoiceCallCoordinator.tryBeginReplay()) { "请先结束当前通话或重播" }
        try {
        requests.withLock {
            checkActive()
            check(db.backendHandoffDao().link(companionId)?.let { !deleted(it) } == true) { "助手正在删除" }
            replayJobs[companionId] = currentCoroutineContext()[Job]!!
        }
        val receipt = db.backendHandoffDao().pendingReceipts().firstOrNull { it.companionId == companionId && it.state in setOf("REPLAY_AVAILABLE", "PLAY_PENDING") } ?: error("没有待重播的通话回复")
        check(db.backendHandoffDao().link(companionId)?.let { !deleted(it) } == true) { "助手正在删除" }
        val payload = Json.parseToJsonElement(ChatMessageCrypto.decrypt(receipt.encryptedPayload)).jsonObject
        when (val audio = com.xingshi.ai.feature.chat.TextToSpeechService.synthesize(app, payload.text("content"), java.io.File(app.cacheDir, "voice_replay"))) {
            is com.xingshi.ai.feature.chat.TextToSpeechService.Outcome.Success -> {
                checkActive()
                check(db.backendHandoffDao().link(companionId)?.let { !deleted(it) } == true) { "助手正在删除" }
                check(VoiceCallCoordinator.active.value == null) { "当前通话正在使用播放器" }
                val player = com.xingshi.ai.feature.chat.VoiceCallPlayer()
                try { player.play(audio.audioPath); markCallPlayed(receipt.localMessageId) } finally { player.release() }
            }
            else -> error("语音合成暂不可用，回复仍保留待重播")
        }
        } finally { replayJobs.remove(companionId); VoiceCallCoordinator.endReplay() }
    } }.await()

    fun endCall(companionId: Long, callId: String, mode: String, durationMs: Long) {
        checkActive()
        val key = "end:$callId"
        if (!prefs.contains(key)) check(prefs.edit().putString(key, buildJsonObject { put("companion_id", companionId); put("call_id", callId); put("mode", mode); put("duration_ms", durationMs); put("operation_id", UUID.randomUUID().toString()) }.toString()).commit()) { "无法保存挂断交接，待恢复后确认" }
        wake()
    }
    private suspend fun flushControls() {
        for ((key, value) in prefs.all.filterKeys { it.startsWith("end:") }) {
            val control = Json.parseToJsonElement(value as String).jsonObject
            val link = db.backendHandoffDao().link(control.number("companion_id")) ?: continue
            http.json("POST", chatPath(link) + "/messages", buildJsonObject {
                put("client_message_id", control.text("operation_id")); put("source_event_id", control.text("operation_id")); put("content", "")
                put("media", buildJsonObject {
                    put("input_mode", control.text("mode")); put("call_id", control.text("call_id")); put("call_ended", true); put("duration_ms", control.number("duration_ms"))
                })
            })
            prefs.edit().remove(key).commit()
        }
    }
    private fun chatPath(link: BackendCompanionLink) = "/api/instances/${pathSegment(link.instanceId)}/conversations/${pathSegment(link.conversationId)}"
    private fun routeJson(request: ChannelRequestRef) = buildJsonObject {
        put("plugin_id", request.pluginId); put("account_id", request.replyRoute.accountId); put("external_conversation_id", request.replyRoute.externalConversationId)
        put("sender_id", request.replyRoute.senderId); put("platform_message_id", request.replyRoute.platformMessageId)
        request.replyRoute.opaqueRoute?.let { put("opaque_route", it) }
    }
    private fun parseRoute(value: JsonObject) = ChannelRequestRef(value.text("plugin_id"), ChannelReplyRoute(value.text("account_id"), value.text("external_conversation_id"), value.text("sender_id"), value.text("platform_message_id"), value.text("opaque_route").ifBlank { null }))
}

internal sealed interface BilibiliFetchOutcome {
    data class Ready(val item: PreparedAttachment) : BilibiliFetchOutcome
    data class Failed(val statusKey: String) : BilibiliFetchOutcome
}

internal suspend fun runBilibiliFetch(
    registry: OptionalCapabilityCall.Registry,
    pluginId: String,
    version: String,
    item: PreparedAttachment,
    fetch: suspend (BilibiliFetchRuntime) -> BilibiliFetchResult
): BilibiliFetchOutcome = when (
    val call = OptionalCapabilityCall.invoke(registry, pluginId, version) {
        val gate = OptionalCapabilityCall.callJob()
        when (
            val result = fetch(object : BilibiliFetchRuntime {
                override fun isActive(): Boolean = gate.isActive
            })
        ) {
            is BilibiliFetchResult.Ready -> BilibiliFetchOutcome.Ready(
                item.copy(
                    sourceUrl = result.sourceUrl.ifBlank { item.sourceUrl },
                    linkText = result.linkText,
                    videoPath = result.videoPath
                )
            )
            is BilibiliFetchResult.Failed ->
                BilibiliFetchOutcome.Failed(AttachmentPreparation.bilibiliFailureKey(result.code))
            BilibiliFetchResult.Cancelled -> BilibiliFetchOutcome.Failed("ui_bilibili_failed")
        }
    }
) {
    is OptionalCapabilityCall.Outcome.Completed -> call.value
    OptionalCapabilityCall.Outcome.Unavailable,
    OptionalCapabilityCall.Outcome.Cancelled -> BilibiliFetchOutcome.Failed("ui_bilibili_failed")
}

internal fun withLocalOriginal(items: List<StoredAttachment>, index: Int, path: String): List<StoredAttachment> =
    items.mapIndexed { position, item -> if (position == index) item.copy(path = path) else item }

internal const val BILIBILI_VIDEO_LOST_KEY = "ui_bilibili_video_lost"

internal const val ATTACH_SAVE_FAILED_KEY = "ui_attach_save_failed"

internal const val ATTACH_MESSAGE_MISSING_KEY = "ui_attach_failed"

internal suspend fun acquireFetchedBilibili(
    workDir: File,
    item: PreparedAttachment,
    fetch: suspend (PreparedAttachment, File) -> BilibiliFetchOutcome,
    persistItem: suspend (PreparedAttachment) -> Unit
): BilibiliFetchOutcome {
    BilibiliFetchCache.read(workDir, item.sourceUrl)?.let { cached ->
        return BilibiliFetchOutcome.Ready(persistFetchedItem(workDir, cached, persistItem))
    }
    val fetched = try {
        fetch(item, workDir)
    } catch (t: Throwable) {
        workDir.deleteRecursively()
        throw t
    }
    if (fetched is BilibiliFetchOutcome.Failed) {
        workDir.deleteRecursively()
        return fetched
    }
    val ready = (fetched as BilibiliFetchOutcome.Ready).item
    BilibiliFetchCache.write(workDir, item.sourceUrl, ready)
    return BilibiliFetchOutcome.Ready(persistFetchedItem(workDir, ready, persistItem))
}

internal suspend fun persistFetchedItem(
    workDir: File,
    fetched: PreparedAttachment,
    persistItem: suspend (PreparedAttachment) -> Unit
): PreparedAttachment = try {
    persistItem(fetched)
    fetched
} catch (t: Throwable) {
    if (t.endsBilibiliWorkResponsibility() || t is AttachmentPreparation.CapacityExceeded) {
        workDir.deleteRecursively()
    }
    throw t
}

internal fun bilibiliReclaimDecision(outcome: ResolvedAttachment): Boolean = when (outcome) {
    is ResolvedAttachment.Text -> false
    is ResolvedAttachment.Failed -> outcome.statusKey == BILIBILI_VIDEO_LOST_KEY
}

internal fun Throwable.endsBilibiliWorkResponsibility(): Boolean =
    this is CancellationException || this is BackendChatConsumer.PreparationAbandoned || this is StaleBackendSession

internal fun reclaimCommittedBilibiliWork(rootDir: File, state: AttachmentPreparationState) {
    state.items
        .filter { it.kind == DraftAttachmentKind.BILIBILI && it.text.isNotBlank() && it.videoPath.isNotBlank() }
        .forEach { BilibiliWorkDir.reclaim(rootDir, it.videoPath) }
}

private fun bilibiliWorkPaths(payloads: List<String>): List<String> = payloads.flatMap { payload ->
    val state = runCatching {
        AttachmentPreparation.decode(Json.parseToJsonElement(payload).jsonObject)
    }.getOrNull() ?: return@flatMap emptyList()
    state.items.filter { it.kind == DraftAttachmentKind.BILIBILI && it.videoPath.isNotBlank() }
        .map { it.videoPath }
}

internal fun reclaimBilibiliWorkFor(rootDir: File, payloads: List<String>) {
    bilibiliWorkPaths(payloads).forEach { BilibiliWorkDir.reclaim(rootDir, it) }
}

internal fun keepRetryableBilibiliWork(
    dir: File,
    messageIds: Set<Long>,
    hasRetryInput: (Long) -> Boolean,
    restore: (File) -> PreparedAttachment?
): Boolean = BilibiliWorkDir.ownerMessageId(dir)?.let { id ->
    id in messageIds && hasRetryInput(id) && restore(dir) != null
} == true

internal fun sweepOrphanBilibiliWork(
    rootDir: File,
    payloads: List<String>,
    keep: (File) -> Boolean = { false }
) {
    val referenced = bilibiliWorkPaths(payloads).mapNotNull { path ->
        runCatching { File(path).canonicalFile.toPath() }.getOrNull()
    }
    rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val directory = runCatching { dir.canonicalFile.toPath() }.getOrNull() ?: return@forEach
        if (referenced.none { it.startsWith(directory) } && !keep(dir)) dir.deleteRecursively()
    }
}

internal suspend fun attachBilibiliOriginal(
    message: ChatMessage,
    index: Int,
    source: File,
    destDir: File,
    copy: suspend (File, File) -> AttachmentCopyResult,
    update: suspend (ChatMessage) -> Int
): String? {
    val items = MessageAttachments.decode(message.linkString) ?: return null
    if (items.getOrNull(index)?.kind != DraftAttachmentKind.BILIBILI) return null
    val dest = File(destDir, "${UUID.randomUUID()}_${source.name}")
    when (val result = copy(source, dest)) {
        AttachmentCopyResult.Copied -> Unit
        else -> return attachmentCopyFailureKey(result)
    }
    return try {
        if (update(message.copy(linkString = MessageAttachments.encode(withLocalOriginal(items, index, dest.absolutePath)))) <= 0) {
            dest.delete()
            SecureLog.e("BackendChatConsumer", "bilibili video association updated no message row")
            ATTACH_SAVE_FAILED_KEY
        } else null
    } catch (e: CancellationException) {
        dest.delete()
        throw e
    } catch (e: Exception) {
        dest.delete()
        SecureLog.e("BackendChatConsumer", "bilibili video association failed", e)
        ATTACH_SAVE_FAILED_KEY
    }
}

internal class ChatFeedbackState {
    data class Snapshot(
        val status: Map<Long, String> = emptyMap(),
        val nextActions: Map<Long, String> = emptyMap()
    )

    private val mutableState = MutableStateFlow(Snapshot())

    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    val status: StateFlow<Map<Long, String>> = ProjectedStateFlow(state) { it.status }
    val nextActions: StateFlow<Map<Long, String>> = ProjectedStateFlow(state) { it.nextActions }

    fun set(companionId: Long, value: String, nextAction: String? = null) {
        mutableState.update { current ->
            val actions = when (nextAction) {
                null -> current.nextActions
                "" -> current.nextActions - companionId
                else -> current.nextActions + (companionId to nextAction)
            }
            Snapshot(status = current.status + (companionId to value), nextActions = actions)
        }
    }

    fun retry(companionId: Long) = set(companionId, ChatStatusDisplay.PREPARING, "")

    fun fail(companionId: Long, statusKey: String) = set(companionId, statusKey, AttachmentPreparation.RETRY_HINT)

    fun networkFallback(statusKey: String = ChatStatusDisplay.NETWORK_UNREACHABLE) {
        mutableState.update { current ->
            val targets = current.status.filter { (companionId, value) ->
                !current.nextActions.containsKey(companionId) && !value.isExplicitFailureReason()
            }.keys
            if (targets.isEmpty()) current
            else Snapshot(
                status = current.status + targets.associateWith { statusKey },
                nextActions = current.nextActions
            )
        }
    }

    private fun String.isExplicitFailureReason(): Boolean =
        listOf("ui_tts_error_", "ui_chat_tts_retry_", "ui_chat_failure_", "ui_attach_", "ui_doc_", "ui_video_", "ui_bilibili_", "ui_call_",
            ChatStatusDisplay.REQUEST_REJECTED, ChatStatusDisplay.SERVICE_BUSY,
            ChatStatusDisplay.EXTERNAL_FAILED, ChatStatusDisplay.EXTERNAL_UNKNOWN,
            ChatStatusDisplay.ROUTE_CONFLICT, ChatStatusDisplay.LINK_MISSING)
            .any { startsWith(it) }

    fun forget(companionId: Long) {
        mutableState.update { Snapshot(it.status - companionId, it.nextActions - companionId) }
    }
}

private class ProjectedStateFlow<S, T>(
    private val source: StateFlow<S>,
    private val project: (S) -> T
) : StateFlow<T> {
    override val value: T get() = project(source.value)
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var last: Any? = Unset
        source.collect(object : FlowCollector<S> {
            override suspend fun emit(value: S) {
                val next = project(value)
                if (last !== Unset && next == last) return
                last = next
                collector.emit(next)
            }
        })
        error("StateFlow 不应结束")
    }

    private object Unset
}
