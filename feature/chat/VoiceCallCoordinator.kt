package com.xingshi.ai.feature.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.feature.chat.ui.viewmodel.AiResponseFinalizer
import com.xingshi.ai.feature.chat.ui.viewmodel.ChatVerificationBus
import com.xingshi.ai.feature.chat.ui.viewmodel.ConversationIntake
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginSendGuard
import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import com.xingshi.ai.plugins.api.SpeechSegmentConfig
import com.xingshi.ai.plugins.api.SpeechSegmentProvider
import com.xingshi.ai.plugins.api.TtsResult
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CALL_REPLY_TIMEOUT_MS = 60_000L

 
object VoiceCallCoordinator {

    enum class Prepare {
        READY, STT_MISSING, TTS_MISSING, VISION_MISSING,
        STT_NOT_CONFIGURED, TTS_NOT_CONFIGURED, VISION_NOT_CONFIGURED,
        CAMERA_PERMISSION_MISSING, PERMISSION_MISSING
    }

    data class ActiveCall(
        val callId: String,
        val companionId: Long,
        val mode: String,
         
        val startedElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    )

    private val _active = MutableStateFlow<ActiveCall?>(null)
    val active: StateFlow<ActiveCall?> = _active.asStateFlow()

    private val _state = MutableStateFlow(VoiceCallController.State.PREPARING)
    val state: StateFlow<VoiceCallController.State> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var controller: VoiceCallController? = null

    @Volatile
    private var runningJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var activePlayer: VoiceCallPlayer? = null

    @Volatile
    private var activeCamera: VideoCallCamera? = null

    @Volatile
    private var capabilityWatch: kotlinx.coroutines.Job? = null

    @Volatile
    private var capabilityWatchIds: List<String> = emptyList()

    @Volatile
    private var capabilityWatchReleased = false

    @Volatile
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var lastReply: CallTurnHandoff.Reply? = null

    @Volatile
    private var lastVideoCapturedAt: Long = 0L

    
    @Volatile
    private var currentRoundInputMessageId: Long = 0L

    private val starting = AtomicBoolean(false)

     
    @Volatile
    private var acceptWindowOpen: AtomicBoolean? = null

    
    @Volatile
    private var sendGuard: PluginSendGuard? = null

    fun isActiveFor(companionId: Long): Boolean = _active.value?.companionId == companionId

     
    fun tryBeginReplay(): Boolean {
        if (!starting.compareAndSet(false, true)) return false
        if (_active.value != null) {
            starting.set(false)
            return false
        }
        return true
    }

    fun endReplay() { starting.set(false) }

    suspend fun canStart(context: Context, video: Boolean = false): Prepare {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Prepare.PERMISSION_MISSING
        }
        if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return Prepare.CAMERA_PERMISSION_MISSING
        }
        if (!SpeechRecognitionService.isPluginInstalled()) return Prepare.STT_MISSING
        if (!TextToSpeechService.isPluginInstalled()) return Prepare.TTS_MISSING
        if (video && !VisionAnalysisService.isPluginInstalled()) return Prepare.VISION_MISSING
        val database = AppDatabase.getDatabase(context)
        val repository = ApiConfigRepository(database.apiConfigDao())
        if (repository.getSttConfig() == null) return Prepare.STT_NOT_CONFIGURED
        if (repository.getTtsConfig() == null) return Prepare.TTS_NOT_CONFIGURED
        if (video && (repository.getVisionConfig() ?: repository.getActiveEnabledConfig()) == null) {
            return Prepare.VISION_NOT_CONFIGURED
        }
        return Prepare.READY
    }

    fun start(
        context: Context,
        companionId: Long,
        scope: CoroutineScope,
        video: Boolean = false,
        camera: VideoCallCamera? = null
    ): Boolean {
        if (!starting.compareAndSet(false, true)) return false
        if (_active.value != null) {
            starting.set(false)
            return false
        }
        
        val acceptWindow = AtomicBoolean(true)
        acceptWindowOpen = acceptWindow
        val callGuard = PluginSendGuard { acceptWindow.get() }
        sendGuard = callGuard
        val app = context.applicationContext as android.app.Application
        val backendConsumer = com.xingshi.ai.domain.ServiceRegistry.get(com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer::class.java)
        _error.value = null
        _state.value = VoiceCallController.State.PREPARING
        val callId = UUID.randomUUID().toString()
        val mode = if (video) "VIDEO_CALL" else "VOICE_CALL"
        _active.value = ActiveCall(callId, companionId, mode)
        currentRoundInputMessageId = 0L
        VoiceCallRecovery.markStarted(app, callId, companionId, mode, System.currentTimeMillis())
        val segmenter = runCatching {
            (PluginFramework.manager()?.speechRecognitionPlugin() as? SpeechSegmentProvider)
                ?.openSegmenter(
                    SpeechSegmentConfig(),
                    File(app.cacheDir, "voice_calls").apply { mkdirs() }
                )
        }.getOrNull()
        val player = VoiceCallPlayer()
        activePlayer = player
        activeCamera = camera
        val controller = VoiceCallController(
            callId = callId,
            companionId = companionId,
            segmenter = segmenter,
            recognize = { segment ->
                when (val outcome = SpeechRecognitionService.recognize(
                    app, segment.audioPath, "audio/wav", segment.durationMs
                )) {
                    is SpeechRecognitionService.Outcome.Recognized -> SpeechRecognitionResult.Success(outcome.text)
                    is SpeechRecognitionService.Outcome.Failed -> SpeechRecognitionResult.Failure(outcome.reason)
                    else -> SpeechRecognitionResult.Failure("语音识别不可用")
                }
            },
            synthesize = { text ->
                when (val outcome = TextToSpeechService.synthesize(app, text, File(app.cacheDir, "voice_calls"))) {
                    is TextToSpeechService.Outcome.Success ->
                        TtsResult.Success(outcome.audioPath, outcome.mimeType, outcome.durationMs)
                    is TextToSpeechService.Outcome.Failed -> TtsResult.Failure(outcome.reason)
                    else -> TtsResult.Failure("语音合成不可用")
                }
            },
            play = { audio ->
                player.play(audio.audioPath)
                lastReply?.let { reply -> backendConsumer?.markCallPlayed(reply.messageId) }
            },
            submit = { text ->
                val outcome = ConversationIntake.submit(
                    app = app,
                    companionId = companionId,
                    text = text,
                    source = ConversationIntake.Source.LOCAL,
                    guard = callGuard,
                    inputKind = ConversationIntake.InputKind.VOICE_CALL,
                    callId = callId,
                    presetUserMessage = com.xingshi.ai.database.model.ChatMessage(
                        companionId = companionId,
                        content = text,
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        linkString = "call:$callId"
                    )
                )
                if (!outcome.accepted) {
                    currentRoundInputMessageId = 0L
                    _error.value = outcome.reason ?: "通话输入未受理"
                } else {
                    currentRoundInputMessageId = outcome.userMessageId
                    saveTurnMetadata(
                        context = app,
                        companionId = companionId,
                        messageId = outcome.userMessageId,
                        text = text,
                        observation = "",
                        source = "VOICE_CALL",
                        capturedAt = System.currentTimeMillis()
                    )
                }
            },
            videoRound = if (video && camera != null) { segment, merge ->
                
                var capturedFrame: java.io.File? = null
                val turn = VideoCallTurnCoordinator(
                    recognize = { runVideoSpeech(app, segment) },
                    analyze = {
                        val frame = capturedFrame ?: camera.captureFrame().also {
                            capturedFrame = it
                            if (it != null) lastVideoCapturedAt = System.currentTimeMillis()
                        }
                        if (frame == null) {
                            com.xingshi.ai.plugins.api.VisionAnalysisResult.Failure("本轮画面获取失败")
                        } else {
                            when (val vision = VisionAnalysisService.analyze(
                                app, frame.absolutePath, VisionAnalysisService.FRAME_PROMPT
                            )) {
                                is VisionAnalysisService.Outcome.Described ->
                                    com.xingshi.ai.plugins.api.VisionAnalysisResult.Described(vision.text)
                                is VisionAnalysisService.Outcome.Failed ->
                                    com.xingshi.ai.plugins.api.VisionAnalysisResult.Failure(vision.reason)
                                VisionAnalysisService.Outcome.NotInstalled ->
                                    com.xingshi.ai.plugins.api.VisionAnalysisResult.Failure("视觉插件不可用")
                                VisionAnalysisService.Outcome.NotConfigured ->
                                    com.xingshi.ai.plugins.api.VisionAnalysisResult.Failure("视觉配置不完整")
                            }
                        }
                    },
                    submit = merge
                )
                turn.runRound()
            } else null,
            submitWithObservation = { text, observation ->
                val outcome = ConversationIntake.submit(
                    app = app,
                    companionId = companionId,
                    text = text,
                    source = ConversationIntake.Source.LOCAL,
                    guard = callGuard,
                    inputKind = ConversationIntake.InputKind.VIDEO_CALL,
                    visionObservation = observation,
                    callId = callId,
                    presetUserMessage = com.xingshi.ai.database.model.ChatMessage(
                        companionId = companionId,
                        content = text,
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        linkString = "call:$callId"
                    )
                )
                if (!outcome.accepted) {
                    currentRoundInputMessageId = 0L
                    _error.value = outcome.reason ?: "视频通话输入未受理"
                } else {
                    currentRoundInputMessageId = outcome.userMessageId
                    saveTurnMetadata(
                        context = app,
                        companionId = companionId,
                        messageId = outcome.userMessageId,
                        text = text,
                        observation = observation,
                        source = "VIDEO_CALL",
                        capturedAt = lastVideoCapturedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }
            },
            awaitReply = { id ->
                val reply = awaitCallReply(app, companionId, id, currentRoundInputMessageId)
                lastReply = reply
                reply?.text
            },
            onReplyAudio = { durationMs ->
                val reply = lastReply
                if (reply != null) {
                    VoiceTurnMetadataStore.save(
                        context = app,
                        messageId = reply.messageId,
                        companionId = companionId,
                        source = metadataSourceFor(video),
                        capturedAt = System.currentTimeMillis(),
                        durationMs = durationMs,
                        callId = callId,
                        transcript = reply.text
                    )
                }
            },
            onState = {
                _state.value = it
                runCatching { VoiceCallRecovery.heartbeat(app) }
            },
            onStageError = { _error.value = it }
        )
        this.controller = controller
        
        capabilityWatchReleased = false
        val manager = PluginFramework.manager()
        val watchedIds = listOfNotNull(
            manager?.speechRecognitionPluginId(),
            manager?.textToSpeechPluginId(),
            if (video) manager?.visionAnalysisPluginId() else null
        ).distinct()
        capabilityWatchIds = watchedIds
        capabilityWatch = manager?.registerOptionalCapabilityWatch(watchedIds) {
            
            if (!capabilityWatchReleased && _active.value?.callId == callId) hangup()
        }
        if (manager != null && watchedIds.isNotEmpty() && capabilityWatch == null) {
            
            hangup()
        }
        
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (_active.value != null) {
                runCatching { VoiceCallRecovery.heartbeat(app) }
                kotlinx.coroutines.delay(5_000L)
            }
        }
        runningJob = scope.launch(Dispatchers.IO) {
            try {
                controller.run()
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                releaseCapabilityWatch()
                player.release()
                runCatching { segmenter?.release() }
                runCatching { camera?.stop() }
                
                val cardSaved = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    saveCallCard(app, controller, mode, backendConsumer)
                }
                if (cardSaved) VoiceCallRecovery.clear(app)
                cleanupTemporaryCallMedia(app)
                _active.value = null
                _state.value = VoiceCallController.State.ENDED
                controller.record?.reason?.let { reason ->
                    if (reason == VoiceCallController.EndReason.FAILED) {
                        _error.value = controller.lastError() ?: _error.value
                    }
                }
                acceptWindow.set(false)
                if (sendGuard === callGuard) sendGuard = null
                this@VoiceCallCoordinator.controller = null
                runningJob = null
                activePlayer = null
                activeCamera = null
                starting.set(false)
            }
        }
        return true
    }

    private fun cleanupTemporaryCallMedia(app: android.app.Application) {
        listOf("voice_calls", "video_calls").forEach { name ->
            val dir = java.io.File(app.cacheDir, name)
            dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
        }
    }

    private fun releaseCapabilityWatch() {
        capabilityWatchReleased = true
        val job = capabilityWatch
        capabilityWatch = null
        val ids = capabilityWatchIds
        capabilityWatchIds = emptyList()
        if (job != null && ids.isNotEmpty()) {
            runCatching { PluginFramework.manager()?.unregisterOptionalCapabilityWatch(ids, job) }
        }
    }

    fun hangup() {
        
        sendGuard?.revoke()
        acceptWindowOpen?.set(false)
        controller?.hangup()
        activePlayer?.release()
        activeCamera?.stop()
        runningJob?.cancel()
    }

     
    suspend fun stopForAccountSwitch() {
        val job = runningJob
        hangup()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { job?.join() }
    }

    private suspend fun runVideoSpeech(
        context: Context,
        segment: com.xingshi.ai.plugins.api.SpeechSegment
    ): SpeechRecognitionResult =
        when (val outcome = SpeechRecognitionService.recognize(
            context, segment.audioPath, "audio/wav", segment.durationMs
        )) {
            is SpeechRecognitionService.Outcome.Recognized -> SpeechRecognitionResult.Success(outcome.text)
            is SpeechRecognitionService.Outcome.Failed -> SpeechRecognitionResult.Failure(outcome.reason)
            else -> SpeechRecognitionResult.Failure("语音识别不可用")
        }

    private suspend fun awaitCallReply(
        context: Context,
        companionId: Long,
        callId: String,
        inputMessageId: Long
    ): CallTurnHandoff.Reply? =
        
        CallTurnHandoff.awaitReply(
            callId = callId,
            companionId = companionId,
            timeoutMs = CALL_REPLY_TIMEOUT_MS,
            inputMessageId = inputMessageId
        )

    private fun metadataSourceFor(video: Boolean): String = if (video) "VIDEO_CALL" else "VOICE_CALL"

     
    private suspend fun saveTurnMetadata(
        context: Context,
        companionId: Long,
        messageId: Long,
        text: String,
        observation: String,
        source: String,
        capturedAt: Long
    ) {
        if (messageId <= 0L) return
        val callId = _active.value?.callId ?: return
        runCatching {
            VoiceTurnMetadataStore.save(
                context = context,
                messageId = messageId,
                companionId = companionId,
                source = source,
                capturedAt = capturedAt,
                durationMs = 0L,
                observation = observation,
                callId = callId,
                transcript = text
            )
        }
    }

    private suspend fun saveCallCard(
        context: Context,
        controller: VoiceCallController,
        mode: String,
        backendConsumer: com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer?
    ): Boolean {
        val record = controller.record ?: return false
        val seconds = (record.durationMs / 1000).coerceAtLeast(0)
        
        val text = "call-card|$mode|$seconds"
        return runCatching {
            val consumer = backendConsumer ?: return@runCatching false
            consumer.endCall(record.companionId, record.callId, mode, record.durationMs)
            val repository = ChatRepository(AppDatabase.getDatabase(context).chatMessageDao())
            if (repository.countCallCardsByLinkString("call:${record.callId}") > 0) return@runCatching true
            repository.sendMessage(
                ChatMessage(
                    companionId = record.companionId,
                    content = text,
                    isFromUser = false,
                    timestamp = record.endedAt,
                    type = MessageType.CALL,
                    linkString = "call:${record.callId}"
                )
            )
            true
        }.getOrDefault(false)
    }
}
