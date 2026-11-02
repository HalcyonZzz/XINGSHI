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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ChatViewModel(
    application: Application,
    private val companionId: Long,
    /** 详情页只读取会话，不维护插件发送口；发送会话归仍打开的聊天页或应用级入口。 */
    private val bindChannelEntry: Boolean = true
) : AndroidViewModel(application) {

    // 实例级作用域，用于消息落库、队列消费等与 UI 生命周期一致的操作
    private val _appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        ChatDebugLog.log("[ChatVM] applicationApiScope UNCAUGHT: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatViewModel", "applicationApiScope uncaught exception", throwable)
    }
    private val applicationApiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + _appExceptionHandler)

    private val database = AppDatabase.getDatabase(application)
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val companionRepository = CompanionRepository(database.companionDao())
    private val contextResolver = ChatContextResolver()

    // ── 消息：Room Flow 做最新一页数据源，_olderMessages 做加载的历史 ──
    // 进入时先从 ChatRepository 内存缓存读取初始数据（HomeViewModel 预热），避免 loading
    private val _recentMessages = MutableStateFlow<List<ChatMessage>>(
        chatRepository.getCachedRecent(companionId) ?: emptyList()
    )
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(_recentMessages, _olderMessages) { recent, older ->
        // UI 消息列表做上限保护，避免长对话时内存无限增长
        contextResolver.capUiMessages(older + recent).first.filter { message ->
            // 通话逐句实录供原对话与承接使用，但不在普通聊天展开；通话卡片本身保留。
            message.type == MessageType.CALL || !message.linkString.startsWith("call:")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), chatRepository.getCachedRecent(companionId) ?: emptyList())

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    // true when a loadMore returned fewer than ChatConstants.CHAT_LOAD_MORE_SIZE, meaning no older messages
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

    // Typing indicator state for this chat
    private val chatTypingState = ChatTypingState()
    val isTyping: StateFlow<Boolean> = chatTypingState.isTyping
    val typingText: StateFlow<String> = chatTypingState.typingText

    /** 应用级队列深度：队列由 ConversationIntake 跨页面持有，聊天页只消费。 */
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

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    /** 消息处理流水线 — 与通道共用应用级入口的检查实现 */
    val pipeline = ConversationIntake.pipeline

    // 打包状态: 合并所有 StateFlow 为单一 observable
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
        // 后台预热安全检查模块；即使失败，ContentFilter.checkKeywords 已降级为纯 Java 正则
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
            // 只有真正持有聊天页身份时才是消费者与发送口所有者；详情页不登记，不会覆盖/删除。
            registerMessageConsumer()
            bindPluginSession()
            collectVerificationFragments()
        }
    }

    /** 页面当前处理的通道轮次：受控回复终结时按它交回原请求，避免回复落到错误联系人。 */
    private val pageTurnContext = PageTurnContext()

    private fun collectVerificationFragments() {
        if (ServiceRegistry.get(BackendChatConsumer::class.java) != null) return
        applicationApiScope.launch {
            val callBuffers = mutableMapOf<String, StringBuilder>()
            val ordinaryBuffers = mutableMapOf<Long, StringBuilder>()
            var ordinaryUiInputId = 0L
            ChatVerificationBus.events.collect { fragment ->
                if (fragment.companionId != companionId) return@collect
                // 通话轮次走同一终结链落库，再把「原请求/已存消息」交给通话控制器消费；
                // 逐句实录不展开成普通气泡。
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
                                // 正式交接由 AiResponseFinalizer 在落库后统一发布，不在此重复一次。
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
                    // 与真实流式一致：末段可能还没达到发布阈值，交接前先取出缓冲尾段，
                    // 再走既有终结/保存合同（不是调试路径另写一次库）
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
                            // 普通语音消息：先保存正式文字，再按原输入方式合成同一消息的语音；失败保留文字。
                            if (id > 0 && turn?.inputKind == ConversationIntake.InputKind.VOICE_MESSAGE) {
                                when (val tts = TextToSpeechService.synthesize(getApplication(), full)) {
                                    is TextToSpeechService.Outcome.Success -> {
                                        chatRepository.getMessageById(id)?.let { message ->
                                            chatRepository.updateMessage(
                                                message.copy(
                                                    type = MessageType.VOICE,
                                                    fileFormat = FileFormat.AUDIO,
                                                    linkString = tts.audioPath
                                                )
                                            )
                                            VoiceTurnMetadataStore.save(
                                                context = getApplication(),
                                                messageId = id,
                                                companionId = companionId,
                                                source = "TTS_REPLY",
                                                capturedAt = System.currentTimeMillis(),
                                                durationMs = tts.durationMs,
                                                transcript = full
                                            )
                                        }
                                    }
                                    TextToSpeechService.Outcome.NotInstalled -> Unit
                                    TextToSpeechService.Outcome.NotConfigured -> Unit
                                    is TextToSpeechService.Outcome.Failed ->
                                        _events.tryEmit(
                                            if (tts.code.isNotBlank()) {
                                                ChatUiEvent.ThemedError("ui_tts_error_${tts.code}")
                                            } else {
                                                ChatUiEvent.ThemedError("ui_chat_tts_retry_failed", tts.reason)
                                            }
                                        )
                                }
                            }
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

    /** 本页面名下的发送口；退出时只解绑自己绑的那个，不能覆盖详情页或应用级入口。 */
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

    /**
     * 页面只登记处理函数，队列消费由应用级 [InboundTurnDispatcher] 唯一持有：
     * 未打开聊天页时通道入站仍会被消费并回传，打开/离开页面不增加第二消费者。
     */
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
                // 私聊前置：先补齐该实例合法群增量，再进入普通会话读取。
                runCatching {
                    com.xingshi.ai.domain.ServiceRegistry.get(
                        com.xingshi.ai.domain.GroupConversationGateway::class.java
                    )?.synchronizeBeforePrivateChat(companionId)
                }
                chatRepository.getMessagesForCompanion(companionId, ChatConstants.CHAT_PAGE_SIZE).collect {
                    // 隐藏实录不占可见页：始终用过滤感知分页重算本页与是否还有更早可见消息。
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
                // 延迟重启，避免快速重试风暴
                delay(500)
                observeMessages()
            }
            ChatDebugLog.log("[ChatVM] observeMessages ENDED normally")
        }
    }

    /**
     * 最新页是滑动窗口：加载过旧页后又有新消息插入时，旧页末尾与最新页开头之间会露出空洞。
     * 按 id 区间补一次交界消息，保持 older + recent 连续。
     */
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
            delay(200) // 短暂延迟让加载指示器可见
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
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = ConversationIntake.submit(
                app = getApplication(),
                companionId = companionId,
                text = content,
                source = ConversationIntake.Source.LOCAL,
                accountPartition = accountPartition
            )
            if (outcome.accepted) return@launch
            when (outcome.rejection) {
                ConversationIntake.Rejection.BLOCKED ->
                    _events.tryEmit(ChatUiEvent.ContentBlocked(outcome.reason ?: "安全检查未完成"))
                ConversationIntake.Rejection.QUEUE_FULL ->
                    _events.tryEmit(ChatUiEvent.Error("消息队列已满，请稍后再试"))
                else -> _events.tryEmit(ChatUiEvent.Error(outcome.reason ?: "消息发送失败"))
            }
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
                val companion = _companionData.value ?: companionRepository.getCompanionById(companionId) ?: return@launch
                val cloud = companionCloud
                if (cloud != null) {
                    if (!cloud.delete(companionId)) { _events.tryEmit(ChatUiEvent.Info("助手正在删除，确认完成后再移除本机资料")); return@launch }
                    withContext(Dispatchers.Main) { onDeleted() }
                    return@launch
                }
                companionRepository.deleteCompanion(companion)
                SecureLog.i("ChatViewModel", "Companion deleted permanently: $companionId")
                withContext(Dispatchers.Main) { onDeleted() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(ChatUiEvent.Error(e.message ?: "删除尚未完成，请重连后确认"))
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

    /**
     * 发送语音消息（用户录制，duration 单位为秒）
     */
    private data class PendingVoiceRetry(val audioPath: String, val durationSeconds: Int, val inputKey: String)

    private var pendingVoiceRetry: PendingVoiceRetry? = null
    private val voiceInputs = mutableMapOf<String, Any>()

    private val _voiceRetryAvailable = MutableStateFlow(false)
    /** 识别失败后原件仍可重试；仅重识别这条语音，不重新录制、不重发原输入。 */
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
                // 先保存原件到耐久附件目录：识别失败时原件仍在，成功时同一条语音气泡引用它。
                storedPath = persistAttachment(audioPath, "voice")
                _events.tryEmit(ChatUiEvent.Info("正在识别语音…"))
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
                        // 退出/切角色后迟到的识别结果不得提交。
                        currentCoroutineContext().ensureActive()
                        val text = outcome.text.trim()
                        if (text.isBlank()) {
                            rememberVoiceRetry(storedPath, duration, inputKey, attempt)
                            _events.tryEmit(ChatUiEvent.Error("没有识别到语音内容，请重试"))
                            return@launch
                        }
                        // 一条正式本人消息：正文是转写文字，类型仍是语音，原件挂在同一行。
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
                            // 来源/采集时点/真实时长与同一消息持久绑定，重启后仍可取回。
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

    /**
     * 发送图片消息（保存图片原件路径并落库展示）
     */
    fun sendImageMessage(imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = ConversationIntake.submit(getApplication(), companionId, "",
                    attachments = listOf(com.xingshi.ai.plugins.api.PluginAttachment(imagePath, "image/*", com.xingshi.ai.plugins.api.PluginAttachmentKind.IMAGE)),
                    source = ConversationIntake.Source.LOCAL, accountPartition = accountPartition)
                if (!outcome.accepted) _events.tryEmit(ChatUiEvent.Error(outcome.reason ?: "图片保存失败"))
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendImageMessage failed", e)
                _events.tryEmit(ChatUiEvent.Error(e.message ?: "发送失败"))
            }
        }
    }

    /**
     * 发送视频消息（保存原件引用，聊天行可按引用重新定位已复制原件）
     */
    fun sendVideoMessage(videoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val storedPath = persistAttachment(videoPath, "video")
                val videoMessage = ChatMessage(
                    companionId = companionId,
                    content = "[视频]",
                    isFromUser = true,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.VIDEO,
                    fileFormat = FileFormat.VIDEO,
                    linkString = storedPath
                )
                chatRepository.sendMessage(videoMessage)
                SecureLog.d("ChatViewModel", "Video message saved")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendVideoMessage failed", e)
            }
        }
    }

    /**
     * cacheDir 是系统可清理目录，媒体原件落库前转存到 filesDir，保证长期引用可定位。
     */
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
        // 只解绑本页绑定的发送口：详情页/应用级入口可能仍持有同一会话的发送能力。
        val entry = boundSendEntry
        if (entry != null) {
            PluginFramework.manager()?.unbindSession(companionId, entry)
            boundSendEntry = null
        }
        // 应用级队列不随页面关闭：通道入站仍由应用级消费者处理，页面重开重新登记处理函数。
        // 注销只撤销本 VM 自己的登记，详情页退出不会删除原聊天页的 handler。
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
