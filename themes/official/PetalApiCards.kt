package com.xingshi.ai.feature.settings.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.xingshi.ai.feature.settings.mainConfigurationIssue
import com.xingshi.ai.domain.MainChatConfigurationIssue
import com.xingshi.ai.domain.mainChatProtocol
import com.xingshi.ai.theme.framework.themeText

// ═══ 自定义中继 error code mapping ═══
private fun mapErrorCode(code: String): String = when (code) {
    "upstream_unreachable" -> "上游不通"
    "account_blocked" -> "已冻结"
    "key_disabled" -> "密钥已禁用"
    "network_error" -> "网络不通"
    "timeout" -> "超时"
    else -> "失败"
}

// ============================================================
// PetalStatChip - 状态标签小组件
// ============================================================

@Composable
fun PetalStatChip(icon: String, text: String, color: Color, isDarkTheme: Boolean) {
    val bgColor = if (isDarkTheme) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                color = color,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============================================================
// ApiConfigEditDialog - 内联 API 配置编辑弹窗
// ============================================================

@Composable
fun PetalApiConfigEditDialog(
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
    val isMain = config.kind == "MAIN"
    val protocol = mainChatProtocol(config.provider.name, formatHint)
    val maxTokensRequired = isMain && protocol == "ANTHROPIC_MESSAGES"
    val mainIssue = if (!isMain) null else if (maxTokens.isNotBlank() && maxTokens.toIntOrNull() == null)
        MainChatConfigurationIssue.INVALID_MAX_TOKENS
        else config.copy(apiKey = apiKey, baseUrl = baseUrl, model = model, temperature = temperature,
            maxTokens = maxTokens.toIntOrNull(), formatHint = formatHint).mainConfigurationIssue()
    val isValid = if (isMain) mainIssue == null else when (config.provider) {
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
                text = themeText("api_config_dialog_title_fmt", config.name.ifBlank { config.provider.displayName }),
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
                    label = { Text(themeText(if (isMain && protocol == "IFLYTEK_HTTP") "ui_main_spark_password" else "ui_petal_api_cards_01"), color = textSecondaryColor) },
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
                    label = { Text(themeText("ui_petal_api_cards_02"), color = textSecondaryColor) },
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

                if (isMain && protocol == "IFLYTEK_HTTP") {
                    Text(themeText("ui_main_spark_password_hint"), color = textSecondaryColor, fontSize = 12.sp)
                }
                // Native Messages accepts temperature in 0..1; other supported formats keep 0..2.
                Text(
                    text = "Temperature: ${String.format("%.1f", temperature)}",
                    color = textPrimaryColor,
                    fontSize = 14.sp
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..(if (maxTokensRequired) 1f else 2f),
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                // 辅助模型不消费通用输出上限：只在主模型配置里保留
                if (isMain) {
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                        label = { Text(if (maxTokensRequired) themeText("ui_main_max_tokens_label") else "Max Tokens", color = textSecondaryColor) },
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
                }

                // API 格式选择 — 只对主模型的自定义 API 显示；辅助模型沿用各自能力协议
                if (isCustom && isMain) {
                    var showFormatDropdown by remember { mutableStateOf(false) }
                    val formatOptions = mapOf(
                        "openai" to themeText("ui2_petal_api_cards_01"),
                        "anthropic" to themeText(if (isMain) "ui_main_anthropic_format" else "ui2_petal_api_cards_02"),
                        "iflytek" to themeText("ui_main_spark_format")
                    )
                    val selectedFormatText = formatOptions[formatHint] ?: themeText("ui2_petal_api_cards_03")

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedFormatText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(themeText("ui_petal_api_cards_03"), color = textSecondaryColor) },
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
                                    contentDescription = themeText("ui_petal_api_cards_04"),
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

                if (mainIssue != null) {
                    Text(themeText("ui_main_configuration_${mainIssue.name.lowercase()}"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
                                maxTokens = if (isMain) maxTokens.toIntOrNull() else config.maxTokens,
                                formatHint = if (isMain) formatHint else config.formatHint
                            )
                        )
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(themeText("ui_petal_api_cards_07"))
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
                Text(themeText("ui_petal_api_cards_08"))
            }
        }
    )
}

// ============================================================
// PetalSavedApiCard - 已保存的其他 API 配置卡片
// ============================================================

@Composable
fun PetalSavedApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    /** 主模型以云端启停结果为准；辅助配置沿用本机启用位。 */
    isEnabled: Boolean = true,
    /** 未发布到云端的本地草稿：可丢弃、不可启用/设为默认。 */
    isDraft: Boolean = false,
    /** 发布结果未知、等待幂等确认：只能重试保存，不能改启停/默认。 */
    isPending: Boolean = false,
    /** 云端凭据不可用：需要重新填写 Key 后保存，不能直接启停。 */
    isUnavailable: Boolean = false,
    /** 主模型卡片需要展示的云端原因（凭据不可用、发布未知等）。 */
    statusNote: String? = null,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
    onSelectActive: () -> Unit,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    showActiveAction: Boolean = true
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.provider.displayName,
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty()) {
                        Text(
                            text = config.name,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Active indicator
                    if (isActive) {
                        PetalStatChip(
                            icon = "\u2713",
                            text = themeText("ui_petal_api_cards_09"),
                            color = MaterialTheme.colorScheme.primary,
                            isDarkTheme = isDarkTheme
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (showActiveAction) {
                        // 主模型不展示本机连接测试结果，只展示云端确认的启停状态。
                        val cloudStatusText = when {
                            isDraft -> "未发布"
                            isPending -> "待确认"
                            isUnavailable -> "不可用"
                            !isEnabled -> "已停用"
                            else -> null
                        }
                        if (cloudStatusText != null) {
                            val cloudStatusColor = when {
                                isDraft -> textSecondaryColor
                                isPending -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                            PetalStatChip(
                                icon = "\u25CF",
                                text = cloudStatusText,
                                color = cloudStatusColor,
                                isDarkTheme = isDarkTheme
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    } else {
                        // Connection status indicator
                        val statusColor = when (connectionResult.status) {
                            SettingsViewModel.ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            SettingsViewModel.ConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
                            SettingsViewModel.ConnectionStatus.TESTING -> MaterialTheme.colorScheme.tertiary
                            SettingsViewModel.ConnectionStatus.HISTORICAL -> MaterialTheme.colorScheme.tertiary
                            SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                        }
                        val statusText = when (connectionResult.status) {
                            SettingsViewModel.ConnectionStatus.CONNECTED ->
                                if (connectionResult.latencyMs > 0) "已连接 ${connectionResult.latencyMs}ms" else themeText("ui2_petal_api_cards_04")
                            SettingsViewModel.ConnectionStatus.HISTORICAL -> "历史测试，未验证"
                            SettingsViewModel.ConnectionStatus.FAILED -> {
                                val err = connectionResult.errorCode
                                if (err != null) mapErrorCode(err) else themeText("ui2_petal_api_cards_05")
                            }
                            SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                            SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                        }
                        PetalStatChip(
                            icon = "\u25CF",
                            text = statusText,
                            color = statusColor,
                            isDarkTheme = isDarkTheme
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) themeText("ui_petal_api_cards_10") else themeText("ui_petal_api_cards_11"),
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Model info
                if (config.model.isNotEmpty()) {
                    Text(
                        text = themeText("api_config_model_line_fmt", config.model),
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Base URL
                if (config.baseUrl.isNotEmpty()) {
                    Text(
                        text = themeText("api_config_base_url_line_fmt", config.baseUrl),
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (statusNote != null) {
                    Text(
                        text = statusNote,
                        color = textSecondaryColor,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 云端已发布的主模型配置不提供本地删除，只能停用；草稿才可丢弃。
                    if (isDraft || !showActiveAction) {
                        TextButton(onClick = onDelete) {
                            Text(themeText("ui_petal_api_cards_12"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    TextButton(onClick = {
                        editingConfig = config
                    }) {
                        Text(themeText("ui_petal_api_cards_13"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                    if (showActiveAction && !isDraft && !isPending && !isUnavailable) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = onToggleEnabled) {
                            Text(if (isEnabled) "停用" else "启用", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                        if (isEnabled && !isActive) {
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(onClick = onSelectActive) {
                                Text(themeText("ui_petal_api_cards_14"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑弹窗 - INLINE inside PetalSavedApiCard
    editingConfig?.let { editing ->
        PetalApiConfigEditDialog(
            config = editing,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }
}

// ============================================================
// PetalAddApiButton - 添加 API 配置按钮
// ============================================================

@Composable
fun PetalAddApiButton(onClick: () -> Unit, isDarkTheme: Boolean, label: String = themeText("ui_petal_api_cards_15")) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp
        )
    }
}

// ============================================================
// PetalApiProviderPickerDialog - 选择 API 提供商（预设 + 自定义）
// ============================================================

@Composable
fun PetalApiProviderPickerDialog(
    onDismiss: () -> Unit,
    onPick: (ApiProvider) -> Unit,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val presetProviders = listOf(
        ApiProvider.OPENAI,
        ApiProvider.DEEPSEEK,
        ApiProvider.DASHSCOPE,
        ApiProvider.KIMI,
        ApiProvider.ZHIPU,
        ApiProvider.SILICONFLOW,
        ApiProvider.OPENROUTER,
        ApiProvider.GROQ,
        ApiProvider.GEMINI,
        ApiProvider.ANTHROPIC,
        ApiProvider.XIAOMI,
        ApiProvider.IFLYTEK,
        ApiProvider.CUSTOM
    )
    val cardBackground = MaterialTheme.colorScheme.surfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outline

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBackground,
        title = {
            Text(
                text = themeText("ui_petal_api_cards_16"),
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetProviders.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(dividerColor.copy(alpha = 0.3f))
                            .clickable { onPick(provider) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ProviderLogo(
                            provider = provider,
                            size = 36.dp,
                            cornerRadius = 10.dp
                        )
                        Column {
                            Text(
                                text = provider.displayName,
                                color = textPrimaryColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = provider.defaultBaseUrl,
                                color = textSecondaryColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(themeText("ui_petal_api_cards_17"), color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
