package com.xingshi.ai.feature.profile

import com.xingshi.ai.themes.official.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.theme.framework.FrameRateController
import kotlinx.coroutines.delay
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.ThemePageAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onNavigateBack: () -> Unit,
    onDataBackupClick: () -> Unit,
    onOriginOSAdaptionClick: () -> Unit = {},
    onWeChatClick: () -> Unit = {},
    onQQBotClick: () -> Unit = {}
) {
    ThemePageSlot(
        page = ThemePages.GENERAL_SETTINGS,
        host = {
            ThemePageHost(
                page = ThemePages.GENERAL_SETTINGS,
                items = listOf(
                    ThemePageItem("backup", "settings_entry", themeText("data_backup")),
                    ThemePageItem("originos", "settings_entry", themeText("originos_adaption"))
                ),
                state = emptyMap(),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "open_backup", "backup" -> onDataBackupClick()
                            "open_originos", "originos" -> onOriginOSAdaptionClick()
                            "open_wechat" -> onWeChatClick()
                            "open_qqbot" -> onQQBotClick()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            "data_backup" -> onDataBackupClick()
                            "originos_adaption" -> onOriginOSAdaptionClick()
                            "wechat_settings", "wechat_bind" -> onWeChatClick()
                            "qqbot_settings" -> onQQBotClick()
                        }
                    }
                }
            )
        },
        fallback = {
            GeneralSettingsScreenContent(
                onNavigateBack = onNavigateBack,
                onDataBackupClick = onDataBackupClick,
                onOriginOSAdaptionClick = onOriginOSAdaptionClick
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSettingsScreenContent(
    onNavigateBack: () -> Unit,
    onDataBackupClick: () -> Unit,
    onOriginOSAdaptionClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(80); isVisible = true }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(themeText("general_settings"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, themeText("profile_back"), tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SolidMenuGroup(
                items = listOf(
                    MenuItemData(Icons.Filled.SaveAlt, themeText("data_backup"), themeText("data_backup_desc"), onDataBackupClick),
                    MenuItemData(Icons.Filled.Tune, themeText("originos_adaption"), themeText("originos_adaption_desc"), onOriginOSAdaptionClick)
                ),
                isVisible = isVisible, delayMillis = 220
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 280)) + slideInVertically(tween(400, delayMillis = 280)) { it / 4 }
            ) {
                PermissionSettingsCard()
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ============================================================================

@Composable
private fun PermissionSettingsCard() {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        PermissionItem(themeText("auto_start"), themeText("auto_start_desc")) {
            com.xingshi.ai.common.BatteryOptimizationHelper.openAutoStartSettings(context)
        }
        PermissionDivider()
        PermissionItem(themeText("battery_whitelist"), themeText("battery_whitelist_desc")) {
            if (!com.xingshi.ai.common.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
                com.xingshi.ai.common.BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
            else
                com.xingshi.ai.common.BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
        }
        PermissionDivider()
        PermissionItem(themeText("notification_permission"), themeText("notification_permission_desc")) {
            com.xingshi.ai.common.BatteryOptimizationHelper.openNotificationSettings(context)
        }
    }
}

@Composable
private fun PermissionItem(title: String, subtitle: String, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PowerSettingsNew, title, Modifier.size(24.dp), tint = colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PermissionDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(0.5.dp).background(MaterialTheme.colorScheme.outline))
}
