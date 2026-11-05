package com.xingshi.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.theme.framework.themeText

@Composable
fun ChannelPluginSetupScreen(
    pluginId: String,
    settings: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateBind: () -> Unit = onNavigateBack
) {
    val manager = remember { PluginFramework.manager() }
    val installedFlow = manager?.installed
    val installed = if (installedFlow != null) {
        val state by installedFlow.collectAsState()
        state
    } else {
        emptyList()
    }
    val content = if (installed.none { it.manifest.id == pluginId }) {
        null
    } else if (settings) {
        manager?.settingsPageFor(pluginId, onNavigateBack) { route ->
            if (route == "bind") onNavigateBind()
        }
    } else {
        manager?.pageFor(pluginId, companionId = 0L, input = "", close = onNavigateBack) { route ->
            if (route == "bind") onNavigateBind()
        }
    }
    if (content != null) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = themeText("channel_plugin_not_installed"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp
            )
            Text(
                text = themeText("channel_plugin_install_hint"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Button(onClick = onNavigateBack, modifier = Modifier.padding(top = 16.dp)) {
                Text(themeText("channel_plugin_back"))
            }
        }
    }
}
