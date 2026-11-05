package com.xingshi.ai.plugins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingshi.ai.plugins.InstalledPluginRecord
import com.xingshi.ai.plugins.PluginCatalogEntry
import com.xingshi.ai.plugins.PluginChannels
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginInstallResult
import com.xingshi.ai.plugins.PluginManifests
import com.xingshi.ai.plugins.api.ChannelStatus
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.theme.framework.ThemeTextResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.icons.PackageIconImage
import com.xingshi.ai.theme.icons.pluginBuiltinIcon
import com.xingshi.ai.theme.icons.rememberInstalledPluginHostImages
import com.xingshi.ai.theme.icons.rememberInstalledPluginIcon

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

@Composable
fun PluginManageScreen(
    onNavigateBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onOpenSettings: (String) -> Unit
) {
    val manager = PluginFramework.manager()
    val scope = rememberCoroutineScope()
    val installed by (manager?.installed ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val hostImages = rememberInstalledPluginHostImages(PluginFramework.store(), installed)
    var message by remember { mutableStateOf("") }
    ThemePageSlot(
        page = ThemePages.PLUGIN_MANAGE,
        host = {
            ThemePageHost(
                page = ThemePages.PLUGIN_MANAGE,
                items = installed.map { record ->
                    ThemePageItem(
                        id = record.manifest.id,
                        kind = "plugin",
                        title = record.manifest.name,
                        subtitle = "v${record.manifest.version}"
                    )
                },
                state = mapOf(
                    "installed_count" to installed.size.toString(),
                    "manager_ready" to (manager != null).toString(),
                    "message" to message
                ),
                images = hostImages,
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "open_plugin" -> action.payload["id"]?.let(onOpenPlugin)
                            "open_settings" -> action.payload["id"]?.let(onOpenSettings)
                            "uninstall" -> action.payload["id"]?.let { id ->
                                scope.launch {
                                    message = if (manager?.uninstall(id) == true) "uninstalled" else "failed"
                                }
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
        fallback = { PluginManageScreenContent(onNavigateBack = onNavigateBack, onOpenPlugin = onOpenPlugin, onOpenSettings = onOpenSettings) }
    )
}

@Composable
private fun PluginManageScreenContent(
    onNavigateBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onOpenSettings: (String) -> Unit
) {
    val manager = PluginFramework.manager()
    if (manager == null) {
        PluginScaffold(title = themeText("plugin_manage"), onNavigateBack = onNavigateBack) {
            PluginCenteredText(themeText("plugin_unavailable"))
        }
        return
    }
    val installed by manager.installed.collectAsState()
    val loadErrors by manager.loadErrors.collectAsState()
    val channelStatuses by PluginChannels.statuses.collectAsState()
    val scope = rememberCoroutineScope()
    val doneLabel = themeText("plugin_install_done")
    val removedLabel = themeText("plugin_uninstalled")
    var catalog by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var pendingUninstall by remember { mutableStateOf<InstalledPluginRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(manager) { catalog = manager.search("") }

    PluginScaffold(title = themeText("plugin_manage"), onNavigateBack = onNavigateBack) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (installed.isEmpty()) {
                PluginCenteredText(themeText("plugin_empty"))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(installed, key = { it.manifest.id }) { record ->
                        val id = record.manifest.id
                        val entry = catalog.firstOrNull { it.id == id }
                        val newer = entry != null &&
                            PluginManifests.versionCode(entry.version) > PluginManifests.versionCode(record.manifest.version)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PackageIconImage(
                                    image = rememberInstalledPluginIcon(PluginFramework.store(), record),
                                    fallback = pluginBuiltinIcon(record.manifest.icon) ?: Icons.Filled.Extension,
                                    size = 40.dp,
                                    background = MaterialTheme.colorScheme.surfaceVariant,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = record.manifest.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "v${record.manifest.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (record.manifest.description.isNotBlank()) {
                                Text(
                                    text = record.manifest.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            loadErrors[id]?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            channelStatusLabel(channelStatuses[id])?.let { (label, failed) ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (failed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val loadedInstance = manager.loadedPlugin(id)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (loadedInstance is com.xingshi.ai.plugins.api.SpeechRecognitionPlugin) {
                                    if (manager.speechRecognitionPluginId() == id) {
                                        Text(themeText("plugin_capability_bound"), style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        TextButton(onClick = { manager.selectSpeechRecognitionPlugin(id) }) {
                                            Text(themeText("plugin_bind_stt"))
                                        }
                                    }
                                }
                                if (loadedInstance is com.xingshi.ai.plugins.api.TextToSpeechPlugin) {
                                    if (manager.textToSpeechPluginId() == id) {
                                        Text(themeText("plugin_capability_bound"), style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        TextButton(onClick = { manager.selectTextToSpeechPlugin(id) }) {
                                            Text(themeText("plugin_bind_tts"))
                                        }
                                    }
                                }
                                if (loadedInstance is com.xingshi.ai.plugins.api.VisionAnalysisPlugin) {
                                    if (manager.visionAnalysisPluginId() == id) {
                                        Text(themeText("plugin_capability_bound"), style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        TextButton(onClick = { manager.selectVisionAnalysisPlugin(id) }) {
                                            Text(themeText("plugin_bind_vision"))
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (manager.settingsPageFor(id) != null) {
                                    TextButton(onClick = { onOpenSettings(id) }) { Text(themeText("plugin_manage_action")) }
                                } else if (manager.hasPage(id)) {
                                    TextButton(onClick = { onOpenPlugin(id) }) { Text(themeText("plugin_open")) }
                                }
                                if (newer) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                val result = manager.update(entry)
                                                message = when (result) {
                                                    is PluginInstallResult.Success -> "$doneLabel v${result.record.manifest.version}"
                                                    is PluginInstallResult.Failure -> result.message
                                                }
                                                busy = false
                                            }
                                        }
                                    ) { Text(themeText("plugin_update")) }
                                }
                                TextButton(onClick = { pendingUninstall = record }) {
                                    Text(themeText("plugin_uninstall"))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingUninstall?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(themeText("plugin_uninstall")) },
            text = { Text(themeText("plugin_uninstall_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    val id = record.manifest.id
                    pendingUninstall = null
                    scope.launch(Dispatchers.IO) {
                        manager.uninstall(id)
                        message = removedLabel
                    }
                }) { Text(themeText("plugin_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text(themeText("plugin_cancel")) }
            }
        )
    }
}

private fun channelStatusLabel(status: ChannelStatus?): Pair<String, Boolean>? = when (status) {
    null -> null
    is ChannelStatus.Failed -> {
        val text = ThemeRuntime.resolveText(status.reason)
        (if (text == ThemeTextResolver.PLACEHOLDER) status.reason else text) to true
    }
    ChannelStatus.Connecting, ChannelStatus.Connected ->
        ThemeRuntime.resolveText("ui_channel_running") to false
    is ChannelStatus.Disconnected -> {
        val text = ThemeRuntime.resolveText(status.reason)
        if (text == ThemeTextResolver.PLACEHOLDER) ThemeRuntime.resolveText("ui_channel_stopped") to false else text to true
    }
    ChannelStatus.Unbound -> ThemeRuntime.resolveText("ui_channel_stopped") to false
}
