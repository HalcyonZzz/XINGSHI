package com.xingshi.ai.feature.chat.ui.screen

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.feature.chat.data.ChatDetailSettingsStore

import com.xingshi.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.xingshi.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.xingshi.ai.uicommon.component.ChatBackgroundPickerDialog
import com.xingshi.ai.uicommon.component.getChatBackgroundKey
import com.xingshi.ai.theme.framework.themeText

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

@Composable
fun ChatDetailScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onCompanionDeleted: () -> Unit = onNavigateBack
) {
    val context = LocalContext.current
    val store = remember { ChatDetailSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId, bindChannelEntry = false)
    )
    val companionData by viewModel.companionData.collectAsState()
    val settings by store.settingsFlow(companionId).collectAsState(initial = com.xingshi.ai.feature.chat.data.CompanionChatDetailSettings())
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteFinal by remember { mutableStateOf(false) }
    var deleteAcknowledged by remember { mutableStateOf(false) }
    ThemePageSlot(
        page = ThemePages.COMPANION_DETAIL,
        host = {
            ThemePageHost(
                page = ThemePages.COMPANION_DETAIL,
                items = emptyList(),
                state = mapOf(
                    "companion_id" to companionId.toString(),
                    "name" to companionData?.name.orEmpty(),
                    "age" to companionData?.age?.toString().orEmpty(),
                    "personality" to companionData?.personality.orEmpty(),
                    "avatar_url" to companionData?.avatarUrl.orEmpty(),
                    "blocked" to settings.blocked.toString(),
                    "wechat_sync_enabled" to settings.wechatSyncEnabled.toString(),
                    "background_key" to settings.backgroundKey.orEmpty(),
                    "use_global_background" to settings.useGlobalBackground.toString()
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "clear_history" -> viewModel.clearChatHistory()
                            "delete_companion" -> {
                                deleteAcknowledged = false
                                showDeleteConfirm = true
                            }
                            "toggle_wechat_sync" -> scope.launch {
                                store.updateSettings(companionId) { it.copy(wechatSyncEnabled = !it.wechatSyncEnabled) }
                            }
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
            ChatDetailScreenContent(
                companionId = companionId,
                onNavigateBack = onNavigateBack,
                onCompanionDeleted = onCompanionDeleted,
                onRequestDelete = {
                    deleteAcknowledged = false
                    showDeleteConfirm = true
                }
            )
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(themeText("ui_chat_detail_screen_09")) },
            text = { Text(themeText("ui_chat_detail_screen_10")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        deleteAcknowledged = false
                        showDeleteFinal = true
                    }
                ) { Text(themeText("ui_chat_detail_screen_11"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(themeText("ui_chat_detail_screen_12")) }
            }
        )
    }

    if (showDeleteFinal) {
        AlertDialog(
            onDismissRequest = { showDeleteFinal = false },
            title = { Text(themeText("ui_chat_detail_screen_delete_final_title")) },
            text = {
                Column {
                    Text(themeText("ui_chat_detail_screen_delete_final_message"))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteAcknowledged = !deleteAcknowledged }
                    ) {
                        Checkbox(
                            checked = deleteAcknowledged,
                            onCheckedChange = { deleteAcknowledged = it }
                        )
                        Text(themeText("ui_chat_detail_screen_delete_ack"))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deleteAcknowledged,
                    onClick = {
                        showDeleteFinal = false
                        viewModel.deleteCompanionPermanently { onCompanionDeleted() }
                    }
                ) { Text(themeText("ui_chat_detail_screen_delete_action"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFinal = false }) { Text(themeText("ui_chat_detail_screen_12")) }
            }
        )
    }
}

@Composable
private fun ChatDetailScreenContent(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onCompanionDeleted: () -> Unit = onNavigateBack,
    onRequestDelete: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ChatDetailSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId, bindChannelEntry = false)
    )
    val companionData by viewModel.companionData.collectAsState()
    val settings by store.settingsFlow(companionId).collectAsState(initial = com.xingshi.ai.feature.chat.data.CompanionChatDetailSettings())
    var showBgPicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = themeText("ui_chat_detail_screen_01"), tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    text = themeText("ui_chat_detail_screen_02"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }

            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (companionData?.avatarUrl != null) {
                        AsyncImage(
                            model = companionData?.avatarUrl,
                            contentDescription = companionData?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = companionData?.name?.firstOrNull()?.toString() ?: "?",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = companionData?.name ?: "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!companionData?.personality.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = companionData?.personality ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            
            SectionTitle("聊天外观")
            SettingsCard {
                SettingsRow(title = themeText("ui_chat_detail_screen_03"), subtitle = backgroundName(settings.backgroundKey, context)) {
                    showBgPicker = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = themeText("ui_chat_detail_screen_04"),
                    checked = settings.useGlobalBackground,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(useGlobalBackground = checked) }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            
            SectionTitle("关系与隐私")
            SettingsCard {
                DangerRow(title = themeText("ui_chat_detail_screen_06"), color = Color(0xFFFF3B30)) {
                    onRequestDelete()
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DangerRow(title = themeText("ui_chat_detail_screen_07"), color = Color(0xFFFF3B30)) {
                    showClearConfirm = true
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    
    if (showBgPicker) {
        val currentKey = if (settings.useGlobalBackground) getChatBackgroundKey(context) else (settings.backgroundKey ?: "default")
        ChatBackgroundPickerDialog(
            currentKey = currentKey,
            onDismiss = { showBgPicker = false },
            onSelect = { key ->
                scope.launch {
                    store.updateSettings(companionId) { it.copy(backgroundKey = key, useGlobalBackground = false) }
                }
                showBgPicker = false
            }
        )
    }

    
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(themeText("ui_chat_detail_screen_13")) },
            text = { Text(themeText("ui_chat_detail_screen_14")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.clearChatHistory()
                        }
                        showClearConfirm = false
                    }
                ) { Text(themeText("ui_chat_detail_screen_15"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(themeText("ui_chat_detail_screen_16")) }
            }
        )
    }

}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(text = "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun DangerRow(title: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun backgroundName(key: String?, context: android.content.Context): String {
    return when (key) {
        null, "default" -> "默认白色"
        "warm_pink" -> "暖粉色"
        "lavender" -> "薰衣草"
        "ocean" -> "海洋蓝"
        "forest" -> "森林绿"
        "sunset" -> "日落橙"
        "night" -> "夜空"
        else -> if (key.startsWith("custom_")) "自定义图片" else "未知"
    }
}
