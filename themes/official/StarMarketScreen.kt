package com.xingshi.ai.feature.profile

import android.widget.Toast
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.plugins.PluginCatalogEntry
import com.xingshi.ai.plugins.PluginCatalogState
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginManifests
import com.xingshi.ai.theme.framework.FRAMEWORK_API
import com.xingshi.ai.theme.framework.ThemeCatalogEntry
import com.xingshi.ai.theme.framework.ThemeInstallOutcome
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.theme.framework.rememberThemeState
import com.xingshi.ai.theme.framework.themeText
import kotlinx.coroutines.launch

private enum class StarFilter { PLUGIN, THEME }

private sealed interface StarEntry {
    val title: String
}

private data class StarPluginEntry(
    val entry: PluginCatalogEntry,
    val installedVersion: String?,
    val hasUpdate: Boolean
) : StarEntry {
    override val title: String get() = entry.name
}

private data class StarThemeEntry(
    val entry: ThemeCatalogEntry,
    val installedVersion: String?,
    val isCurrent: Boolean
) : StarEntry {
    override val title: String get() = entry.manifest.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMarketScreen(
    onNavigateBack: () -> Unit,
    onOpenPlugin: (String) -> Unit
) {
    val manager = PluginFramework.manager()
    val themeState = rememberThemeState()
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var pluginCatalog by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var pluginCatalogState by remember { mutableStateOf(PluginCatalogState()) }
    var installedPlugins by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf(StarFilter.PLUGIN, StarFilter.THEME)) }
    var showFilter by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf<ThemeCatalogEntry?>(null) }

    val installedFlow = manager?.installed
    val installedState = if (installedFlow != null) installedFlow.collectAsState() else null
    LaunchedEffect(manager, installedState?.value) {
        if (manager != null) {
            pluginCatalog = manager.search("")
            pluginCatalogState = manager.marketplace.value
            installedPlugins = installedState?.value.orEmpty()
                .associate { it.manifest.id to it.manifest.version }
        }
    }
    val themeCatalog = remember(themeState.revision) { ThemeRuntime.marketCatalogOrNull() }

    val plugins = if (StarFilter.PLUGIN in filters) {
        pluginCatalog.filter { entry ->
            entry.name.contains(query, ignoreCase = true) ||
                entry.description.contains(query, ignoreCase = true) ||
                entry.author.contains(query, ignoreCase = true)
        }.map { entry ->
            val installedVersion = installedPlugins[entry.id]
            StarPluginEntry(
                entry = entry,
                installedVersion = installedVersion,
                hasUpdate = installedVersion != null &&
                    PluginManifests.versionCode(entry.version) > PluginManifests.versionCode(installedVersion)
            )
        }
    } else {
        emptyList()
    }
    val themes = if (StarFilter.THEME in filters && themeCatalog != null) {
        themeCatalog.search(query).map { entry ->
            val installed = themeState.installed.firstOrNull { it.manifest.id == entry.manifest.id }
            StarThemeEntry(
                entry = entry,
                installedVersion = installed?.manifest?.version,
                isCurrent = themeState.themeId == entry.manifest.id
            )
        }
    } else {
        emptyList()
    }
    val entries: List<StarEntry> = (plugins + themes).sortedBy { it.title.lowercase() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(themeText("star_market"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, themeText("profile_back"), tint = colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            manager?.refreshCatalog()
                            pluginCatalogState = manager?.marketplace?.value ?: PluginCatalogState()
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = themeText("circle_refresh"), tint = colorScheme.onSurface)
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(themeText("star_market_search_hint")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showFilter = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = themeText("star_market_filter"))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (pluginCatalogState.loading && !pluginCatalogState.loaded) {
                Text(themeText("plugin_loading"), color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (pluginCatalogState.error != null && pluginCatalogState.entries.isEmpty()) {
                Text(themeText("plugin_market_failed"), color = colorScheme.error, fontSize = 13.sp)
                TextButton(onClick = {
                    scope.launch {
                        manager?.refreshCatalog()
                        pluginCatalogState = manager?.marketplace?.value ?: PluginCatalogState()
                    }
                }) { Text(themeText("circle_retry")) }
            } else if (pluginCatalogState.error != null) {
                Text(themeText("plugin_market_cached"), color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (entries.isEmpty()) {
                Text(themeText("star_market_empty"), color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            entries.forEach { item ->
                when (item) {
                    is StarPluginEntry -> StarMarketPluginRow(item) { onOpenPlugin(item.entry.id) }
                    is StarThemeEntry -> StarMarketThemeRow(item) { selectedTheme = item.entry }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showFilter) {
        AlertDialog(
            onDismissRequest = { showFilter = false },
            title = { Text(themeText("star_market_filter")) },
            text = {
                Column {
                    StarFilterRow(themeText("star_market_filter_plugins"), StarFilter.PLUGIN in filters) {
                        filters = if (StarFilter.PLUGIN in filters) filters - StarFilter.PLUGIN else filters + StarFilter.PLUGIN
                    }
                    StarFilterRow(themeText("star_market_filter_themes"), StarFilter.THEME in filters) {
                        filters = if (StarFilter.THEME in filters) filters - StarFilter.THEME else filters + StarFilter.THEME
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilter = false }) { Text(themeText("plugin_cancel")) }
            }
        )
    }

    selectedTheme?.let { entry ->
        ThemeInstallDialog(
            entry = entry,
            onDismiss = { selectedTheme = null }
        )
    }
}

@Composable
private fun StarFilterRow(label: String, checked: Boolean, onCheckedChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun StarMarketPluginRow(item: StarPluginEntry, onClick: () -> Unit) {
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
        StarIconBox(Icons.Filled.Message)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.entry.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colorScheme.onSurface)
            Text(
                themeText("theme_author_fmt", item.entry.author) + " · " +
                    themeText("theme_version_fmt", item.entry.version),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
            if (item.entry.description.isNotBlank()) {
                Text(item.entry.description, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = when {
                item.installedVersion == null -> themeText("plugin_install")
                item.hasUpdate -> themeText("plugin_update")
                else -> themeText("plugin_installed")
            },
            fontSize = 12.sp,
            color = colorScheme.primary
        )
    }
}

@Composable
private fun StarMarketThemeRow(item: StarThemeEntry, onClick: () -> Unit) {
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
        StarIconBox(Icons.Filled.Storefront)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.entry.manifest.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colorScheme.onSurface)
            Text(
                themeText("theme_author_fmt", item.entry.manifest.author) + " · " +
                    themeText("theme_version_fmt", item.entry.manifest.version),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
            if (item.entry.manifest.description.isNotBlank()) {
                Text(item.entry.manifest.description, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = when {
                item.isCurrent -> themeText("theme_current")
                item.installedVersion != null -> themeText("theme_installed")
                else -> themeText("theme_install")
            },
            fontSize = 12.sp,
            color = colorScheme.primary
        )
    }
}

@Composable
private fun StarIconBox(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ThemeInstallDialog(entry: ThemeCatalogEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val compatError = com.xingshi.ai.theme.framework.ThemePackages.validateManifest(entry.manifest)
    val installDone = themeText("theme_install_done")
    val installFailed = themeText("theme_install_failed")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.manifest.name) },
        text = {
            Column {
                Text(themeText("theme_version_fmt", entry.manifest.version))
                Text(themeText("theme_author_fmt", entry.manifest.author))
                Spacer(modifier = Modifier.height(6.dp))
                Text(entry.manifest.description, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (compatError == null) themeText("theme_compat_fmt", entry.manifest.minFramework, entry.manifest.maxFramework, FRAMEWORK_API) else themeText("theme_incompatible") + "：" + compatError,
                    fontSize = 12.sp,
                    color = if (compatError == null) colorScheme.onSurfaceVariant else colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = compatError == null && entry.manifest.supportsFramework,
                onClick = {
                    when (val outcome = ThemeRuntime.installFromAsset(entry)) {
                        is ThemeInstallOutcome.Success -> Toast.makeText(context, installDone, Toast.LENGTH_SHORT).show()
                        is ThemeInstallOutcome.Failure -> Toast.makeText(context, installFailed + ": " + outcome.message, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(themeText("theme_install"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(themeText("plugin_cancel")) }
        }
    )
}
