@file:OptIn(ExperimentalMaterial3Api::class)

package com.xingshi.ai.feature.settings.ui.screen

import androidx.compose.ui.platform.LocalContext
import com.xingshi.ai.theme.framework.rememberThemeState
import com.xingshi.ai.theme.framework.ThemeRuntime
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.feature.settings.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.foundation.isSystemInDarkTheme
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.domain.MainChatConfigurationIssue
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val configs by viewModel.configs.collectAsState(initial = emptyList())
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    
    val mainModelCloud by viewModel.mainModelCloud.collectAsState()
    val mainModelNotice by viewModel.mainModelNotice.collectAsState()
    val sttAvailable by viewModel.sttAvailable.collectAsState()
    val ttsAvailable by viewModel.ttsAvailable.collectAsState()
    val context = LocalContext.current
    val themeState = rememberThemeState()
    val isDarkTheme = remember(themeState.revision) { ThemeRuntime.appearance(context).isDark }
    val scheme = MaterialTheme.colorScheme
    var editingMain by remember { mutableStateOf<ApiConfig?>(null) }
    var editingVision by remember { mutableStateOf<ApiConfig?>(null) }
    var editingStt by remember { mutableStateOf<ApiConfig?>(null) }
    var newConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showTtsSettings by remember { mutableStateOf(false) }
    
    var uiMessage by remember { mutableStateOf<String?>(null) }
    val mainIssueTexts by rememberUpdatedState(MainChatConfigurationIssue.entries.associateWith {
        themeText("ui_main_configuration_${it.name.lowercase()}")
    })
    LaunchedEffect(viewModel) {
        viewModel.saveResult.collect { result ->
            uiMessage = when (result) {
                is SettingsViewModel.SaveResult.Success -> result.message
                is SettingsViewModel.SaveResult.Error -> result.mainIssue?.let { mainIssueTexts[it] } ?: result.message
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ThemePageSlot(
            page = ThemePages.SETTINGS,
            host = {
                val visionConfig = configs.firstOrNull { it.kind == "VISUAL" }
                val sttConfig = configs.firstOrNull { it.kind == "STT" }
                val ttsConfig = configs.firstOrNull { it.kind == "TTS" }
                ThemePageHost(
                    page = ThemePages.SETTINGS,
                    items = configs.map { config ->
                        ThemePageItem(
                            id = config.id.toString(),
                            kind = when (config.kind) {
                                "VISUAL" -> "api_vision_config"
                                "STT" -> "api_stt_config"
                                "TTS" -> "api_tts_config"
                                else -> "api_config"
                            },
                            title = config.name.ifBlank { config.provider.displayName },
                            subtitle = themeText("api_config_model_fmt", config.model)
                        )
                    },
                    state = configs.associate {
                        val status = connectionStatus[viewModel.connectionKey(it)]
                        "connection_status_${it.id}" to (status?.status?.name ?: "UNKNOWN")
                    } + mapOf(
                        "config_count" to configs.size.toString(),
                        "main_count" to configs.count {
                            it.kind != "VISUAL" && it.kind != "STT" && it.kind != "TTS"
                        }.toString(),
                        "active_config_id" to mainModelCloud.entries.firstOrNull { it.value.isDefault }?.key?.toString().orEmpty(),
                        
                        "main_model_notice" to (mainModelNotice ?: ""),
                        "vision_configured" to (visionConfig != null).toString(),
                        "vision_config_id" to visionConfig?.id?.toString().orEmpty(),
                        "stt_available" to sttAvailable.toString(),
                        "stt_configured" to (sttConfig != null).toString(),
                        "stt_config_id" to sttConfig?.id?.toString().orEmpty(),
                        "tts_available" to ttsAvailable.toString(),
                        "tts_configured" to (ttsConfig != null).toString(),
                        "tts_config_id" to ttsConfig?.id?.toString().orEmpty(),
                        "aux_capabilities" to "vision,stt,tts",
                        "ui_message" to (uiMessage ?: "")
                    ) + configs.filter { it.kind == "MAIN" }.associate {
                        "main_model_state_${it.id}" to (mainModelCloud[it.id]?.state?.name ?: "DRAFT")
                    },
                    dispatch = { action ->
                        when (action) {
                            is ThemePageAction.Invoke -> when (action.id) {
                                "refresh_status" -> {
                                    viewModel.refreshConnectionStatus()
                                    viewModel.refreshMainConfigurations()
                                }
                                "add_config" -> showProviderPicker = true
                                "add_vision_config" -> {
                                    editingVision = visionConfig ?: ApiConfig(
                                        provider = ApiProvider.CUSTOM,
                                        apiKey = "",
                                        baseUrl = "",
                                        model = "",
                                        kind = "VISUAL"
                                    )
                                }
                                "edit_config" -> {
                                    val id = action.payload["id"]?.toLongOrNull()
                                    editingMain = configs.firstOrNull { it.id == id }
                                }
                                "edit_vision_config" -> {
                                    val id = action.payload["id"]?.toLongOrNull()
                                    editingVision = configs.firstOrNull { it.id == id && it.kind == "VISUAL" }
                                        ?: visionConfig
                                }
                                "add_stt_config" -> {
                                    val preset = viewModel.sttPreset()
                                    editingStt = sttConfig ?: ApiConfig(
                                        provider = ApiProvider.CUSTOM,
                                        apiKey = "",
                                        baseUrl = preset?.endpoint ?: "",
                                        model = preset?.defaultModel ?: "",
                                        kind = "STT",
                                        name = "语音识别"
                                    )
                                }
                                "edit_stt_config" -> {
                                    val id = action.payload["id"]?.toLongOrNull()
                                    editingStt = configs.firstOrNull { it.id == id && it.kind == "STT" } ?: sttConfig
                                }
                                "delete_stt_config" -> action.payload["id"]?.toLongOrNull()?.let { id ->
                                    configs.firstOrNull { it.id == id && it.kind == "STT" }?.let(viewModel::deleteSttConfig)
                                }
                                "open_tts_settings" -> showTtsSettings = true
                                "select_config" -> action.payload["id"]?.toLongOrNull()?.let { id ->
                                    configs.firstOrNull { it.id == id }?.let(viewModel::selectActiveConfig)
                                }
                                "toggle_config" -> action.payload["id"]?.toLongOrNull()?.let { id ->
                                    configs.firstOrNull { it.id == id }?.let(viewModel::toggleConfigEnabled)
                                }
                                "delete_config" -> action.payload["id"]?.toLongOrNull()?.let { id ->
                                    configs.firstOrNull { it.id == id }?.let(viewModel::deleteConfig)
                                }
                                "dismiss_message" -> uiMessage = null
                                "back" -> onNavigateBack()
                            }
                            is ThemePageAction.Open -> when (action.route) {
                                "back" -> onNavigateBack()
                            }
                        }
                    }
                )
            },
            fallback = {
                SettingsScreenContent(
                    onNavigateBack = onNavigateBack,
                    viewModel = viewModel,
                    uiMessage = uiMessage,
                    onUiMessageShown = { uiMessage = null },
                    sttAvailable = sttAvailable,
                    ttsAvailable = ttsAvailable
                )
            }
        )

        
        newConfig?.let { draft ->
            ApiConfigEditDialog(
                config = draft,
                onDismiss = { newConfig = null },
                onSave = { saved: ApiConfig -> viewModel.saveConfig(saved); newConfig = null },
                isDarkTheme = isDarkTheme,
                textPrimaryColor = scheme.onSurface,
                textSecondaryColor = scheme.onSurfaceVariant
            )
        }
        editingMain?.let { draft ->
            ApiConfigEditDialog(
                config = draft,
                onDismiss = { editingMain = null },
                onSave = { saved: ApiConfig -> viewModel.saveConfig(saved); editingMain = null },
                isDarkTheme = isDarkTheme,
                textPrimaryColor = scheme.onSurface,
                textSecondaryColor = scheme.onSurfaceVariant
            )
        }
        editingVision?.let { draft ->
            PetalApiConfigEditDialog(
                config = draft,
                onDismiss = { editingVision = null },
                onSave = { saved: ApiConfig -> viewModel.saveVisionConfig(saved); editingVision = null },
                isDarkTheme = isDarkTheme,
                textPrimaryColor = scheme.onSurface,
                textSecondaryColor = scheme.onSurfaceVariant
            )
        }
        editingStt?.let { draft ->
            PetalApiConfigEditDialog(
                config = draft,
                onDismiss = { editingStt = null },
                onSave = { saved: ApiConfig -> viewModel.saveSttConfig(saved); editingStt = null },
                isDarkTheme = isDarkTheme,
                textPrimaryColor = scheme.onSurface,
                textSecondaryColor = scheme.onSurfaceVariant
            )
        }
        if (showProviderPicker) {
            PetalApiProviderPickerDialog(
                onDismiss = { showProviderPicker = false },
                onPick = { provider: ApiProvider ->
                    showProviderPicker = false
                    newConfig = ApiConfig(
                        provider = provider,
                        apiKey = "",
                        baseUrl = provider.defaultBaseUrl,
                        model = provider.defaultModel
                    )
                },
                textPrimaryColor = scheme.onSurface,
                textSecondaryColor = scheme.onSurfaceVariant
            )
        }
        if (showTtsSettings) {
            TtsSettingsScreen(onBack = { showTtsSettings = false })
        }
    }
}

@Composable
private fun AuxCapabilityRow(
    title: String,
    summary: String,
    configured: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = textPrimaryColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = summary,
                color = if (configured) textSecondaryColor else MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = textSecondaryColor
        )
    }
}

@Composable
private fun SettingsScreenContent(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    uiMessage: String? = null,
    onUiMessageShown: () -> Unit = {},
    sttAvailable: Boolean = false,
    ttsAvailable: Boolean = false
) {
    val configs by viewModel.configs.collectAsState(initial = emptyList())
    
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val mainModelCloud by viewModel.mainModelCloud.collectAsState()
    val mainModelNotice by viewModel.mainModelNotice.collectAsState()
    var expandedProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var newConfigDialog by remember { mutableStateOf<ApiConfig?>(null) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showTtsSettings by remember { mutableStateOf(false) }
    val themeState = rememberThemeState()
    val appearanceContext = LocalContext.current
    val isDarkTheme = remember(themeState.revision) { ThemeRuntime.appearance(appearanceContext).isDark }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiMessage) {
        if (!uiMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(uiMessage)
            onUiMessageShown()
        }
    }

    if (showTtsSettings) {
        TtsSettingsScreen(onBack = { showTtsSettings = false })
        return
    }
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val textTertiaryColor = colorScheme.outlineVariant
    val dividerColor = colorScheme.outline
    val cardBackground = colorScheme.surfaceVariant

    LaunchedEffect(Unit) {
        viewModel.refreshConnectionStatus()
        
        viewModel.refreshMainConfigurations()
        delay(100)
        isVisible = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigateBack() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = themeText("cd_back"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = themeText("ui_settings_screen_01"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
            ) {
                Text(
                    text = themeText("settings_hint"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = textTertiaryColor,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            
            ApiCardsSection(
                isVisible = isVisible,
                configs = configs,
                viewModel = viewModel,
                expandedProvider = expandedProvider,
                onExpandedProviderChange = { expandedProvider = it },
                isDarkTheme = isDarkTheme,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                showProviderPicker = showProviderPicker,
                onShowProviderPickerChange = { showProviderPicker = it },
                
                connectionStatus = connectionStatus,
                mainModelCloud = mainModelCloud,
                mainModelNotice = mainModelNotice,
                sttAvailable = sttAvailable,
                ttsAvailable = ttsAvailable,
                onOpenTtsSettings = { showTtsSettings = true }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    
    newConfigDialog?.let { newConfig ->
        ApiConfigEditDialog(
            config = newConfig,
            onDismiss = { newConfigDialog = null },
            onSave = { config: ApiConfig ->
                viewModel.saveConfig(config)
                newConfigDialog = null
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }

    
    if (showProviderPicker) {
        PetalApiProviderPickerDialog(
            onDismiss = { showProviderPicker = false },
            onPick = { provider ->
                showProviderPicker = false
                newConfigDialog = ApiConfig(
                    provider = provider,
                    apiKey = "",
                    baseUrl = provider.defaultBaseUrl,
                    model = provider.defaultModel
                )
            },
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }
}



@Composable
private fun ApiCardsSection(
    isVisible: Boolean,
    configs: List<ApiConfig>,
    viewModel: SettingsViewModel,
    expandedProvider: ApiProvider?,
    onExpandedProviderChange: (ApiProvider?) -> Unit,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    showProviderPicker: Boolean,
    onShowProviderPickerChange: (Boolean) -> Unit,
    
    connectionStatus: Map<String, SettingsViewModel.ConnectionResult>,
    mainModelCloud: Map<Long, SettingsViewModel.MainModelCloudState>,
    mainModelNotice: String?,
    sttAvailable: Boolean = false,
    ttsAvailable: Boolean = false,
    onOpenTtsSettings: () -> Unit = {}
) {
    var visionPicker by remember { mutableStateOf(false) }
    var visionDraft by remember { mutableStateOf<ApiConfig?>(null) }
    var sttDraft by remember { mutableStateOf<ApiConfig?>(null) }
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = 150)) +
                slideInVertically(tween(400, delayMillis = 150)) { it / 4 }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val mainConfigs = configs.filter {
                it.kind != "VISUAL" && it.kind != "STT" && it.kind != "TTS"
            }
            val visionConfig = configs.firstOrNull { it.kind == "VISUAL" }
            val sttConfig = configs.firstOrNull { it.kind == "STT" }
            val ttsConfig = configs.firstOrNull { it.kind == "TTS" }

            Text(
                text = themeText("ui_settings_screen_02"),
                color = textSecondaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (mainModelNotice != null) {
                Text(
                    text = mainModelNotice,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            mainConfigs.forEach { config ->
                val result = connectionStatus[viewModel.connectionKey(config)]
                    ?: SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN)
                val isExpanded = expandedProvider == config.provider
                
                val cloudState = mainModelCloud[config.id]
                
                val credentialUnavailable = cloudState != null && !cloudState.keyConfigured
                val isUnavailable = cloudState?.state == SettingsViewModel.MainModelState.UNAVAILABLE || credentialUnavailable
                val statusNote = when {
                    cloudState == null -> "尚未发布到云端，保存后生效；本机只保留草稿"
                    cloudState.state == SettingsViewModel.MainModelState.PENDING -> "上次发布结果未知，请用原内容重新保存以确认"
                    isUnavailable -> "云端凭据不可用，请重新填写 API Key"
                    else -> null
                }

                PetalSavedApiCard(
                    config = config,
                    connectionResult = result,
                    isExpanded = isExpanded,
                    isActive = cloudState?.isDefault == true,
                    isEnabled = cloudState?.state == SettingsViewModel.MainModelState.ACTIVE,
                    isDraft = cloudState == null,
                    isPending = cloudState?.state == SettingsViewModel.MainModelState.PENDING,
                    isUnavailable = isUnavailable,
                    statusNote = statusNote,
                    onExpandToggle = {
                        onExpandedProviderChange(if (isExpanded) null else config.provider)
                    },
                    onEdit = { it: ApiConfig -> viewModel.saveConfig(it) },
                    onDelete = { viewModel.deleteConfig(config) },
                    onToggleEnabled = { viewModel.toggleConfigEnabled(config) },
                    onSelectActive = { viewModel.selectActiveConfig(config) },
                    isDarkTheme = isDarkTheme,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            PetalAddApiButton(
                onClick = {
                    onShowProviderPickerChange(true)
                },
                isDarkTheme = isDarkTheme
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = themeText("ui_settings_screen_03"),
                color = textSecondaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            AuxCapabilityRow(
                title = themeText("ui_settings_aux_vision"),
                summary = visionConfig?.let { it.model.ifBlank { it.provider.displayName } }
                    ?: themeText("ui_settings_aux_missing"),
                configured = visionConfig != null,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                onClick = {
                    if (visionConfig == null) visionPicker = true
                    else visionDraft = visionConfig
                }
            )
            if (sttAvailable) {
                AuxCapabilityRow(
                    title = themeText("ui_settings_stt_title"),
                    summary = sttConfig?.let { it.model.ifBlank { it.provider.displayName } }
                        ?: themeText("ui_settings_aux_missing"),
                    configured = sttConfig != null,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor,
                    onClick = {
                        sttDraft = sttConfig ?: ApiConfig(
                            provider = ApiProvider.CUSTOM,
                            apiKey = "",
                            baseUrl = viewModel.sttPreset()?.endpoint ?: "",
                            model = viewModel.sttPreset()?.defaultModel ?: "",
                            kind = "STT",
                            name = "语音识别"
                        )
                    }
                )
            }
            if (ttsAvailable) {
                AuxCapabilityRow(
                    title = themeText("ui_settings_tts_title"),
                    summary = ttsConfig?.let { it.provider.displayName }
                        ?: themeText("ui_settings_aux_missing"),
                    configured = ttsConfig != null,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor,
                    onClick = onOpenTtsSettings
                )
            }
        }
    }
    sttDraft?.let { draft ->
        PetalApiConfigEditDialog(
            config = draft,
            onDismiss = { sttDraft = null },
            onSave = { saved: ApiConfig ->
                viewModel.saveSttConfig(saved)
                sttDraft = null
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }
    if (visionPicker) {
        PetalApiProviderPickerDialog(
            onDismiss = { visionPicker = false },
            onPick = { provider ->
                visionPicker = false
                visionDraft = ApiConfig(
                    provider = provider,
                    name = ThemeRuntime.resolveText("ui_settings_screen_06"),
                    apiKey = "",
                    baseUrl = provider.defaultBaseUrl,
                    model = provider.defaultModel,
                    kind = "VISUAL"
                )
            },
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }

    visionDraft?.let { draft ->
        PetalApiConfigEditDialog(
            config = draft,
            onDismiss = { visionDraft = null },
            onSave = { saved: ApiConfig ->
                viewModel.saveVisionConfig(saved)
                visionDraft = null
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }
}



@Composable
fun ApiConfigEditDialog(
    config: ApiConfig,
    onDismiss: () -> Unit,
    onSave: (ApiConfig) -> Unit,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val cardBackground = MaterialTheme.colorScheme.surfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outline
    val textTertiary = MaterialTheme.colorScheme.outlineVariant

    var apiKey by remember { mutableStateOf(config.apiKey) }
    var extraApiKeys by remember { mutableStateOf(config.extraApiKeys) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var temperature by remember { mutableFloatStateOf(config.temperature) }
    var maxTokens by remember { mutableStateOf(config.maxTokens?.toString() ?: "") }
    var formatHint by remember { mutableStateOf(config.formatHint) }

    val isPartner = config.provider == ApiProvider.PARTNER
    val isCustom = config.provider == ApiProvider.CUSTOM
    val isValid = when (config.provider) {
        ApiProvider.PARTNER -> true
        ApiProvider.CUSTOM -> baseUrl.isNotBlank() && model.isNotBlank()
        else -> model.isNotBlank() && (apiKey.isNotBlank() || config.id > 0L)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBackground,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = themeText("api_config_dialog_title_fmt", config.provider.displayName),
                color = textPrimaryColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(themeText("ui_settings_screen_07"), color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = extraApiKeys,
                    onValueChange = { extraApiKeys = it },
                    label = { Text(themeText("ui_settings_screen_08"), color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    placeholder = {
                        Text("sk-xxx,sk-xxx,...", color = textTertiary, fontSize = 12.sp)
                    }
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                Text(
                    text = "Temperature: ${String.format("%.1f", temperature)}",
                    color = textPrimaryColor,
                    fontSize = 14.sp
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                    label = { Text("Max Tokens", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true
                )

                
                if (isCustom) {
                    var showFormatDropdown by remember { mutableStateOf(false) }
                    val formatOptions = mapOf(
                        "openai" to themeText("ui2_settings_screen_01"),
                        "anthropic" to themeText("ui2_settings_screen_02")
                    )
                    val selectedFormatText = formatOptions[formatHint] ?: themeText("ui2_settings_screen_03")

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedFormatText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(themeText("ui_settings_screen_09"), color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFormatDropdown = !showFormatDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = cardBackground,
                                unfocusedContainerColor = cardBackground,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = {
                                Icon(
                                    imageVector = if (showFormatDropdown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = themeText("ui_settings_screen_10"),
                                    modifier = Modifier.clickable { showFormatDropdown = !showFormatDropdown },
                                    tint = textSecondaryColor
                                )
                            },
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = showFormatDropdown,
                            onDismissRequest = { showFormatDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            formatOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = textPrimaryColor, fontSize = 14.sp) },
                                    onClick = {
                                        formatHint = key
                                        showFormatDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSave(
                            config.copy(
                                apiKey = apiKey.trim(),
                                extraApiKeys = extraApiKeys.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                temperature = temperature,
                                maxTokens = maxTokens.toIntOrNull(),
                                formatHint = formatHint
                            )
                        )
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(themeText("ui_settings_screen_13"))
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = textSecondaryColor
                )
            ) {
                Text(themeText("ui_settings_screen_14"))
            }
        }
    )
}

