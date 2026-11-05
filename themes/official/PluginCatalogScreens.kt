package com.xingshi.ai.plugins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingshi.ai.plugins.InstalledPluginRecord
import com.xingshi.ai.plugins.PluginCatalogEntry
import com.xingshi.ai.plugins.PluginCatalogState
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginInstallCenter
import com.xingshi.ai.plugins.PluginInstallResult
import com.xingshi.ai.plugins.PluginManifests
import com.xingshi.ai.plugins.PluginManager
import com.xingshi.ai.theme.icons.PackageIconImage
import com.xingshi.ai.theme.icons.pluginBuiltinIcon
import com.xingshi.ai.theme.icons.rememberPluginCatalogHostImages
import com.xingshi.ai.theme.icons.rememberPluginCatalogIcon
import com.xingshi.ai.themes.official.R
import kotlinx.coroutines.launch
import com.xingshi.ai.theme.framework.themeText

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = actions
            )
        }
    ) { padding -> content(padding) }
}

@Composable
internal fun PluginCenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PluginMarketScreen(onNavigateBack: () -> Unit, onOpenPlugin: (String) -> Unit) {
    val manager = PluginFramework.manager()
    val installed by (manager?.installed ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var installMessage by remember { mutableStateOf("") }
    var catalogState by remember { mutableStateOf(PluginCatalogState()) }
    LaunchedEffect(manager) {
        catalog = manager?.search("").orEmpty()
        catalogState = manager?.marketplace?.value ?: PluginCatalogState()
    }
    ThemePageSlot(
        page = ThemePages.PLUGIN_MARKET,
        host = {
            ThemePageHost(
                page = ThemePages.PLUGIN_MARKET,
                items = catalog.map { entry ->
                    ThemePageItem(
                        id = entry.id,
                        kind = "plugin",
                        title = entry.name,
                        subtitle = entry.description
                    )
                },
                state = mapOf(
                    "plugin_count" to catalog.size.toString(),
                    "manager_ready" to (manager != null).toString(),
                    "install_message" to installMessage,
                    "catalog_loading" to catalogState.loading.toString(),
                    "catalog_cached" to catalogState.cached.toString(),
                    "catalog_error" to catalogState.error.orEmpty()
                ),
                images = rememberPluginCatalogHostImages(PluginFramework.store(), catalog, installed),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "open_plugin" -> action.payload["id"]?.let(onOpenPlugin)
                            "install" -> action.payload["id"]?.let { id ->
                                catalog.firstOrNull { it.id == id }?.let { entry ->
                                    scope.launch {
                                        installMessage = when (val result = manager?.install(entry)) {
                                            is PluginInstallResult.Success -> "installed"
                                            is PluginInstallResult.Failure -> result.message
                                            else -> ""
                                        }
                                    }
                                }
                            }
                            "refresh" -> scope.launch {
                                catalog = manager?.refreshCatalog().orEmpty()
                                catalogState = manager?.marketplace?.value ?: PluginCatalogState()
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
        fallback = { PluginMarketScreenContent(onNavigateBack = onNavigateBack, onOpenPlugin = onOpenPlugin) }
    )
}

@Composable
private fun PluginMarketScreenContent(onNavigateBack: () -> Unit, onOpenPlugin: (String) -> Unit) {
    val manager = PluginFramework.manager()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val stateFlow = manager?.marketplace ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PluginCatalogState(loaded = true)) }
    val state by stateFlow.collectAsState()
    val installing by PluginInstallCenter.states.collectAsState()

    LaunchedEffect(manager) { manager?.refreshCatalog() }

    val entries = remember(state.entries, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) state.entries else state.entries.filter {
            it.name.contains(keyword, ignoreCase = true) ||
                it.description.contains(keyword, ignoreCase = true) ||
                it.author.contains(keyword, ignoreCase = true)
        }
    }

    PluginScaffold(
        title = themeText("plugin_market"),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { scope.launch { manager?.refreshCatalog() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = themeText("circle_refresh"))
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(themeText("plugin_search_hint")) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                manager == null -> PluginCenteredText(themeText("plugin_unavailable"))
                state.loading && !state.loaded -> PluginCenteredText(themeText("plugin_loading"))
                state.error != null && state.entries.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = themeText("plugin_market_failed"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { scope.launch { manager.refreshCatalog() } }) {
                        Text(themeText("circle_retry"))
                    }
                }
                state.error != null -> Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = themeText("plugin_market_cached"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MarketList(
                        manager = manager,
                        entries = entries,
                        installing = installing,
                        onOpenPlugin = onOpenPlugin
                    )
                }
                entries.isEmpty() -> PluginCenteredText(themeText("plugin_empty"))
                else -> MarketList(
                    manager = manager,
                    entries = entries,
                    installing = installing,
                    onOpenPlugin = onOpenPlugin
                )
            }
        }
    }
}

@Composable
private fun MarketList(
    manager: PluginManager,
    entries: List<PluginCatalogEntry>,
    installing: Map<String, PluginInstallCenter.State>,
    onOpenPlugin: (String) -> Unit
) {
    val installedNow by manager.installed.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            PluginCatalogRow(
                manager = manager,
                entry = entry,
                installedRecord = installedNow.firstOrNull { it.manifest.id == entry.id },
                progress = installing[entry.id],
                onClick = { onOpenPlugin(entry.id) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PluginCatalogRow(
    manager: PluginManager,
    entry: PluginCatalogEntry,
    installedRecord: InstalledPluginRecord?,
    progress: PluginInstallCenter.State?,
    onClick: () -> Unit
) {
    val installed = installedRecord != null
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PackageIconImage(
            image = rememberPluginCatalogIcon(PluginFramework.store(), entry, installedRecord),
            fallback = pluginBuiltinIcon(entry.icon) ?: Icons.Filled.Extension,
            size = 44.dp,
            background = MaterialTheme.colorScheme.surfaceVariant,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "v${entry.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${themeText("plugin_author")}${entry.author}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            progress?.takeIf { it.running }?.let { state ->
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = themeText("plugin_downloading"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (installed) {
            Text(
                text = themeText("plugin_installed"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = (manager.install(entry) as? PluginInstallResult.Failure)?.message
                        busy = false
                    }
                }
            ) {
                Text(
                    text = if (busy) themeText("plugin_loading") else themeText("plugin_install"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun PluginDetailScreen(pluginId: String, onNavigateBack: () -> Unit) {
    val manager = PluginFramework.manager()
    val scope = rememberCoroutineScope()
    val installed by (manager?.installed ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    var entry by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(manager) { entry = manager?.search("")?.firstOrNull { it.id == pluginId } }
    ThemePageSlot(
        page = ThemePages.PLUGIN_DETAIL,
        host = {
            val record = installed.firstOrNull { it.manifest.id == pluginId }
            ThemePageHost(
                page = ThemePages.PLUGIN_DETAIL,
                items = entry?.let { item ->
                    listOf(
                        ThemePageItem(id = "install", kind = "action", title = "install"),
                        ThemePageItem(id = "back", kind = "action", title = themeText("profile_back"))
                    )
                }.orEmpty(),
                state = mapOf(
                    "plugin_id" to pluginId,
                    "name" to (entry?.name ?: record?.manifest?.name).orEmpty(),
                    "version" to (entry?.version ?: record?.manifest?.version).orEmpty(),
                    "author" to entry?.author.orEmpty(),
                    "description" to entry?.description.orEmpty(),
                    "installed" to (record != null).toString(),
                    "message" to message
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "install", "update" -> entry?.let { item ->
                                scope.launch {
                                    message = when (val result = manager?.install(item)) {
                                        is PluginInstallResult.Success -> "installed"
                                        is PluginInstallResult.Failure -> result.message
                                        else -> ""
                                    }
                                }
                            }
                            "uninstall" -> scope.launch {
                                message = if (manager?.uninstall(pluginId) == true) "uninstalled" else "failed"
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
        fallback = { PluginDetailScreenContent(pluginId = pluginId, onNavigateBack = onNavigateBack) }
    )
}

@Composable
private fun PluginDetailScreenContent(pluginId: String, onNavigateBack: () -> Unit) {
    val manager = PluginFramework.manager()
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manager, pluginId) {
        entry = manager?.search("")?.firstOrNull { it.id == pluginId }
        loaded = true
    }

    PluginScaffold(title = entry?.name ?: pluginId, onNavigateBack = onNavigateBack) { padding ->
        val item = entry
        when {
            !loaded -> PluginCenteredText(themeText("plugin_loading"))
            item == null -> PluginCenteredText(themeText("plugin_not_found"))
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    PackageIconImage(
                        image = rememberPluginCatalogIcon(
                            PluginFramework.store(),
                            item,
                            manager?.installed?.value?.firstOrNull { it.manifest.id == item.id }
                        ),
                        fallback = pluginBuiltinIcon(item.icon) ?: Icons.Filled.Extension,
                        size = 96.dp,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = item.name, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${themeText("plugin_version")} ${item.version}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "${themeText("plugin_author")}${item.author}+", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    val installed = manager?.isInstalled(item.id) == true
                    val installedVersion = manager?.installed?.value?.firstOrNull { it.manifest.id == item.id }?.manifest?.version
                    if (installed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${themeText("plugin_installed")} v$installedVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    message?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                val installedNow = manager?.isInstalled(item.id) == true
                val installedNowVersion = manager?.installed?.value?.firstOrNull { it.manifest.id == item.id }?.manifest?.version
                val upToDate = installedNowVersion != null &&
                    PluginManifests.versionCode(installedNowVersion) >= PluginManifests.versionCode(item.version)
                Button(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    enabled = manager != null && !busy && !(installedNow && upToDate),
                    onClick = {
                        val target = manager ?: return@Button
                        scope.launch {
                            busy = true
                            val result = if (installedNow) target.update(item) else target.install(item)
                            message = when (result) {
                                is PluginInstallResult.Success -> null
                                is PluginInstallResult.Failure -> result.message
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(
                        text = when {
                            busy -> themeText("plugin_loading")
                            upToDate && installedNowVersion != item.version -> themeText("plugin_up_to_date")
                            upToDate -> themeText("plugin_installed")
                            installedNow -> themeText("plugin_update")
                            else -> themeText("plugin_install")
                        }
                    )
                }
            }
        }
    }
}
