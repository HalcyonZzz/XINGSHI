package com.xingshi.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.common.BanManager
import com.xingshi.ai.common.ChatConstants
import com.xingshi.ai.common.ContentFilter
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.common.StickerInfo
import com.xingshi.ai.common.TimeoutBudgets
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.model.FileFormat
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.UserProfileProvider
import com.xingshi.ai.feature.chat.SpeechRecognitionService
import com.xingshi.ai.feature.chat.TextToSpeechService
import com.xingshi.ai.feature.chat.VoiceCallCoordinator
import com.xingshi.ai.feature.chat.data.ChatContextResolver
import com.xingshi.ai.feature.chat.data.KeywordBridge
import com.xingshi.ai.network.ChatTypingState
import com.xingshi.ai.plugins.ChatSendEntry
import com.xingshi.ai.feature.chat.CallTurnHandoff
import com.xingshi.ai.feature.chat.VoiceTurnMetadataStore
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginSendGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object DraftAttachmentState {
    fun items(attachments: List<DraftAttachment>, failureKey: String = ""): String = buildJsonArray {
        attachments.forEach { attachment ->
            add(buildJsonObject {
                put("id", attachment.id)
                put("name", attachment.fileName)
                put("kind", attachment.kind.name.lowercase())
                put("failure", failureKey)
            })
        }
    }.toString()
}

class ChatViewModel(
    application: Application,
    private val companionId: Long,
    private val bindChannelEntry: Boolean = true
) : AndroidViewModel(application) {

    private val _appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        ChatDebugLog.log("[ChatVM] applicationApiScope UNCAUGHT: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatViewModel", "applicationApiScope uncaught exception", throwable)
    }
    private val applicationApiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + _appExceptionHandler)

    private val database = AppDatabase.getDatabase(application)
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val companionRepository = CompanionRepository(database.companionDao())
    private val contextResolver = ChatContextResolver()

    private val _recentMessages = MutableStateFlow<List<ChatMessage>>(
        chatRepository.getCachedRecent(companionId) ?: emptyList()
    )
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(_recentMessages, _olderMessages) { recent, older ->
        contextResolver.capUiMessages(older + recent).first.filter { message ->
            message.type == MessageType.CALL || !message.linkString.startsWith("call:")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), chatRepository.getCachedRecent(companionId) ?: emptyList())

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private var _reachedEnd = false

    private val _userName = MutableStateFlow("我")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    private val _companionData = MutableStateFlow<CompanionEntity?>(null)
    val companionData: StateFlow<CompanionEntity?> = _companionData.asStateFlow()

    private val _reasoningText = MutableStateFlow("")
    val reasoningText: StateFlow<String> = _reasoningText.asStateFlow()

    private val _isReasoning = MutableStateFlow(false)
    val isReasoning: StateFlow<Boolean> = _isReasoning.asStateFlow()

    private val chatTypingState = ChatTypingState()
    val isTyping: StateFlow<Boolean> = chatTypingState.isTyping
    val typingText: StateFlow<String> = chatTypingState.typingText

    val queueDepth: StateFlow<Int> = ConversationIntake.depth(companionId)
    private val backendConsumer = ServiceRegistry.get(BackendChatConsumer::class.java)
    private val companionCloud = ServiceRegistry.get(com.xingshi.ai.domain.CompanionCloudGateway::class.java)
    private val modelCloud = ServiceRegistry.get(com.xingshi.ai.domain.ChatModelCloudGateway::class.java)
    private val backendAccount = ServiceRegistry.get(com.xingshi.ai.domain.BackendAccountGateway::class.java)
    private val accountFiles = application.filesDir
    private val accountCache = application.cacheDir
    private val accountPartition = com.xingshi.ai.common.AccountStorage.partition
    val backendStatus: StateFlow<String> = (backendConsumer?.status ?: MutableStateFlow(emptyMap<Long, String>()))
        .map { it[companionId].orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val backendNextAction: StateFlow<String> = (backendConsumer?.nextActions ?: MutableStateFlow(emptyMap<Long, String>()))
        .map { it[companionId].orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val hasBackend: Boolean get() = backendConsumer != null
    fun reconnectBackend() { runCatching { backendAccount?.reconnect() }.onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "连接恢复失败")) } }
    fun cancelBackendGeneration() { viewModelScope.launch(Dispatchers.IO) {
        runCatching { backendConsumer?.cancel(companionId) }.onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "取消尚未确认")) }
    } }
    fun replayBackendSpeech() { viewModelScope.launch(Dispatchers.IO) {
        runCatching { backendConsumer?.replayPendingSpeech(companionId) }.onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "重播失败")) }
    } }
    fun regenerateBackendGeneration() { viewModelScope.launch(Dispatchers.IO) {
        runCatching { backendConsumer?.regenerate(companionId) }.onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "重新生成尚待确认")) }
    } }
    private val mutableModelChoices = MutableStateFlow<List<com.xingshi.ai.domain.CloudChatModelChoice>?>(null)
    val backendModelChoices = mutableModelChoices.asStateFlow()
    fun dismissBackendModels() { mutableModelChoices.value = null }
    fun loadBackendModels() { viewModelScope.launch(Dispatchers.IO) {
        runCatching { checkNotNull(modelCloud) { "请先登录" }.choices(companionId) }
            .onSuccess { mutableModelChoices.value = it }
            .onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "模型列表读取失败")) }
    } }
    fun chooseBackendModel(choice: com.xingshi.ai.domain.CloudChatModelChoice) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { checkNotNull(modelCloud) { "请先登录" }.select(companionId, choice) }
            .onSuccess { mutableModelChoices.value = null; _events.tryEmit(ChatUiEvent.Info("普通聊天模型已生效")) }
            .onFailure { _events.tryEmit(ChatUiEvent.Error(it.message ?: "模型绑定尚未生效")) }
    } }

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<DraftAttachment>>(emptyList())
    val draftAttachments: StateFlow<List<DraftAttachment>> = _draftAttachments.asStateFlow()

    private val _draftAttachmentFailure = MutableStateFlow("")
    val draftAttachmentFailure: StateFlow<String> = _draftAttachmentFailure.asStateFlow()

    private val sendInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val inFlightDraftPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    fun addDraftAttachment(
        path: String,
        fileName: String,
        mimeType: String,
        kind: DraftAttachmentKind = AttachmentPreparation.kindOf(mimeType, fileName),
        sourceUrl: String = ""
    ) {
        if (com.xingshi.ai.common.AccountStorage.partition != accountPartition) { clearDraftAttachments(); return }
        if (kind == DraftAttachmentKind.BILIBILI) {
            if (sourceUrl.isBlank()) return
        } else if (path.isBlank()) {
            return
        }
        _draftAttachments.update { current ->
            current + DraftAttachment(
                id = UUID.randomUUID().toString(),
                path = path,
                fileName = fileName,
                mimeType = mimeType,
                kind = kind,
                sourceUrl = sourceUrl
            )
        }
        _draftAttachmentFailure.value = ""
    }

    fun removeDraftAttachment(id: String) {
        val removed = _draftAttachments.value.firstOrNull { it.id == id } ?: return
        _draftAttachments.update { current -> current.filterNot { it.id == id } }
        _draftAttachmentFailure.value = ""
        if (!inFlightDraftPaths.contains(removed.path)) releaseDraftFile(removed.path)
    }

    private fun clearDraftAttachments() {
        _draftAttachments.value.forEach { if (it.path.isNotBlank()) releaseDraftFile(it.path) }
        _draftAttachments.value = emptyList()
        _draftAttachmentFailure.value = ""
    }

    private fun releaseDraftFile(path: String) {
        if (inFlightDraftPaths.contains(path)) return
        releaseDraftCacheFile(path)
    }

    private fun releaseDraftCacheFile(path: String) {
        val file = java.io.File(path)
        val inCache = runCatching {
            file.canonicalPath.startsWith(accountCache.canonicalPath + java.io.File.separator)
        }.getOrDefault(false)
        if (inCache) runCatching { file.delete() }
    }

    val pipeline = ConversationIntake.pipeline

    val state: StateFlow<ChatState> = combine(
        listOf(
            _companionData, messages, chatTypingState.isTyping,
            chatTypingState.typingText, _reasoningText, _isReasoning,
            _userName, _userAvatar,
            queueDepth, _hasMoreMessages, _isLoadingMore,
            pipeline.pipelineState
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ChatState(
            companionData = values[0] as CompanionEntity?,
            messages = values[1] as List<ChatMessage>,
            visibleMessages = values[1] as List<ChatMessage>,
            isTyping = values[2] as Boolean,
            typingText = values[3] as String,
            reasoningText = values[4] as String,
            isReasoning = values[5] as Boolean,
            userName = values[6] as String,
            userAvatar = values[7] as String?,
            queueDepth = values[8] as Int,
            hasMoreMessages = values[9] as Boolean,
            isLoadingMore = values[10] as Boolean,
            pipelineStage = (values[11] as MessagePipeline.PipelineState).stage.name,
            pipelineError = (values[11] as MessagePipeline.PipelineState).error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatState())

    init {
        loadCompanionData()
        observeMessages()
        loadUserProfile()
        applicationApiScope.launch {
            try {
                ContentFilter.initialize(application)
                val warmOk = ContentFilter.warmUpNativeAc()
                ChatDebugLog.log("[ChatVM] ContentFilter warmUp: $warmOk")
            } catch (e: Exception) {
                ChatDebugLog.log("[ChatVM] ContentFilter init/warmUp failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            KeywordBridge.initialize(application)
        }
        if (bindChannelEntry) {
            registerMessageConsumer()
            bindPluginSession()
            collectVerificationFragments()
        }
    }

    private val pageTurnContext = PageTurnContext()

    private fun collectVerificationFragments() {
        if (ServiceRegistry.get(BackendChatConsumer::class.java) != null) return
        applicationApiScope.launch {
            val callBuffers = mutableMapOf<String, StringBuilder>()
            val ordinaryBuffers = mutableMapOf<Long, StringBuilder>()
            var ordinaryUiInputId = 0L
            ChatVerificationBus.events.collect { fragment ->
                if (fragment.companionId != companionId) return@collect
                val fragmentCallId = fragment.callId
                if (fragmentCallId != null) {
                    val roundKey = "$fragmentCallId#${fragment.inputMessageId}"
                    val builder = callBuffers.getOrPut(roundKey) { StringBuilder() }
                    builder.append(fragment.text)
                    if (fragment.terminal) {
                        val full = callBuffers.remove(roundKey)?.toString()?.trim().orEmpty()
                        val turn = pageTurnContext.consumeTurn(fragmentCallId, fragment.inputMessageId)
                        if (turn == null) {
                            return@collect
                        }
                        if (full.isNotBlank()) {
                            runCatching {
                                val finalizer = AiResponseFinalizer(
                                    companionId = companionId,
                                    chatRepository = chatRepository,
                                    appSettingsStore = AppSettingsStore(getApplication()),
                                    reasoningText = _reasoningText,
                                    isReasoning = _isReasoning,
                                    application = getApplication()
                                )
                                val request = turn.request ?: fragment.request
                                finalizer.finalizeResponse(
                                    request = request,
                                    aiContent = full,
                                    reasoning = null,
                                    logMessage = "call turn finalized",
                                    linkString = "call:$fragmentCallId",
                                    inputMessageId = turn.userMessageId
                                )
                            }.onFailure {
                                ChatDebugLog.log(
                                    "[ChatVM] call turn finalize failed: ${it.javaClass.simpleName}: ${it.message}"
                                )
                            }
                        }
                    }
                    return@collect
                }
                val typedInputId = fragment.inputMessageId
                val full = if (typedInputId > 0L) {
                    val existed = ordinaryBuffers.containsKey(typedInputId)
                    val builder = ordinaryBuffers.getOrPut(typedInputId) { StringBuilder() }
                    builder.append(fragment.text)
                    if (!existed) {
                        ordinaryUiInputId = typedInputId
                        chatTypingState.startTyping()
                    }
                    if (ordinaryUiInputId == typedInputId) chatTypingState.appendText(fragment.text)
                    if (!fragment.terminal) return@collect
                    val body = builder.toString().trim()
                    ordinaryBuffers.remove(typedInputId)
                    if (ordinaryUiInputId == typedInputId) {
                        chatTypingState.flushPending()
                        chatTypingState.stopTyping()
                        ordinaryUiInputId = 0L
                    }
                    body
                } else {
                    if (!chatTypingState.isTyping.value) chatTypingState.startTyping()
                    chatTypingState.appendText(fragment.text)
                    if (!fragment.terminal) return@collect
                    chatTypingState.flushPending()
                    val body = chatTypingState.typingText.value
                    chatTypingState.stopTyping()
                    body
                }
                if (full.isNotBlank()) {
                        runCatching {
                            val finalizer = AiResponseFinalizer(
                                companionId = companionId,
                                chatRepository = chatRepository,
                                appSettingsStore = AppSettingsStore(getApplication()),
                                reasoningText = _reasoningText,
                                isReasoning = _isReasoning,
                                application = getApplication()
                            )
                            val turn = if (fragment.inputMessageId > 0L) {
                                pageTurnContext.consumeTurn(null, fragment.inputMessageId)
                            } else {
                                pageTurnContext.consumeTurn()
                            }
                            if (fragment.inputMessageId <= 0L || turn != null) {
                            val id = finalizer.finalizeResponse(
                                request = turn?.request,
                                fromChannelTurn = turn?.request == null && turn?.source == ConversationIntake.Source.CHANNEL,
                                aiContent = full,
                                reasoning = null,
                                logMessage = "fixed fragment response finalized"
                            )
                            ChatDebugLog.log("[ChatVM] fixed fragment finalized, length=${full.length}, id=$id")
                            }
                        }.onFailure {
                            ChatDebugLog.log(
                                "[ChatVM] fixed fragment finalize failed: ${it.javaClass.simpleName}: ${it.message}"
                            )
                        }
                }
            }
        }
    }

    private var boundSendEntry: ChatSendEntry? = null

    private fun bindPluginSession() {
        if (!bindChannelEntry) return
        val manager = PluginFramework.manager() ?: return
        val entry = ChatSendEntry { _, originalText, mergedText ->
            submitPluginResult(originalText, mergedText)
        }
        boundSendEntry = entry
        manager.bindSession(companionId, entry)
    }

    private suspend fun submitPluginResult(originalText: String, mergedText: String): Boolean {
        val content = originalText.ifBlank { mergedText }
        return try {
            val outcome = ConversationIntake.submit(getApplication(), companionId, content,
                source = ConversationIntake.Source.PLUGIN, enqueueText = mergedText,
                guard = currentCoroutineContext()[PluginSendGuard], accountPartition = accountPartition)
            if (!outcome.accepted) _events.tryEmit(ChatUiEvent.Error(outcome.reason ?: "发送未受理"))
            if (outcome.accepted && _draft.value == content) _draft.value = ""
            outcome.accepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatDebugLog.log("[ChatVM] plugin handoff failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun registerMessageConsumer() {
        ChatDebugLog.log("[ChatVM] registerMessageConsumer, companionId=$companionId")
        InboundTurnDispatcher.registerPage(companionId, this) { turn ->
            pageTurnContext.begin(turn)
        }
    }

    private fun observeMessages() {
        ChatDebugLog.log("[ChatVM] observeMessages START, companionId=$companionId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    com.xingshi.ai.domain.ServiceRegistry.get(
                        com.xingshi.ai.domain.GroupConversationGateway::class.java
                    )?.synchronizeBeforePrivateChat(companionId)
                }
                chatRepository.getMessagesForCompanion(companionId, ChatConstants.CHAT_PAGE_SIZE).collect {
                    val page = chatRepository.getVisibleRecent(companionId, ChatConstants.CHAT_PAGE_SIZE)
                    _recentMessages.value = page.messages
                    _hasMoreMessages.value = page.hasMore
                    fillHistoryGap(page.messages)
                    ChatDebugLog.log("[ChatVM] Room Flow emit: ${page.messages.size} visible messages")
                }
            } catch (e: CancellationException) {
                ChatDebugLog.log("[ChatVM] observeMessages CANCELLED (viewModelScope cancelled)")
                throw e
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "observeMessages Flow crashed, restarting...", e)
                ChatDebugLog.log("[ChatVM] observeMessages CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                delay(500)
                observeMessages()
            }
            ChatDebugLog.log("[ChatVM] observeMessages ENDED normally")
        }
    }

    private suspend fun fillHistoryGap(recent: List<ChatMessage>) {
        val older = _olderMessages.value
        val lastOlder = older.lastOrNull() ?: return
        val firstRecent = recent.firstOrNull() ?: return
        if (firstRecent.id - lastOlder.id <= 1L) return
        val fill = chatRepository.getMessagesBetweenIdsSync(companionId, lastOlder.id, firstRecent.id)
        if (fill.isNotEmpty()) {
            _olderMessages.value = (older + fill).distinctBy { it.id }
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
        }
    }

    fun loadMoreHistory() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            delay(200)
            try {
                val boundary = _olderMessages.value.firstOrNull()
                    ?: _recentMessages.value.firstOrNull()
                    ?: return@launch
                val page = chatRepository.getVisibleBefore(
                    companionId,
                    boundary.timestamp,
                    boundary.id,
                    ChatConstants.CHAT_LOAD_MORE_SIZE
                )
                if (page.messages.isNotEmpty()) {
                    _olderMessages.value = (page.messages + _olderMessages.value).distinctBy { it.id }
                }
                _reachedEnd = !page.hasMore
                _hasMoreMessages.value = !_reachedEnd
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "loadMoreHistory failed", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private var avatarUnsubscribe: (() -> Unit)? = null
    private var nicknameUnsubscribe: (() -> Unit)? = null

    private fun loadUserProfile() {
        val provider = ServiceRegistry.get(UserProfileProvider::class.java)
        _userName.value = provider?.getNickname() ?: "我"
        _userAvatar.value = provider?.getAvatar()
        nicknameUnsubscribe = provider?.observeNickname { nickname ->
            _userName.value = nickname
        }
        avatarUnsubscribe = provider?.observeAvatar { avatar ->
            _userAvatar.value = avatar
        }
    }

    private fun loadCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                companionRepository.getCompanionByIdFlow(companionId).collect { companion ->
                    _companionData.value = companion
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "loadCompanionData failed", e)
            }
        }
    }

    fun refreshCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _companionData.value = companionRepository.getCompanionById(companionId)
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "refreshCompanionData failed", e)
            }
        }
    }

    fun sendMessage(content: String) {
        ChatDebugLog.log("[ChatVM] sendMessage called")
        if (com.xingshi.ai.common.AccountStorage.partition != accountPartition) {
            clearDraftAttachments()
            _events.tryEmit(ChatUiEvent.ThemedError("ui_attach_account_switched"))
            return
        }
        val attachments = _draftAttachments.value
        if (content.isBlank() && attachments.isEmpty()) return
        if (!sendInFlight.compareAndSet(false, true)) return
        val capturedAt = System.currentTimeMillis()
        attachments.filter { it.path.isNotBlank() }.forEach { inFlightDraftPaths += it.path }
        viewModelScope.launch(Dispatchers.IO) {
            var accepted = false
            try {
                val outcome = if (attachments.isEmpty()) {
                    ConversationIntake.submit(
                        app = getApplication(),
                        companionId = companionId,
                        text = content,
                        source = ConversationIntake.Source.LOCAL,
                        capturedAt = capturedAt,
                        accountPartition = accountPartition
                    )
                } else {
                    ConversationIntake.submitDraftAttachments(
                        app = getApplication(),
                        companionId = companionId,
                        text = content,
                        attachments = attachments,
                        capturedAt = capturedAt,
                        accountPartition = accountPartition
                    )
                }
                if (outcome.accepted) {
                    accepted = true
                    if (attachments.isNotEmpty()) {
                        val sentIds = attachments.map { it.id }.toSet()
                        _draftAttachments.update { current -> current.filterNot { it.id in sentIds } }
                    }
                    _draftAttachmentFailure.value = ""
                    if (_draft.value.trim() == content.trim()) _draft.value = ""
                } else {
                    notifySendRejection(outcome)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendMessage failed", e)
                _events.tryEmit(ChatUiEvent.ThemedError(chatSendFailureKey(e)))
            } finally {
                attachments.filter { it.path.isNotBlank() }.forEach { inFlightDraftPaths -= it.path }
                val retained = _draftAttachments.value.map { it.path }.toSet()
                attachments.forEach { attachment ->
                    if (attachment.path.isBlank()) return@forEach
                    if (accepted || attachment.path !in retained) releaseDraftCacheFile(attachment.path)
                }
                sendInFlight.set(false)
            }
        }
    }

    private fun notifySendRejection(outcome: ConversationIntake.Outcome) {
        val key = outcome.failureKey
        _draftAttachmentFailure.value = key.orEmpty()
        if (key != null) { _events.tryEmit(ChatUiEvent.ThemedError(key)); return }
        when (outcome.rejection) {
            ConversationIntake.Rejection.BLOCKED ->
                _events.tryEmit(ChatUiEvent.ContentBlocked(outcome.reason ?: "安全检查未完成"))
            ConversationIntake.Rejection.QUEUE_FULL ->
                _events.tryEmit(ChatUiEvent.Error("消息队列已满，请稍后再试"))
            else -> _events.tryEmit(ChatUiEvent.Error(outcome.reason ?: "消息发送失败"))
        }
    }

    fun retryAttachmentPreparation() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backendConsumer?.retryAttachments(companionId) }
        }
    }

    private suspend fun checkUserInput(content: String): Boolean {
        if (BanManager.isBanned(getApplication())) {
            val banInfo = BanManager.getBanInfo(getApplication())
            val banMsg = if (banInfo.remainingDays > 0) {
                "账号已被封禁（剩余${banInfo.remainingDays}天${banInfo.remainingHours}小时），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else if (banInfo.remainingHours > 0) {
                "账号已被封禁（剩余${banInfo.remainingHours}小时${banInfo.remainingMinutes}分钟），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else {
                "账号已被封禁，原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。请完成安全答题以解除封禁。"
            }
            ChatDebugLog.log("[ChatVM] BLOCKED: account is banned - $banMsg")
            _events.tryEmit(ChatUiEvent.Error(banMsg))
            return false
        }

        val pipelineOk = try {
            withTimeoutOrNull(TimeoutBudgets.PIPELINE_EXECUTE_MS) {
                pipeline.execute(MessagePipeline.PipelineInput(rawText = content, companionId = companionId))
            }
        } catch (_: Exception) {
            false
        }

        if (pipelineOk != true) {
            val err = pipeline.pipelineState.value.error ?: "安全检查未完成"
            ChatDebugLog.log("[ChatVM] BLOCKED by pipeline: $err")
            _events.tryEmit(ChatUiEvent.ContentBlocked(err))
            return false
        }
        return true
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.clearChatHistory(companionId)
                _olderMessages.value = emptyList()
                _reachedEnd = false
                SecureLog.i("ChatViewModel", "Chat history cleared for companion=$companionId")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "clearChatHistory failed", e)
            }
        }
    }

    fun deleteCompanionPermanently(onDeleted: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloud = companionCloud
                    ?: run { _events.tryEmit(ChatUiEvent.ThemedError("ui_companion_delete_failed")); return@launch }
                if (!cloud.delete(companionId)) { _events.tryEmit(ChatUiEvent.ThemedInfo("ui_companion_delete_pending")); return@launch }
                withContext(Dispatchers.Main) { onDeleted() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(ChatUiEvent.ThemedError("ui_companion_delete_failed", e.message))
            }
        }
    }

    fun recallMessage(message: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.deleteMessage(message)
                SecureLog.d("ChatViewModel", "消息已撤回: id=${message.id}")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "recallMessage failed", e)
            }
        }
    }

    fun sendSticker(sticker: StickerInfo) {
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        sendMessage("[$stickerId]")
    }

    private data class PendingVoiceRetry(val audioPath: String, val durationSeconds: Int, val inputKey: String)

    private var pendingVoiceRetry: PendingVoiceRetry? = null
    private val voiceInputs = mutableMapOf<String, Any>()

    private val _voiceRetryAvailable = MutableStateFlow(false)
    val voiceRetryAvailable: StateFlow<Boolean> = _voiceRetryAvailable.asStateFlow()

    fun retryVoiceRecognition() {
        val pending = synchronized(voiceInputs) { pendingVoiceRetry } ?: return
        sendVoiceInput(pending.audioPath, pending.durationSeconds, pending.inputKey)
    }

    fun retryReplyVoiceSynthesis(message: ChatMessage) {
        if (message.id <= 0L || message.isFromUser) return
        if (message.companionId != companionId) return
        if (message.type != MessageType.TEXT || message.content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val tts = TextToSpeechService.synthesize(getApplication(), message.content)) {
                    is TextToSpeechService.Outcome.Success -> {
                        val stored = chatRepository.getMessageById(message.id) ?: return@launch
                        if (stored.isFromUser || stored.type != MessageType.TEXT) return@launch
                        chatRepository.updateMessage(
                            stored.copy(
                                type = MessageType.VOICE,
                                fileFormat = FileFormat.AUDIO,
                                linkString = tts.audioPath
                            )
                        )
                        VoiceTurnMetadataStore.save(
                            context = getApplication(),
                            messageId = stored.id,
                            companionId = stored.companionId,
                            source = "TTS_REPLY",
                            capturedAt = System.currentTimeMillis(),
                            durationMs = tts.durationMs,
                            transcript = stored.content
                        )
                        _events.tryEmit(ChatUiEvent.ThemedInfo("ui_chat_tts_retry_done"))
                    }
                    TextToSpeechService.Outcome.NotInstalled ->
                        _events.tryEmit(ChatUiEvent.ThemedError("ui_chat_tts_retry_plugin_missing"))
                    TextToSpeechService.Outcome.NotConfigured ->
                        _events.tryEmit(ChatUiEvent.ThemedError("ui_chat_tts_retry_config_missing"))
                    is TextToSpeechService.Outcome.Failed ->
                        _events.tryEmit(
                            if (tts.code.isNotBlank()) {
                                ChatUiEvent.ThemedError("ui_tts_error_${tts.code}")
                            } else {
                                ChatUiEvent.ThemedError("ui_chat_tts_retry_failed", tts.reason)
                            }
                        )
                }
            } catch (e: Exception) {
                _events.tryEmit(ChatUiEvent.ThemedError("ui_chat_tts_retry_failed"))
            }
        }
    }

    private fun rememberVoiceRetry(path: String?, duration: Int, inputKey: String, attempt: Any) {
        if (path == null) return
        synchronized(voiceInputs) {
            if (voiceInputs[inputKey] !== attempt) return
            voiceInputs.remove(inputKey)
            pendingVoiceRetry = PendingVoiceRetry(path, duration, inputKey)
            _voiceRetryAvailable.value = true
        }
    }

    fun sendVoiceMessage(audioPath: String, duration: Int) {
        sendVoiceInput(audioPath, duration, audioPath)
    }

    private fun sendVoiceInput(audioPath: String, duration: Int, inputKey: String) {
        val attempt = Any()
        synchronized(voiceInputs) {
            if (voiceInputs.containsKey(inputKey)) return
            voiceInputs[inputKey] = attempt
            pendingVoiceRetry = null
            _voiceRetryAvailable.value = false
        }
        viewModelScope.launch(Dispatchers.IO) {
            var storedPath: String? = null
            var accepted = false
            try {
                storedPath = persistAttachment(audioPath, "voice")
                val mimeType = when (java.io.File(audioPath).extension.lowercase()) {
                    "m4a", "mp4" -> "audio/mp4"
                    "wav" -> "audio/wav"
                    "mp3" -> "audio/mpeg"
                    "aac" -> "audio/aac"
                    "ogg", "opus" -> "audio/ogg"
                    "amr" -> "audio/amr"
                    else -> "application/octet-stream"
                }
                when (val outcome = SpeechRecognitionService.recognize(
                    getApplication(),
                    storedPath,
                    mimeType,
                    duration.coerceAtLeast(1) * 1000L
                )) {
                    is SpeechRecognitionService.Outcome.Recognized -> {
                        currentCoroutineContext().ensureActive()
                        val text = outcome.text.trim()
                        if (text.isBlank()) {
                            rememberVoiceRetry(storedPath, duration, inputKey, attempt)
                            _events.tryEmit(ChatUiEvent.Error("没有识别到语音内容，请重试"))
                            return@launch
                        }
                        val voiceMessage = ChatMessage(
                            companionId = companionId,
                            content = text,
                            isFromUser = true,
                            timestamp = System.currentTimeMillis(),
                            type = MessageType.VOICE,
                            fileFormat = FileFormat.AUDIO,
                            linkString = storedPath
                        )
                        val result = ConversationIntake.submit(
                            app = getApplication(),
                            companionId = companionId,
                            text = text,
                            source = ConversationIntake.Source.LOCAL,
                            presetUserMessage = voiceMessage,
                            inputKind = ConversationIntake.InputKind.VOICE_MESSAGE,
                            durationMs = duration.coerceAtLeast(1) * 1000L,
                            accountPartition = accountPartition
                        )
                        accepted = result.accepted
                        if (result.accepted && result.userMessageId > 0L) {
                            VoiceTurnMetadataStore.save(
                                context = getApplication(),
                                messageId = result.userMessageId,
                                companionId = companionId,
                                source = "VOICE_MESSAGE",
                                capturedAt = System.currentTimeMillis(),
                                durationMs = duration.coerceAtLeast(1) * 1000L,
                                transcript = text
                            )
                        }
                        if (!result.accepted) {
                            _events.tryEmit(ChatUiEvent.Error(result.reason ?: "语音发送失败"))
                        }
                    }
                    SpeechRecognitionService.Outcome.NotInstalled ->
                        _events.tryEmit(ChatUiEvent.Error("未安装语音识别插件，请先在插件市场安装"))
                    SpeechRecognitionService.Outcome.NotConfigured ->
                        _events.tryEmit(ChatUiEvent.Error("未配置语音识别模型，请先在设置中填写 STT 配置"))
                    is SpeechRecognitionService.Outcome.Failed -> {
                        rememberVoiceRetry(storedPath, duration, inputKey, attempt)
                        _events.tryEmit(ChatUiEvent.Error("语音识别失败：${outcome.reason}"))
                    }
                }
                SecureLog.d("ChatViewModel", "Voice message processed, duration=$duration")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendVoiceMessage failed", e)
                if (!accepted) rememberVoiceRetry(storedPath, duration, inputKey, attempt)
                _events.tryEmit(ChatUiEvent.Error("语音发送失败：${e.message?.take(50) ?: "未知错误"}"))
            } finally {
                synchronized(voiceInputs) {
                    if (!accepted && voiceInputs[inputKey] === attempt) voiceInputs.remove(inputKey)
                }
            }
        }
    }

    private fun persistAttachment(path: String, kind: String): String {
        val source = java.io.File(path)
        if (!source.isFile) return path
        val app = getApplication<Application>()
        val inCache = runCatching {
            source.canonicalPath.startsWith(accountCache.canonicalPath + java.io.File.separator)
        }.getOrDefault(false)
        if (!inCache) return path
        val dir = java.io.File(accountFiles, "attachments/$kind").apply { mkdirs() }
        val target = java.io.File(dir, "${System.currentTimeMillis()}_${source.name}")
        return runCatching {
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrDefault(path)
    }

    override fun onCleared() {
        super.onCleared()
        clearDraftAttachments()
        val entry = boundSendEntry
        if (entry != null) {
            PluginFramework.manager()?.unbindSession(companionId, entry)
            boundSendEntry = null
        }
        if (bindChannelEntry) {
            InboundTurnDispatcher.unregisterPage(companionId, this)
        }
        applicationApiScope.cancel()
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        nicknameUnsubscribe?.invoke()
        nicknameUnsubscribe = null
        chatTypingState.stopTyping()
        _reasoningText.value = ""
        _isReasoning.value = false
        _recentMessages.value = emptyList()
        _olderMessages.value = emptyList()
        _reachedEnd = false
        _hasMoreMessages.value = false
    }
}

class ChatViewModelFactory(
    private val application: Application,
    private val companionId: Long,
    private val bindChannelEntry: Boolean = true
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(application, companionId, bindChannelEntry) as T
    }
}

internal fun chatSendFailureKey(failure: Throwable): String =
    if (failure is com.xingshi.ai.network.StaleBackendSession) "ui_attach_account_switched" else "ui_attach_failed"
