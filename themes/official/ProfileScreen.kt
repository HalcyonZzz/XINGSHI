package com.xingshi.ai.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.themes.official.R
import kotlinx.coroutines.delay
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.ThemePageAction

@Composable
fun ProfileScreen(
    onStarMarketClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPluginManageClick: () -> Unit,
    onThemeManageClick: () -> Unit,
    
    onGeneralSettingsClick: () -> Unit,
    
    onAboutClick: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    ThemePageSlot(
        page = ThemePages.PROFILE,
        host = {
            val userName by viewModel.userName.collectAsState()
            val avatar by viewModel.userAvatar.collectAsState()
            val connectionError by viewModel.error.collectAsState()
            val entries = listOf(
                Triple("star_market", "star_market_entry", onStarMarketClick),
                Triple("api_settings", "api_settings", onSettingsClick),
                Triple("plugin_manage", "plugin_manage_entry", onPluginManageClick),
                Triple("theme_manage", "theme_manage_entry", onThemeManageClick),
                Triple("general_settings", "general_settings", onGeneralSettingsClick),
                Triple("about", "about_app", onAboutClick),
                Triple("logout", "ui_account_logout", { viewModel.logout() })
            )
            ThemePageHost(
                page = ThemePages.PROFILE,
                items = entries.map { (id, labelKey, _) ->
                    ThemePageItem(id = id, kind = "profile_entry", title = themeText(labelKey))
                },
                state = mapOf("user_name" to userName, "has_avatar" to avatar.isNullOrBlank().not().toString(), "connection_error" to connectionError.orEmpty()),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "reconnect" -> viewModel.reconnect()
                            "star_market" -> onStarMarketClick()
                            "theme_market", "plugin_market" -> onStarMarketClick()
                            "api_settings" -> onSettingsClick()
                            "plugin_manage" -> onPluginManageClick()
                            "theme_manage" -> onThemeManageClick()
                            "general_settings" -> onGeneralSettingsClick()
                            "about" -> onAboutClick()
                            "logout" -> viewModel.logout()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            ThemePages.SETTINGS -> onSettingsClick()
                            ThemePages.THEME_MARKET, ThemePages.PLUGIN_MARKET -> onStarMarketClick()
                            ThemePages.THEME_MANAGE -> onThemeManageClick()
                            ThemePages.GENERAL_SETTINGS -> onGeneralSettingsClick()
                            "star_market" -> onStarMarketClick()
                            "about" -> onAboutClick()
                        }
                    }
                }
            )
        },
        fallback = {
            ProfileScreenContent(
                onStarMarketClick = onStarMarketClick,
                onSettingsClick = onSettingsClick,
                onPluginManageClick = onPluginManageClick,
                onThemeManageClick = onThemeManageClick,
                onGeneralSettingsClick = onGeneralSettingsClick,
                onAboutClick = onAboutClick,
                viewModel = viewModel
            )
        }
    )
}

@Composable
private fun ProfileScreenContent(
    onStarMarketClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPluginManageClick: () -> Unit,
    onThemeManageClick: () -> Unit,
    onGeneralSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val colorScheme = MaterialTheme.colorScheme

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val nameSaving by viewModel.nameSaving.collectAsState()
    val nameError by viewModel.nameError.collectAsState()
    val nameSaved by viewModel.nameSaved.collectAsState()
    var isEditingName by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(nameSaved) {
        if (nameSaved) {
            isEditingName = false
            viewModel.consumeNameSaved()
        }
    }

    val cancelEditName = {
        isEditingName = false
        editName = userName
        viewModel.clearNameError()
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.updateUserAvatar(it.toString()) }
    }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.surfaceVariant)
                    .clickable { imagePicker.launch("image/*") }
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5E5E5))
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (userAvatar != null) {
                            AsyncImage(
                                model = userAvatar,
                                contentDescription = themeText("profile_avatar"),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = themeText("profile_avatar"),
                                tint = Color(0xFFAAAAAA),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isEditingName) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        enabled = !nameSaving,
                        modifier = Modifier.fillMaxWidth(0.8f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline,
                            focusedContainerColor = colorScheme.surface,
                            unfocusedContainerColor = colorScheme.surface
                        ),
                        trailingIcon = {
                            if (nameSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = { viewModel.updateUserName(editName) }, enabled = editName.isNotBlank()) {
                                    Icon(Icons.Filled.Edit, themeText("profile_save"), tint = if (editName.isNotBlank()) colorScheme.primary else colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    )
                    val saveError = nameError
                    when {
                        nameSaving -> Text(
                            text = "保存中…",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = colorScheme.onSurfaceVariant
                        )
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (saveError != null) {
                                Text(
                                    text = saveError,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = colorScheme.error
                                )
                            }
                            TextButton(onClick = cancelEditName) { Text("取消") }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            isEditingName = true
                            editName = userName
                            viewModel.clearNameError()
                        }
                    ) {
                        Text(
                            text = userName.ifBlank { "补全昵称" },
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                            color = if (userName.isBlank()) colorScheme.error else colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Filled.Edit, themeText("profile_edit"), tint = if (userName.isBlank()) colorScheme.error else colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    if (userName.isBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "还没有昵称，点击补全后才能正常聊天",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = themeText("profile_avatar_hint"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Storefront, themeText("star_market_entry"), themeText("star_market_entry_desc"), onStarMarketClick)
            ),
            isVisible = isVisible, delayMillis = 110
        )

        Spacer(modifier = Modifier.height(12.dp))

        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Settings, themeText("api_settings"), themeText("api_settings_desc"), onSettingsClick),
                MenuItemData(Icons.Filled.Widgets, themeText("plugin_manage_entry"), themeText("plugin_manage_entry_desc"), onPluginManageClick),
                MenuItemData(Icons.Filled.Palette, themeText("theme_manage_entry"), themeText("theme_manage_entry_desc"), onThemeManageClick)
            ),
            isVisible = isVisible, delayMillis = 170
        )

        Spacer(modifier = Modifier.height(12.dp))

        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Settings, themeText("general_settings"), themeText("general_settings_desc"), onGeneralSettingsClick)
            ),
            isVisible = isVisible, delayMillis = 260
        )

        Spacer(modifier = Modifier.height(12.dp))

        
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Info, themeText("about_app"), themeText("about_app_desc"), onAboutClick)
            ),
            isVisible = isVisible, delayMillis = 320
        )

        val accountError by viewModel.error.collectAsState()
        accountError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::reconnect) { Text(themeText("ui_account_reconnect")) } }
        androidx.compose.material3.TextButton(onClick = { viewModel.logout() }) { Text(themeText("ui_account_logout")) }
        Spacer(modifier = Modifier.height(80.dp))
    }

}





internal data class MenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
internal fun SolidMenuGroup(items: List<MenuItemData>, isVisible: Boolean, delayMillis: Int) {
    val colorScheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = delayMillis)) +
                slideInVertically(tween(400, delayMillis = delayMillis)) { it / 4 }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items.forEachIndexed { index, item ->
                SolidMenuItem(item.icon, item.title, item.subtitle, item.onClick, index < items.size - 1)
            }
        }
    }
}

@Composable
internal fun SolidMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, showDivider: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, title, Modifier.size(24.dp), tint = colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = colorScheme.onSurface)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colorScheme.outline).padding(start = 36.dp))
        }
    }
}
