package com.xingshi.ai.feature.settings.ui.screen

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.theme.framework.FRAMEWORK_API
import com.xingshi.ai.theme.framework.ThemeCatalogEntry
import com.xingshi.ai.theme.framework.ThemeManifests
import com.xingshi.ai.theme.framework.ThemePackages
import com.xingshi.ai.theme.framework.ThemeInstallOutcome
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.theme.framework.ThemeSwitchResult
import com.xingshi.ai.theme.framework.ThemeUninstallOutcome
import com.xingshi.ai.theme.framework.rememberThemeState
import com.xingshi.ai.theme.framework.themeText

 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeMarketScreen(onNavigateBack: () -> Unit) {
    val state = rememberThemeState()
    val context = LocalContext.current
    val catalog = remember(state.revision) { ThemeRuntime.marketCatalogOrNull() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ThemeCatalogEntry?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(themeText("theme_market"), fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            if (catalog == null) {
                Text(ThemeRuntime.resolveText("theme_market_unavailable"), color = colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    themeText("theme_market_source") + " " + ThemeRuntime.marketSourceLabel(),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(themeText("theme_search_hint")) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                val entries = catalog.search(query)
                if (entries.isEmpty()) {
                    Text(themeText("theme_market_empty"), color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                entries.forEach { entry ->
                    val installed = state.installed.firstOrNull { it.manifest.id == entry.manifest.id }
                    ThemeMarketRow(
                        entry = entry,
                        installedVersion = installed?.manifest?.version,
                        isCurrent = state.themeId == entry.manifest.id,
                        onClick = { selected = entry }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.manifest.name) },
            text = {
                Column {
                    val preview = remember(entry.asset) { ThemeRuntime.marketArtwork(entry, "preview.png") }
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = themeText("theme_preview"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(themeText("theme_version_fmt", entry.manifest.version))
                    Text(themeText("theme_author_fmt", entry.manifest.author))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(entry.manifest.description, fontSize = 13.sp)
                    if (entry.changelog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(entry.changelog, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val compatError = ThemePackages.validateManifest(entry.manifest)
                    Text(
                        text = if (compatError == null) {
                            themeText(
                                "theme_compat_fmt",
                                entry.manifest.minFramework,
                                entry.manifest.maxFramework,
                                FRAMEWORK_API
                            )
                        } else {
                            themeText("theme_incompatible") + "：" + compatError
                        },
                        fontSize = 12.sp,
                        color = if (compatError == null) colorScheme.onSurfaceVariant else colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !ThemePackages.validateManifest(entry.manifest).let { it != null } && entry.manifest.supportsFramework, onClick = {
                    val outcome = ThemeRuntime.installFromAsset(entry)
                    Toast.makeText(
                        context,
                        when (outcome) {
                            is ThemeInstallOutcome.Success -> ThemeRuntime.resolveText("theme_install_done")
                            is ThemeInstallOutcome.Failure -> ThemeRuntime.resolveText("theme_install_failed") + ": " + outcome.message
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    selected = null
                }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(themeText("theme_install"))
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(themeText("plugin_cancel")) }
            }
        )
    }
}

@Composable
private fun ThemeMarketRow(
    entry: ThemeCatalogEntry,
    installedVersion: String?,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = remember(entry.asset) { ThemeRuntime.marketArtwork(entry, "icon.png") }
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = themeText("theme_preview"),
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.primary.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.manifest.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colorScheme.onSurface)
            Text(
                themeText("theme_author_fmt", entry.manifest.author) + " · " +
                    themeText("theme_version_fmt", entry.manifest.version),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
            if (entry.manifest.description.isNotBlank()) {
                Text(entry.manifest.description, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
            }
            if (!entry.manifest.supportsFramework) {
                Text(themeText("theme_incompatible"), fontSize = 12.sp, color = colorScheme.error)
            }
        }
        if (isCurrent) {
            Text(themeText("theme_current"), fontSize = 12.sp, color = colorScheme.primary)
        } else if (installedVersion != null) {
            val canUpdate = ThemeManifests.versionCode(entry.manifest.version) >
                ThemeManifests.versionCode(installedVersion)
            if (canUpdate) {
                Text(
                    themeText("theme_update_available", entry.manifest.version),
                    fontSize = 12.sp,
                    color = colorScheme.primary
                )
            } else {
                Text(themeText("theme_installed"), fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
            }
        }
    }
}

 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeManageScreen(
    onNavigateBack: () -> Unit,
    onThemeModeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onFrameRateClick: () -> Unit
) {
    val state = rememberThemeState()
    val context = LocalContext.current
    val catalog = remember(state.revision) { ThemeRuntime.marketCatalogOrNull() }
    val colorScheme = MaterialTheme.colorScheme
    val displayModeLabel = if (state.themeMode == com.xingshi.ai.uicommon.theme.ThemeMode.DARK.name) {
        themeText("theme_dark")
    } else {
        themeText("theme_system")
    }
    val languageLabel = com.xingshi.ai.theme.framework.LANGUAGE_LABELS[state.language] ?: state.language
    val savedFrameRate = remember { com.xingshi.ai.theme.framework.FrameRateController.getSavedFrameRate(context) }
    val frameRateLabel = if (savedFrameRate == com.xingshi.ai.theme.framework.FrameRateController.FrameRate.AUTO) {
        themeText("framerate_auto")
    } else {
        savedFrameRate.label
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(themeText("theme_manage"), fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            state.installed.forEach { theme ->
                val manifest = theme.manifest
                val isCurrent = manifest.id == state.themeId
                val hasUpdate = ThemeRuntime.hasUpdate(manifest.id)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                manifest.name + if (theme.isOfficial) " · " + themeText("theme_official") else "",
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                color = colorScheme.onSurface
                            )
                            Text(
                                themeText("theme_version_fmt", manifest.version) +
                                    if (manifest.author.isNotBlank()) " · " + manifest.author else "",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        if (isCurrent) Text(themeText("theme_current"), fontSize = 12.sp, color = colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isCurrent) {
                            TextButton(onClick = {
                                val result = ThemeRuntime.switchTheme(manifest.id)
                                Toast.makeText(
                                    context,
                                    when (result) {
                                        is ThemeSwitchResult.Success -> ThemeRuntime.resolveText("theme_apply_done")
                                        is ThemeSwitchResult.Failure -> ThemeRuntime.resolveText("theme_apply_failed") + ": " + result.message
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) { Text(themeText("theme_apply")) }
                        }
                        if (!theme.isOfficial && hasUpdate) {
                            TextButton(onClick = {
                                val entry = catalog?.themes?.firstOrNull { it.manifest.id == manifest.id }
                                if (entry == null) {
                                    Toast.makeText(context, ThemeRuntime.resolveText("theme_market_unavailable"), Toast.LENGTH_SHORT).show()
                                } else {
                                    val outcome = ThemeRuntime.installFromAsset(entry)
                                    Toast.makeText(
                                        context,
                                        when (outcome) {
                                            is ThemeInstallOutcome.Success -> ThemeRuntime.resolveText("theme_update_done")
                                            is ThemeInstallOutcome.Failure -> ThemeRuntime.resolveText("theme_install_failed") + ": " + outcome.message
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) { Text(themeText("theme_update")) }
                        }
                        if (!theme.isOfficial) {
                            TextButton(onClick = {
                                val outcome = ThemeRuntime.uninstallTheme(manifest.id)
                                Toast.makeText(
                                    context,
                                    when (outcome) {
                                        is ThemeUninstallOutcome.Success -> ThemeRuntime.resolveText("theme_uninstall_done")
                                        is ThemeUninstallOutcome.Failure -> ThemeRuntime.resolveText("theme_uninstall_failed") + ": " + outcome.message
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) { Text(themeText("theme_uninstall")) }
                        }
                    }
                    if (isCurrent) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colorScheme.outline.copy(alpha = 0.35f))
                        )
                        ThemeSubSettingRow(Icons.Filled.DarkMode, themeText("theme_mode"), displayModeLabel, onThemeModeClick)
                        ThemeSubSettingRow(Icons.Filled.Language, themeText("theme_language"), languageLabel, onLanguageClick)
                        ThemeSubSettingRow(Icons.Filled.Refresh, themeText("theme_frame_rate"), frameRateLabel, onFrameRateClick)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

 
@Composable
private fun ThemeSubSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, fontSize = 14.sp, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
    }
}
