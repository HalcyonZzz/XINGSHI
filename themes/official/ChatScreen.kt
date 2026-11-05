package com.xingshi.ai.feature.chat.ui.screen

import com.xingshi.ai.theme.framework.rememberThemeState
import com.xingshi.ai.theme.framework.ThemeRuntime
import android.util.Log

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.xingshi.ai.themes.official.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingshi.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.xingshi.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.xingshi.ai.feature.chat.ui.viewmodel.ChatUiEvent
import com.xingshi.ai.feature.chat.ui.viewmodel.DraftAttachmentKind
import com.xingshi.ai.feature.chat.ui.viewmodel.DraftAttachmentState
import com.xingshi.ai.feature.chat.ChatDraftHandoff
import com.xingshi.ai.feature.chat.ChatStatusDisplay
import com.xingshi.ai.feature.chat.data.ChatDetailSettingsStore
import com.xingshi.ai.uicommon.component.WeChatChatInputBar
import com.xingshi.ai.uicommon.component.StickerPanel
import com.xingshi.ai.uicommon.component.CompanionAvatar
import com.xingshi.ai.uicommon.component.VoiceRecorder
import com.xingshi.ai.uicommon.component.getChatBackgroundByKey
import com.xingshi.ai.uicommon.component.getChatBackgroundKey
import com.xingshi.ai.uicommon.component.isCustomBackground
import com.xingshi.ai.uicommon.component.rememberBackgroundBitmap
import com.xingshi.ai.uicommon.theme.rememberAdaptiveSizing
import com.xingshi.ai.common.ReadStatusManager
import com.xingshi.ai.common.HardwareInfo
import com.xingshi.ai.common.StickerManager
import com.xingshi.ai.plugins.ui.PluginFailureToast
import com.xingshi.ai.plugins.ui.PluginInputActions
import com.xingshi.ai.plugins.ui.PluginPendingBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginOutcome
import com.xingshi.ai.plugins.api.PluginReport

@Composable
fun ChatScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {},
    onOpenPluginPage: (String) -> Unit = {},
    onOpenMainModelSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val companionData by viewModel.companionData.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val typingText by viewModel.typingText.collectAsState()
    val isReasoning by viewModel.isReasoning.collectAsState()
    val reasoningText by viewModel.reasoningText.collectAsState()
    val hasMore by viewModel.hasMoreMessages.collectAsState()
    val loadingMore by viewModel.isLoadingMore.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val draftAttachments by viewModel.draftAttachments.collectAsState()
    val draftAttachmentFailure by viewModel.draftAttachmentFailure.collectAsState()
    val shareHandoffs by ChatDraftHandoff.entries.collectAsState()
    val shareHandoff = shareHandoffs[companionId]
    val backendStatus by viewModel.backendStatus.collectAsState()
    val backendNextAction by viewModel.backendNextAction.collectAsState()
    val backendModelChoices by viewModel.backendModelChoices.collectAsState()
    backendModelChoices?.let { choices ->
        AlertDialog(onDismissRequest = viewModel::dismissBackendModels, title = { Text("普通聊天模型") },
            text = {
                Column {
                    if (choices.isEmpty()) {
                        Text("该助手还没有可用的普通聊天模型，请先在设置中配置")
                        TextButton(onClick = { viewModel.dismissBackendModels(); onOpenMainModelSettings() }) { Text("去设置") }
                    }
                    choices.forEach { choice ->
                        TextButton(onClick = { viewModel.chooseBackendModel(choice) }) {
                            Text((if (choice.selected) "✓ " else "") + choice.label)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = viewModel::dismissBackendModels) { Text("关闭") } })
    }
    val pluginManager = remember { PluginFramework.manager() }
    val pluginOutcomeFlow = remember(pluginManager) {
        pluginManager?.outcomes ?: kotlinx.coroutines.flow.MutableStateFlow<Map<Long, PluginOutcome>>(emptyMap())
    }
    val pluginOutcomes by pluginOutcomeFlow.collectAsState()
    val pluginOutcome = pluginOutcomes[companionId]
    val stickerManager = remember { StickerManager.getInstance(context) }
    val voiceRecorder = remember { VoiceRecorder.getInstance(context) }
    var recording by remember { mutableStateOf(false) }
    var uiMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shareHandoff?.id) {
        val pending = shareHandoff ?: return@LaunchedEffect
        val taken = ChatDraftHandoff.take(companionId, pending.id) ?: return@LaunchedEffect
        val bilibili = taken.bilibili
        when {
            bilibili != null -> viewModel.addDraftAttachment(
                path = "",
                fileName = bilibili.title.orEmpty(),
                mimeType = "",
                kind = DraftAttachmentKind.BILIBILI,
                sourceUrl = bilibili.sourceUrl
            )
            taken.text.isNotBlank() -> viewModel.onDraftChange(
                listOf(viewModel.draft.value, taken.text).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
        taken.noticeKey?.let { uiMessage = ThemeRuntime.resolveText(it) }
    }
    var pendingVoiceStart by remember { mutableStateOf(false) }
    var showVoiceCall by remember { mutableStateOf(false) }
    val callActive by com.xingshi.ai.feature.chat.VoiceCallCoordinator.active.collectAsState()
    val callState by com.xingshi.ai.feature.chat.VoiceCallCoordinator.state.collectAsState()
    val callError by com.xingshi.ai.feature.chat.VoiceCallCoordinator.error.collectAsState()
    var showVideoCall by remember { mutableStateOf(false) }
    var videoCallStarted by remember { mutableStateOf(false) }
    var callElapsedMs by remember { mutableLongStateOf(0L) }
    val videoCamera = remember { com.xingshi.ai.feature.chat.VideoCallCamera(context) }
    val canSwitchCamera = remember { videoCamera.canSwitchLens }
    var cameraSwitching by remember { mutableStateOf(false) }
    var cameraFront by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
                videoCamera.stop()
                showVoiceCall = false
                showVideoCall = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (callActive != null) com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
            videoCamera.stop()
        }
    }
    LaunchedEffect(callActive?.callId) {
        val active = callActive ?: return@LaunchedEffect
        callElapsedMs = 0L
        while (true) {
            callElapsedMs = (android.os.SystemClock.elapsedRealtime() - active.startedElapsedMs).coerceAtLeast(0L)
            kotlinx.coroutines.delay(250)
        }
    }
    val callPrepareMessage: (com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare) -> String? = { prepare ->
        when (prepare) {
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.READY -> null
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_MISSING -> ThemeRuntime.resolveText("ui_call_stt_missing")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_MISSING -> ThemeRuntime.resolveText("ui_call_tts_missing")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_MISSING -> ThemeRuntime.resolveText("ui_call_vision_missing")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_NOT_CONFIGURED -> ThemeRuntime.resolveText("ui_call_stt_config")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_NOT_CONFIGURED -> ThemeRuntime.resolveText("ui_call_tts_config")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_NOT_CONFIGURED -> ThemeRuntime.resolveText("ui_call_vision_config")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.CAMERA_PERMISSION_MISSING -> ThemeRuntime.resolveText("ui_call_permission_camera")
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.PERMISSION_MISSING -> ThemeRuntime.resolveText("ui_call_permission_mic")
        }
    }
    val videoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[android.Manifest.permission.CAMERA] == true &&
            granted[android.Manifest.permission.RECORD_AUDIO] == true
        ) {
            showVideoCall = true
        } else {
            uiMessage = ThemeRuntime.resolveText("ui_call_permission_camera_mic")
        }
    }
    val startVideoCall = {
        scope.launch {
            when (com.xingshi.ai.feature.chat.VoiceCallCoordinator.canStart(context, video = true)) {
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.READY ->
                    videoPermissionLauncher.launch(
                        arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                    )
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_stt_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_tts_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_vision_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_stt_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_tts_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_vision_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.CAMERA_PERMISSION_MISSING,
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.PERMISSION_MISSING ->
                    videoPermissionLauncher.launch(
                        arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                    )
            }
        }
        Unit
    }
    LaunchedEffect(callActive) {
        if (showVideoCall && callActive == null) {
            videoCamera.stop()
            showVideoCall = false
        }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val prepare = com.xingshi.ai.feature.chat.VoiceCallCoordinator.canStart(context)
                val message = callPrepareMessage(prepare)
                if (message != null) {
                    uiMessage = message
                } else {
                    showVoiceCall = true
                    com.xingshi.ai.feature.chat.VoiceCallCoordinator.start(context, companionId, scope)
                }
            }
        } else {
            uiMessage = ThemeRuntime.resolveText("ui_call_permission_mic")
        }
    }
    val startVoiceCall = {
        scope.launch {
            when (com.xingshi.ai.feature.chat.VoiceCallCoordinator.canStart(context)) {
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.READY -> callPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_stt_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_tts_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.STT_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_stt_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.TTS_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_tts_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_vision_missing")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.VISION_NOT_CONFIGURED -> uiMessage = ThemeRuntime.resolveText("ui_call_vision_config")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.CAMERA_PERMISSION_MISSING -> uiMessage = ThemeRuntime.resolveText("ui_call_permission_camera")
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.Prepare.PERMISSION_MISSING -> callPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        Unit
    }
    LaunchedEffect(callActive) {
        if (showVoiceCall && callActive == null) showVoiceCall = false
    }
    LaunchedEffect(callError) {
        callError?.let { uiMessage = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            uiMessage = when (event) {
                is ChatUiEvent.Error -> event.message
                is ChatUiEvent.ContentBlocked -> "内容已拦截: ${event.reason}"
                is ChatUiEvent.Info -> event.message
                is ChatUiEvent.ThemedInfo -> ThemeRuntime.resolveText(event.key)
                is ChatUiEvent.ThemedError -> ThemeRuntime.resolveText(event.key) +
                    (event.detail?.let { ": $it" } ?: "")
            }
        }
    }

    var failedBackendStatus by remember { mutableStateOf("") }
    LaunchedEffect(backendStatus) {
        val failed = backendStatus.startsWith("ui_tts_error_") || backendStatus.startsWith("ui_chat_tts_retry_") ||
            backendStatus.startsWith("ui_doc_") || backendStatus.startsWith("ui_attach_") ||
            backendStatus.startsWith("ui_call_vision_") || backendStatus.startsWith("ui_video_") ||
            backendStatus.startsWith("ui_call_video_") || backendStatus.startsWith("ui_bilibili_")
        when {
            failed -> {
                failedBackendStatus = backendStatus
                uiMessage = ThemeRuntime.resolveText(backendStatus)
            }
            backendStatus == ChatStatusDisplay.PREPARING && failedBackendStatus.isNotBlank() -> {
                failedBackendStatus = ""
                uiMessage = null
            }
        }
    }

    val voiceRetryAvailable by viewModel.voiceRetryAvailable.collectAsState()
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingVoiceStart = false
            recording = voiceRecorder.start() != null
        } else {
            uiMessage = "需要麦克风权限才能发送语音"
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "image/*"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "image") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType)
                } else {
                    uiMessage = ThemeRuntime.resolveText("ui_chat_screen_01")
                }
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "file") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType)
                } else {
                    uiMessage = ThemeRuntime.resolveText("ui_chat_screen_06")
                }
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "video/*"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "video") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType, kind = DraftAttachmentKind.VIDEO)
                } else {
                    uiMessage = ThemeRuntime.resolveText("ui_chat_screen_04")
                }
            }
        }
    }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                runCatching { copyUriToCache(context, it) }.getOrNull()?.let { path -> stickerManager.importStickerZip(path) }
            }
        }
    }

    ThemePageSlot(
        page = ThemePages.CHAT,
        host = {
            ThemePageHost(
                page = ThemePages.CHAT,
                items = messages.map { message ->
                    ThemePageItem(
                        id = message.id.toString(),
                        kind = message.role,
                        title = message.content,
                        subtitle = if (message.isFromUser) themeText("chat_sender_me") else themeText("chat_sender_assistant"),
                        messageType = message.type.name.lowercase(),
                        mediaPath = com.xingshi.ai.feature.chat.ui.viewmodel.MessageAttachments
                            .decode(message.linkString)
                            ?.let { items -> items.firstOrNull { it.path.isNotBlank() }?.path.orEmpty() }
                            ?: message.linkString.ifBlank { message.content },
                        timestamp = message.timestamp
                    )
                },
                state = mapOf(
                    "companion_id" to companionId.toString(),
                    "companion_name" to companionData?.name.orEmpty(),
                    "message_count" to messages.size.toString(),
                    "draft" to draft,
                    "draft_attachment_count" to draftAttachments.size.toString(),
                    "draft_attachments" to draftAttachments.joinToString("|") { it.fileName },
                    "draft_attachment_items" to DraftAttachmentState.items(draftAttachments, draftAttachmentFailure),
                    "backend_status" to themeText(backendStatus),
                    "backend_next_action" to backendNextAction,
                    "is_typing" to isTyping.toString(),
                    "typing_text" to typingText,
                    "is_reasoning" to isReasoning.toString(),
                    "reasoning_text" to reasoningText,
                    "is_recording" to recording.toString(),
                    "has_more" to hasMore.toString(),
                    "is_loading_more" to loadingMore.toString(),
                    "plugin_message" to (pluginOutcome?.message ?: (pluginOutcome?.report as? PluginReport.Failure)?.message ?: ""),
                    "plugin_pending" to (pluginOutcome?.submitted == false).toString(),
                    "plugin_original_available" to pluginOutcome?.originalText?.isNotBlank().toString(),
                    "ui_message" to (uiMessage ?: ""),
                    "voice_retry" to voiceRetryAvailable.toString(),
                    "call_active" to (callActive != null).toString(),
                    "call_mode" to (callActive?.mode ?: ""),
                    "call_id" to (callActive?.callId ?: ""),
                    "call_state" to callState.name.lowercase()
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "send_text" -> {
                                val text = action.payload["text"] ?: draft
                                if (DraftAttachmentState.canSend(text, draftAttachments)) {
                                    viewModel.sendMessage(text)
                                }
                            }
                            "reconnect" -> viewModel.reconnectBackend()
                            "cancel_generation" -> viewModel.cancelBackendGeneration()
                            "regenerate" -> viewModel.regenerateBackendGeneration()
                            "select_chat_model" -> viewModel.loadBackendModels()
                            "replay_speech" -> viewModel.replayBackendSpeech()
                            "pick_image" -> imagePicker.launch("image/*")
                            "pick_file" -> filePicker.launch(DraftFileMimeTypes)
                            "remove_draft_attachment" -> action.payload["id"]?.let(viewModel::removeDraftAttachment)
                            "retry_attachment_preparation" -> viewModel.retryAttachmentPreparation()
                            "pick_video" -> videoPicker.launch("video/*")
                            "import_sticker_zip" -> stickerPicker.launch("application/zip")
                            "voice_start" -> {
                                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    recording = voiceRecorder.start() != null
                                } else {
                                    pendingVoiceStart = true
                                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            "voice_stop" -> {
                                if (recording) {
                                    val path = voiceRecorder.stop()
                                    recording = false
                                    if (path != null) {
                                        val seconds = ((voiceRecorder.getDurationMs() + 999) / 1000).coerceAtLeast(1)
                                        viewModel.sendVoiceMessage(path, seconds)
                                    }
                                }
                            }
                            "voice_cancel" -> { voiceRecorder.cancel(); recording = false }
                            "retry_voice" -> viewModel.retryVoiceRecognition()
                            "voice_call" -> startVoiceCall()
                            "video_call" -> startVideoCall()
                            "dismiss_message" -> uiMessage = null
                            "load_more" -> viewModel.loadMoreHistory()
                            "retry_plugin" -> scope.launch { pluginManager?.retryOutcome(companionId) }
                            "dismiss_plugin" -> pluginManager?.dismissOutcome(companionId)
                            "send_plugin_original" -> pluginOutcome?.originalText?.let { original ->
                                scope.launch {
                                    if (pluginManager?.sendOriginal(companionId, original) == true) {
                                        pluginManager.dismissOutcome(companionId)
                                    }
                                }
                            }
                            "open_plugin_page" -> action.payload["id"]?.let(onOpenPluginPage)
                            "open_detail" -> onNavigateToDetail(companionId)
                            "back" -> onNavigateBack()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            "detail" -> onNavigateToDetail(companionId)
                            "back" -> onNavigateBack()
                        }
                    }
                }
            )
        },
        fallback = {
            ChatScreenContent(
                companionId = companionId,
                onNavigateBack = onNavigateBack,
                onNavigateToDetail = onNavigateToDetail,
                onOpenPluginPage = onOpenPluginPage,
                uiMessage = uiMessage,
                onUiMessageShown = { uiMessage = null },
                onVoiceCall = startVoiceCall,
                onVideoCall = startVideoCall
            )
        }
    )
    LaunchedEffect(showVideoCall) {
        if (!showVideoCall) videoCallStarted = false
    }
    if (showVideoCall) {
        val endVideoCall: () -> Unit = {
            com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
            videoCamera.stop()
            showVideoCall = false
        }
        val switchCamera: () -> Unit = {
            if (!cameraSwitching) {
                scope.launch {
                    cameraSwitching = true
                    val result = videoCamera.switchLens()
                    cameraSwitching = false
                    when (result) {
                        com.xingshi.ai.feature.chat.VideoCallCamera.LensSwitch.Switched -> {
                            cameraFront = videoCamera.lensFacing ==
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                        }
                        com.xingshi.ai.feature.chat.VideoCallCamera.LensSwitch.Unavailable ->
                            uiMessage = ThemeRuntime.resolveText("ui_call_camera_switch_unavailable")
                        com.xingshi.ai.feature.chat.VideoCallCamera.LensSwitch.Busy -> Unit
                        is com.xingshi.ai.feature.chat.VideoCallCamera.LensSwitch.Failed ->
                            uiMessage = ThemeRuntime.resolveText("ui_call_camera_switch_failed")
                    }
                }
            }
        }
        Dialog(
            onDismissRequest = endVideoCall,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val videoPreview: @Composable () -> Unit = {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        android.view.SurfaceView(viewContext).also { surfaceView ->
                            videoCamera.open(
                                surfaceView,
                                onReady = { ready ->
                                    if (ready) {
                                        scope.launch {
                                            val prepare = com.xingshi.ai.feature.chat.VoiceCallCoordinator.canStart(context, video = true)
                                            val message = callPrepareMessage(prepare)
                                            if (message != null) {
                                                uiMessage = message
                                                endVideoCall()
                                            } else if (!videoCallStarted) {
                                                videoCallStarted = true
                                                com.xingshi.ai.feature.chat.VoiceCallCoordinator.start(
                                                    context, companionId, scope, video = true, camera = videoCamera
                                                )
                                            }
                                        }
                                    } else {
                                        uiMessage = ThemeRuntime.resolveText("ui_call_camera_unavailable")
                                        endVideoCall()
                                    }
                                },
                                onError = { reason ->
                                    uiMessage = reason
                                    endVideoCall()
                                }
                            )
                        }
                    }
                )
            }
            val videoBody: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    videoPreview()
                    androidx.compose.material3.FilledIconButton(
                        onClick = switchCamera,
                        enabled = canSwitchCamera && !cameraSwitching,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = ThemeRuntime.resolveText("ui_call_camera_switch"),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        val avatar = companionData?.avatarUrl
                        if (avatar.isNullOrBlank()) {
                            Text(
                                text = (companionData?.name ?: "").take(1),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            AsyncImage(
                                model = avatar,
                                contentDescription = companionData?.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }
                    Text(
                        text = when (callState) {
                            com.xingshi.ai.feature.chat.VoiceCallController.State.PREPARING -> ThemeRuntime.resolveText("ui_call_state_preparing")
                            com.xingshi.ai.feature.chat.VoiceCallController.State.LISTENING -> ThemeRuntime.resolveText("ui_call_state_listening")
                            com.xingshi.ai.feature.chat.VoiceCallController.State.RECOGNIZING -> ""
                            com.xingshi.ai.feature.chat.VoiceCallController.State.WAITING_REPLY -> ThemeRuntime.resolveText("ui_call_state_waiting")
                            com.xingshi.ai.feature.chat.VoiceCallController.State.SYNTHESIZING -> ""
                            com.xingshi.ai.feature.chat.VoiceCallController.State.PLAYING -> ThemeRuntime.resolveText("ui_call_state_playing")
                            com.xingshi.ai.feature.chat.VoiceCallController.State.ENDED -> ThemeRuntime.resolveText("ui_call_state_ended")
                        },
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(bottom = 168.dp)
                    )
                    Text(
                        text = "%02d:%02d".format(callElapsedMs / 1000 / 60, callElapsedMs / 1000 % 60),
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(bottom = 128.dp)
                    )
                    androidx.compose.material3.FilledIconButton(
                        onClick = endVideoCall,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Call,
                            contentDescription = ThemeRuntime.resolveText("ui_call_hangup"),
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
            ThemePageSlot(
                page = ThemePages.VIDEO_CALL,
                host = {
                    ThemePageHost(
                        page = ThemePages.VIDEO_CALL,
                        items = emptyList(),
                        state = mapOf(
                            "call_id" to (callActive?.callId ?: ""),
                            "call_mode" to (callActive?.mode ?: ""),
                            "call_state" to callState.name.lowercase(),
                            "companion_id" to companionId.toString(),
                            "companion_name" to companionData?.name.orEmpty(),
                            "companion_avatar" to companionData?.avatarUrl.orEmpty(),
                            "elapsed_ms" to callElapsedMs.toString(),
                            "camera_facing" to if (cameraFront) "front" else "back",
                            "can_switch_camera" to canSwitchCamera.toString(),
                            "camera_switching" to cameraSwitching.toString()
                        ),
                        content = videoPreview,
                        dispatch = { action ->
                            if (action is ThemePageAction.Invoke) {
                                when (action.id) {
                                    "hangup" -> endVideoCall()
                                    "switch_camera" -> switchCamera()
                                }
                            }
                        }
                    )
                },
                fallback = videoBody
            )
        }
    }
    if (showVoiceCall) {
        val label = when (callState) {
            com.xingshi.ai.feature.chat.VoiceCallController.State.PREPARING -> ThemeRuntime.resolveText("ui_call_state_preparing")
            com.xingshi.ai.feature.chat.VoiceCallController.State.LISTENING -> ThemeRuntime.resolveText("ui_call_state_listening")
            com.xingshi.ai.feature.chat.VoiceCallController.State.RECOGNIZING -> ""
            com.xingshi.ai.feature.chat.VoiceCallController.State.WAITING_REPLY -> ThemeRuntime.resolveText("ui_call_state_waiting")
            com.xingshi.ai.feature.chat.VoiceCallController.State.SYNTHESIZING -> ""
            com.xingshi.ai.feature.chat.VoiceCallController.State.PLAYING -> ThemeRuntime.resolveText("ui_call_state_playing")
            com.xingshi.ai.feature.chat.VoiceCallController.State.ENDED -> ThemeRuntime.resolveText("ui_call_state_ended")
        }
        Dialog(
            onDismissRequest = {
                com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
                showVoiceCall = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val voiceBody: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.88f)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            val avatar = companionData?.avatarUrl
                            if (avatar.isNullOrBlank()) {
                                Text(
                                    text = (companionData?.name ?: "").take(1),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = companionData?.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = companionData?.name.orEmpty(),
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = label,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "%02d:%02d".format(callElapsedMs / 1000 / 60, callElapsedMs / 1000 % 60),
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        androidx.compose.material3.FilledIconButton(
                            onClick = {
                                com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
                                showVoiceCall = false
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = ThemeRuntime.resolveText("ui_call_hangup"),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }
            ThemePageSlot(
                page = ThemePages.VOICE_CALL,
                host = {
                    ThemePageHost(
                        page = ThemePages.VOICE_CALL,
                        items = emptyList(),
                        state = mapOf(
                            "call_id" to (callActive?.callId ?: ""),
                            "call_mode" to (callActive?.mode ?: ""),
                            "call_state" to callState.name.lowercase(),
                            "companion_id" to companionId.toString(),
                            "companion_name" to companionData?.name.orEmpty(),
                            "companion_avatar" to companionData?.avatarUrl.orEmpty(),
                            "elapsed_ms" to callElapsedMs.toString()
                        ),
                        content = voiceBody,
                        dispatch = { action ->
                            if (action is ThemePageAction.Invoke && action.id == "hangup") {
                                com.xingshi.ai.feature.chat.VoiceCallCoordinator.hangup()
                                showVoiceCall = false
                            }
                        }
                    )
                },
                fallback = voiceBody
            )
        }
    }
}

@Composable
private fun ChatScreenContent(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {},
    onOpenPluginPage: (String) -> Unit = {},
    uiMessage: String? = null,
    onUiMessageShown: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    onVideoCall: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val draft by viewModel.draft.collectAsState()
    val draftAttachments by viewModel.draftAttachments.collectAsState()
    var showExtensionPanel by remember { mutableStateOf(false) }
    var showStickerPanel by remember { mutableStateOf(false) }
    val stickerManager = remember { StickerManager.getInstance(context) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            showExtensionPanel = false
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "image/*"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "image") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType)
                } else {
                    snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_01"))
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            showExtensionPanel = false
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "file") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType)
                } else {
                    snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_06"))
                }
            }
        }
    }

    var cameraPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            showExtensionPanel = false
            cameraPhotoUri?.let { photoUri ->
                scope.launch {
                    val mimeType = context.contentResolver.getType(photoUri) ?: "image/*"
                    val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, photoUri, "photo.jpg") }
                    if (copied != null) {
                        viewModel.addDraftAttachment(copied.first, copied.second, mimeType)
                    } else {
                        snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_01"))
                    }
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            val photoFile = java.io.File(context.applicationContext.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            photoFile.parentFile?.mkdirs()
            photoFile.createNewFile()
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.xingshi.fileprovider", photoFile
            )
            cameraPhotoUri = photoUri
            cameraLauncher.launch(photoUri)
        } else {
            scope.launch { snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_02")) }
        }
    }

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val voiceRetryAvailable by viewModel.voiceRetryAvailable.collectAsState()
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            pendingAudioAction?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_03")) }
        }
        pendingAudioAction = null
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            showExtensionPanel = false
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: "video/*"
                val copied = withContext(Dispatchers.IO) { copyUriToDraftCache(context, it, "video") }
                if (copied != null) {
                    viewModel.addDraftAttachment(copied.first, copied.second, mimeType, kind = DraftAttachmentKind.VIDEO)
                } else {
                    snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_04"))
                }
            }
        }
    }

    val stickerPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val path = copyUriToCache(context, it)
                    if (path != null) {
                        val count = stickerManager.importStickerZip(path)
                        snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_05", listOf(count)))
                    } else {
                        snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_06"))
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                }
            }
        }
    }

    val userAvatar by viewModel.userAvatar.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsState()
    val companionData by viewModel.companionData.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val typingText by viewModel.typingText.collectAsState()
    val isReasoning by viewModel.isReasoning.collectAsState()
    val reasoningText by viewModel.reasoningText.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val themeState = rememberThemeState()
    val appearanceContext = LocalContext.current
    val isDarkTheme = remember(themeState.revision) { ThemeRuntime.appearance(appearanceContext).isDark }
    val perfTier = remember { HardwareInfo.tier }
    val adaptiveSizing = rememberAdaptiveSizing()

    val settingsStore = remember { ChatDetailSettingsStore(context) }
    val detailSettings by settingsStore.settingsFlow(companionId).collectAsState(initial = com.xingshi.ai.feature.chat.data.CompanionChatDetailSettings())

    LaunchedEffect(Unit) {
        ReadStatusManager.markAsRead(context, companionId)
        viewModel.refreshCompanionData()
    }

    val backendNextAction by viewModel.backendNextAction.collectAsState()
    LaunchedEffect(uiMessage, voiceRetryAvailable, backendNextAction) {
        if (!uiMessage.isNullOrBlank()) {
            val attachmentRetry = backendNextAction == "ui_attach_retry_hint"
            val result = snackbarHostState.showSnackbar(
                message = if (attachmentRetry) {
                    uiMessage + "\n" + ThemeRuntime.resolveText("ui_attach_retry_hint")
                } else {
                    uiMessage
                },
                actionLabel = if (voiceRetryAvailable || attachmentRetry) ThemeRuntime.resolveText("ui_retry") else null,
                duration = SnackbarDuration.Long
            )
            onUiMessageShown()
            if (result == SnackbarResult.ActionPerformed) {
                if (voiceRetryAvailable) viewModel.retryVoiceRecognition()
                else if (attachmentRetry) viewModel.retryAttachmentPreparation()
            }
        }
    }

    var hasScrolledToBottom by remember { mutableStateOf(false) }

    val itemCount = messages.size +
        (if (isLoadingMore) 1 else 0) +
        (if (isTyping) 1 else 0) +
        (if (isReasoning && reasoningText.isNotBlank()) 1 else 0)

    val listState = remember { LazyListState() }

    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !hasScrolledToBottom) {
            hasScrolledToBottom = true
            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    val isAtBottom = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= totalItems - 2
        }
    }

    var wasAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom.value }.collect { nearBottom ->
            wasAtBottom = nearBottom
        }
    }

    val lastMessageId = messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastMessage = messages.lastOrNull()
        val isMyMessage = lastMessage?.isFromUser == true
        if (wasAtBottom || isMyMessage) {
            val currentItemCount = messages.size +
                (if (isLoadingMore) 1 else 0) +
                (if (isTyping) 1 else 0) +
                (if (isReasoning && reasoningText.isNotBlank()) 1 else 0)
            listState.scrollToItem((currentItemCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(typingText) {
        if (typingText.isNotBlank() && wasAtBottom) {
            listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(reasoningText) {
        if (reasoningText.isNotBlank() && wasAtBottom) {
            listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(WindowInsets.ime.getBottom(LocalDensity.current)) {
        if (itemCount > 0) listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
    }

    var prevMessageCount by remember { mutableStateOf(0) }
    var isLoadingMoreTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (!hasScrolledToBottom) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingMore && !isLoadingMoreTriggered) {
            isLoadingMoreTriggered = true
            prevMessageCount = messages.size
            viewModel.loadMoreHistory()
        }
    }

    LaunchedEffect(isLoadingMore, prevMessageCount) {
        if (!isLoadingMore && prevMessageCount > 0 && messages.size > prevMessageCount) {
            val addedCount = messages.size - prevMessageCount
            listState.scrollToItem(addedCount.coerceAtLeast(0), scrollOffset = 0)
            prevMessageCount = 0
            isLoadingMoreTriggered = false
        }
    }

    val defaultBackground = MaterialTheme.colorScheme.background
    var targetBgColor by remember { mutableStateOf(defaultBackground) }
    var chatBgGradient by remember { mutableStateOf<Brush?>(null) }
    var isCustomBg by remember { mutableStateOf(false) }
    var customBgKey by remember { mutableStateOf("") }
    val customBgPainter = if (isCustomBg && customBgKey.isNotEmpty()) {
        rememberBackgroundBitmap(customBgKey)
    } else null
    val globalBgKey = getChatBackgroundKey(context)
    LaunchedEffect(detailSettings, isDarkTheme, globalBgKey) {
        withContext(Dispatchers.IO) {
            val effectiveKey = if (detailSettings.useGlobalBackground || detailSettings.backgroundKey == null) {
                globalBgKey
            } else {
                detailSettings.backgroundKey ?: "default"
            }
            val (color, gradient) = if (isCustomBackground(effectiveKey)) {
                Color.Transparent to null
            } else {
                getChatBackgroundByKey(context, effectiveKey, isDarkTheme)
            }
            val custom = isCustomBackground(effectiveKey)
            withContext(Dispatchers.Main) {
                targetBgColor = color
                chatBgGradient = gradient
                isCustomBg = custom
                customBgKey = if (custom) effectiveKey else ""
            }
        }
    }

    val chatBgColor by animateColorAsState(targetBgColor, tween(300), label = "bgColor")
    val backgroundColor = if (isDarkTheme && !isCustomBg) {
        MaterialTheme.colorScheme.background
    } else if (isDarkTheme && isCustomBg) {
        MaterialTheme.colorScheme.background
    } else {
        chatBgColor
    }

    val glassIntensity = when (perfTier) {
        HardwareInfo.Tier.ULTRA -> 1.0f
        HardwareInfo.Tier.HIGH -> 0.85f
        HardwareInfo.Tier.MEDIUM -> 0.5f
        HardwareInfo.Tier.LOW -> 0.2f
    }

    var showVoiceRecorder by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var isCanceling by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceRecorder.getInstance(context) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            voiceRecorder.start()
            while (isRecording) {
                delay(1000)
                if (isRecording) recordingDuration++
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .zIndex(1f)
        )

        if (isCustomBg && customBgPainter != null) {
            Image(
                painter = customBgPainter,
                contentDescription = themeText("chat_chat_background"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .then(
                    if (isCustomBg) Modifier.background(Color.Transparent)
                    else if (!isDarkTheme && chatBgGradient != null) Modifier.background(chatBgGradient!!)
                    else Modifier.background(backgroundColor)
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(rememberHorizontalSwipeGuard())
                    .pointerInput(Unit) { detectTapGestures { keyboardController?.hide() } },
                contentPadding = PaddingValues(
                    start = adaptiveSizing.listHorizontalPadding, end = adaptiveSizing.listHorizontalPadding,
                    top = 120.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isLoadingMore) {
                    item(key = "load_more_indicator") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    themeText("ui2_chat_screen_01"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(
                        message = message,
                        companionData = companionData,
                        isUser = message.isFromUser,
                        userAvatar = userAvatar,
                        userName = userName,
                        onVoiceClick = {},
                        onRecall = { msg -> viewModel.recallMessage(msg) },
                        onResynthesizeVoice = { msg -> viewModel.retryReplyVoiceSynthesis(msg) },
                        adaptiveSizing = adaptiveSizing,
                        isDarkTheme = isDarkTheme
                    )
                }

                if (isTyping) {
                    item(key = "typing_indicator") {
                        TypingIndicatorBubble(
                            companionData = companionData,
                            typingText = typingText,
                            adaptiveSizing = adaptiveSizing,
                            isDarkTheme = isDarkTheme
                        )
                    }
                }

                if (isReasoning && reasoningText.isNotBlank()) {
                    item(key = "reasoning_indicator") {
                        ReasoningBubble(
                            reasoningText = reasoningText,
                            adaptiveSizing = adaptiveSizing
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                StickerPanel(
                    isVisible = showStickerPanel,
                    onStickerClick = { sticker ->
                        viewModel.sendSticker(sticker)
                        showStickerPanel = false
                    },
                    onImportClick = {
                        stickerPickerLauncher.launch("application/zip")
                    },
                    onDeleteAllClick = {
                        scope.launch {
                            val manager = StickerManager.getInstance(context)
                            val success = manager.deleteAllImportedStickers()
                            if (success) {
                                snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_07"))
                            } else {
                                snackbarHostState.showSnackbar(ThemeRuntime.resolveText("ui_chat_screen_08"))
                            }
                        }
                    }
                )

                if (showExtensionPanel) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(
                            Triple(Icons.Filled.PhotoLibrary, themeText("ui2_chat_screen_02")) {
                                showStickerPanel = false
                                imagePickerLauncher.launch("image/*")
                            },
                            Triple(Icons.Filled.CameraAlt, themeText("ui2_chat_screen_03")) {
                                showStickerPanel = false
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            },
                            Triple(Icons.Filled.Description, themeText("ui_attach_file")) {
                                showStickerPanel = false
                                filePickerLauncher.launch(DraftFileMimeTypes)
                            },
                            Triple(Icons.Filled.Videocam, themeText("ui_chat_video_message")) {
                                showExtensionPanel = false
                                videoPickerLauncher.launch("video/*")
                            },
                            Triple(Icons.Filled.Call, themeText("ui_chat_voice_call")) {
                                showExtensionPanel = false
                                onVoiceCall()
                            },
                            Triple(Icons.Filled.Videocam, themeText("ui_chat_video_call")) {
                                showExtensionPanel = false
                                onVideoCall()
                            },
                            Triple(Icons.Filled.EmojiEmotions, themeText("ui2_chat_screen_05")) {
                                showExtensionPanel = false
                                showStickerPanel = !showStickerPanel
                            }
                        ).forEach { (icon, label, action) ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { action() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        PluginInputActions(
                            companionId = companionId,
                            input = draft,
                            onOpenPlugin = { pluginId ->
                                showExtensionPanel = false
                                onOpenPluginPage(pluginId)
                            }
                        )
                    }
                }

                PluginFailureToast(companionId = companionId)
                PluginPendingBanner(companionId = companionId)

                val isBlocked = detailSettings.blocked
                if (isBlocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = themeText("ui_chat_screen_09"),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    DraftAttachmentStrip(
                        attachments = draftAttachments,
                        onRemove = viewModel::removeDraftAttachment
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        WeChatChatInputBar(
                            onSendMessage = { msg ->
                                viewModel.sendMessage(msg)
                            },
                            draft = draft,
                            onDraftChange = viewModel::onDraftChange,
                            isLoading = false,
                            hasAttachments = draftAttachments.isNotEmpty(),
                            onPlusClick = {
                                showExtensionPanel = !showExtensionPanel
                                showStickerPanel = false
                            },
                            onVoiceRecordStart = {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    showExtensionPanel = false
                                    isCanceling = false
                                    isRecording = true
                                    showVoiceRecorder = true
                                } else {
                                    pendingAudioAction = {
                                        showExtensionPanel = false
                                        isCanceling = false
                                        isRecording = true
                                        showVoiceRecorder = true
                                    }
                                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onVoiceRecordStop = {
                                val audioPath = voiceRecorder.stop()
                                isRecording = false
                                showVoiceRecorder = false
                                if (audioPath != null && recordingDuration >= 1) {
                                    viewModel.sendVoiceMessage(audioPath, recordingDuration)
                                }
                            },
                            onVoiceRecordCancel = {
                                voiceRecorder.cancel()
                                isRecording = false
                                showVoiceRecorder = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (showVoiceRecorder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = themeText("ui_chat_screen_10"),
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${recordingDuration}s",
                        fontSize = 48.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = themeText("ui_chat_screen_11"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    if (!isRecording) return@clickable
                                    val audioPath = voiceRecorder.stop()
                                    isRecording = false
                                    showVoiceRecorder = false
                                    val measuredMs: Long = voiceRecorder.getDurationMs()
                                        .takeIf { it > 0 }?.toLong()
                                        ?: (recordingDuration * 1000L)
                                    val actualSeconds = ((measuredMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                                    if (audioPath != null) {
                                        viewModel.sendVoiceMessage(audioPath, actualSeconds)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = themeText("ui_chat_screen_12"),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 14.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .clickable {
                                    voiceRecorder.cancel()
                                    isRecording = false
                                    showVoiceRecorder = false
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = themeText("ui_chat_screen_13"),
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = 48.dp,
                    start = 16.dp, end = 16.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = themeText("chat_send"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompanionAvatar(
                            avatarUrl = companionData?.avatarUrl,
                            name = companionData?.name,
                            size = 24.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = companionData?.name ?: "",
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (isDarkTheme) 0.2f else 0.12f
                                    )
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = themeText("ui_chat_screen_14"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { onNavigateToDetail(companionId) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = themeText("ui_chat_screen_15"),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

            }
        }
    }
}
