package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.room.withTransaction
import com.xingshi.ai.common.AccountStorage
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.database.*
import com.xingshi.ai.database.repository.*
import com.xingshi.ai.domain.BackendSessionStore
import com.xingshi.ai.feature.chat.CallTurnHandoff
import com.xingshi.ai.feature.chat.ChatFailureDisplay
import com.xingshi.ai.feature.chat.ChatStatusDisplay
import com.xingshi.ai.feature.chat.VoiceCallCoordinator
import com.xingshi.ai.network.*
import com.xingshi.ai.plugins.PluginChannels
import com.xingshi.ai.plugins.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
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
    private val mutableStatus = MutableStateFlow<Map<Long, String>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    private val mutableNextActions = MutableStateFlow<Map<Long, String>>(emptyMap())
    val nextActions = mutableNextActions.asStateFlow()
    private val db = AppDatabase.getDatabase(app)
    private val prefs = app.getSharedPreferences("backend_chat_controls", android.content.Context.MODE_PRIVATE)
    private val accountHandoffs = app.getSharedPreferences("backend_handoffs", android.content.Context.MODE_PRIVATE)
    private fun deleted(link: BackendCompanionLink) = link.deletingOperationId != null || accountHandoffs.contains("deleted:${link.instanceId}")
    private var polling: Job? = null
    private val offeredCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val replayJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    @Volatile private var stopped = false
    private fun active() = !stopped && AccountStorage.partition == partition && sessions.current()?.generation == generation
    private fun checkActive() { if (!active()) throw StaleBackendSession() }
    private fun status(id: Long, value: String, nextAction: String = "") {
        mutableStatus.value = mutableStatus.value + (id to value)
        mutableNextActions.value = if (nextAction.isBlank()) mutableNextActions.value - id else mutableNextActions.value + (id to nextAction)
    }
    fun wake() { signal.trySend(Unit) }
    private fun responseFinalizer(companionId: Long) = AiResponseFinalizer(companionId, ChatRepository(db.chatMessageDao()), AppSettingsStore(app), MutableStateFlow(""), MutableStateFlow(false), app)
    fun stop() { stopped = true; scope.cancel(); signal.close() }
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
        mutableStatus.value = mutableStatus.value - companionId
        mutableNextActions.value = mutableNextActions.value - companionId
    }

    fun start() {
        if (polling != null) return
        polling = scope.launch {
            var idle = 0
            while (isActive && active()) {
                val busy = runCatching { requests.withLock { cycle() } }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    mutableStatus.value.keys.forEach { status(it, ChatStatusDisplay.NETWORK_UNREACHABLE) }
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
        persistAccepted(turn, localId)
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
            val handoff = BackendInputHandoff(localId, turn.companionId, clientId, sourceId,
                ChatMessageCrypto.encrypt(payload.toString()), preparing?.encryptedRoute ?: turn.request?.let { ChatMessageCrypto.encrypt(routeJson(it).toString()) }.orEmpty(), turn.callId.orEmpty())
            if (preparing == null) db.backendHandoffDao().insertInput(handoff) else db.backendHandoffDao().updateInput(handoff)
            status(turn.companionId, ChatStatusDisplay.LOCAL_SAVED)
    }

    suspend fun prepareImage(companionId: Long, localId: Long, text: String, image: String, request: ChannelRequestRef?) {
        checkActive()
        val client = UUID.randomUUID().toString(); val source = UUID.randomUUID().toString()
        val occurredAt = inputMonitor.occurredAt(db.chatMessageDao().getMessageById(localId)?.timestamp ?: System.currentTimeMillis())
        val payload = buildJsonObject { put("client_message_id", client); put("source_event_id", source); put("content", text); put("occurred_at", occurredAt); put("preparation_image", image)
            if (request != null) put("reply_route_ref", client) }
        db.backendHandoffDao().insertInput(BackendInputHandoff(localId, companionId, client, source, ChatMessageCrypto.encrypt(payload.toString()),
            request?.let { ChatMessageCrypto.encrypt(routeJson(it).toString()) }.orEmpty(), state = "PREPARING"))
        status(companionId, ChatStatusDisplay.IMAGE_SAVED)
    }

    private suspend fun cycle(): Boolean {
        checkActive()
        val manager = com.xingshi.ai.plugins.PluginFramework.manager()
        for (input in db.backendHandoffDao().preparingInputs()) {
            checkActive()
            if (manager?.hasImageConsumer() == true && !manager.hasPendingInput(input.localMessageId)) {
                val body = Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject
                val route = input.encryptedRoute.takeIf { it.isNotBlank() }?.let { parseRoute(Json.parseToJsonElement(ChatMessageCrypto.decrypt(it)).jsonObject) }
                manager.startForSentImage(input.companionId, body.text("preparation_image"), route, body.text("content"), input.localMessageId)
            }
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

    private suspend fun submit(input: BackendInputHandoff) {
        val link = db.backendHandoffDao().link(input.companionId) ?: run { status(input.companionId, ChatStatusDisplay.LINK_MISSING); return }
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
            status(input.companionId, when {
                presentation != null -> presentation.reasonKey
                result.state == "COMPLETED" -> ChatStatusDisplay.RESULT_READY
                result.state == "UNKNOWN" -> ChatStatusDisplay.UNKNOWN_READING
                result.stage == "QUEUED" -> ChatStatusDisplay.QUEUED
                result.stage == "PREPARING" -> ChatStatusDisplay.PREPARING
                result.stage == "GENERATING" -> ChatStatusDisplay.GENERATING
                result.generationId == null -> ChatStatusDisplay.ACCEPTED
                else -> ChatStatusDisplay.GENERATING_ACCEPTED
            }, when {
                result.state != "FAILED" && result.state != "UNKNOWN" -> ""
                result.nextAction.isNotBlank() -> result.nextAction
                else -> presentation?.nextAction.orEmpty().takeUnless { it == ChatFailureDisplay.NONE }.orEmpty()
            })
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
                status(input.companionId, statusKey, ChatFailureDisplay.nextActionForCode(code))
            } else {
                val statusKey = if (failure is BackendHttpException) ChatStatusDisplay.BACKEND_UNCONFIRMED else ChatStatusDisplay.NETWORK_UNCONFIRMED
                db.backendHandoffDao().updateInput(current.copy(error = null))
                status(input.companionId, statusKey)
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
        status(link.companionId, ChatStatusDisplay.REPLY_SAVED)
        processFollowUp(receipt, recovering = false)
    }

    private suspend fun processFollowUp(receipt: BackendDeliveryReceipt, recovering: Boolean) {
        checkActive()
        val link = db.backendHandoffDao().link(receipt.companionId) ?: return
        if (deleted(link)) return
        if (receipt.state == "DONE") return
        if (receipt.state == "EXTERNAL_UNKNOWN") { status(receipt.companionId, ChatStatusDisplay.EXTERNAL_UNKNOWN); return }
        if (receipt.state == "REPLAY_AVAILABLE") { status(receipt.companionId, ChatStatusDisplay.REPLAY_AVAILABLE); return }
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
                status(receipt.companionId, ChatStatusDisplay.ROUTE_CONFLICT)
                return
            }
            val state = PluginChannels.deliverFormalReply(receipt.companionId, routes.single(), item.text("content"), receipt.state) { next ->
                checkActive(); db.backendHandoffDao().updateReceipt(receipt.copy(state = next))
            }
            status(receipt.companionId, when (state) {
                "DONE" -> ChatStatusDisplay.DELIVERED
                "EXTERNAL_UNKNOWN" -> ChatStatusDisplay.EXTERNAL_UNKNOWN
                "EXTERNAL_FAILED" -> ChatStatusDisplay.EXTERNAL_FAILED
                else -> ChatStatusDisplay.CHANNEL_PENDING
            })
            return
        }
        val inputModes = inputs.mapNotNull { input ->
            runCatching {
                Json.parseToJsonElement(ChatMessageCrypto.decrypt(input.encryptedPayload)).jsonObject.obj("media").text("input_mode")
            }.getOrNull()
        }
        if (AutoSpeechReply.speaksReply(inputModes)) {
            responseFinalizer(receipt.companionId).attachAutoSpeech(receipt.localMessageId, inputModes)?.let { status(receipt.companionId, it) }
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
        status(companionId, ChatStatusDisplay.REGENERATION_ACCEPTED)
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
