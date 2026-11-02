package com.xingshi.ai.plugins.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginManager
import com.xingshi.ai.plugins.PluginOutcome
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.plugins.VISION_MODEL_FAILURE_PREFIX
import com.xingshi.ai.plugins.api.PluginChatCardContext
import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import kotlinx.coroutines.launch
import com.xingshi.ai.theme.framework.themeText

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages


@Composable
fun PluginPageHost(pluginId: String, companionId: Long, input: String, onNavigateBack: () -> Unit) {
    val manager = PluginFramework.manager()
    if (manager != null) {
        val installed by manager.installed.collectAsState()
        val running by manager.running.collectAsState()
        val outcomes by manager.pluginOutcomes.collectAsState()
        val outcome = outcomes[pluginId to companionId]
        val name = installed.firstOrNull { it.manifest.id == pluginId }?.manifest?.name ?: pluginId
        ThemePageSlot(
            page = ThemePages.PLUGIN_PAGE,
            host = {
                ThemePageHost(
                    page = ThemePages.PLUGIN_PAGE,
                    items = emptyList(),
                    // 与原官方路径一致：装载完成/报告变化时重建内容，关闭回调真实返回。
                    content = remember(installed, outcomes, manager, pluginId, companionId, input) {
                        manager.pageFor(pluginId, companionId, input, close = { onNavigateBack() })
                    },
                    state = mapOf(
                        "plugin_id" to pluginId,
                        "companion_id" to companionId.toString(),
                        "input" to input,
                        "name" to name,
                        "running" to running.contains(pluginId).toString(),
                        "outcome_message" to (outcome?.message.orEmpty())
                    ),
                    dispatch = { action ->
                        when (action) {
                            is ThemePageAction.Invoke -> if (action.id == "back") onNavigateBack()
                            is ThemePageAction.Open -> if (action.route == "back") onNavigateBack()
                        }
                    }
                )
            },
            fallback = { PluginPageHostContent(manager, pluginId, companionId, input, onNavigateBack) }
        )
        return
    }
    if (manager == null) {
        PluginScaffold(title = pluginId, onNavigateBack = onNavigateBack) {
            PluginCenteredText(themeText("plugin_unavailable"))
        }
        return
    }
    PluginPageHostContent(manager, pluginId, companionId, input, onNavigateBack)
}

@Composable
private fun PluginPageHostContent(
    manager: PluginManager,
    pluginId: String,
    companionId: Long,
    input: String,
    onNavigateBack: () -> Unit
) {
    val installed by manager.installed.collectAsState()
    val outcomes by manager.pluginOutcomes.collectAsState()
    val name = installed.firstOrNull { it.manifest.id == pluginId }?.manifest?.name ?: pluginId
    // 页面只认本插件自己的最近一次结果：会话横幅是最后一条，可能来自别的插件
    val report = pageReportOf(outcomes, pluginId, companionId)
    // 内容也以入口列表为键：页面在 reload 完成前组合（如进程死亡恢复路由）会缓存 null，
    // 装载完成后必须换成本次装载的实例，不能停在「不可用」上
    val content = remember(installed, manager, pluginId, companionId, input, report) {
        manager.pageFor(pluginId, companionId, input, onNavigateBack)
    }

    if (content == null) {
        PluginScaffold(title = name, onNavigateBack = onNavigateBack) {
            PluginCenteredText(themeText("plugin_page_unavailable"))
        }
    } else {
        content()
    }
}

@Composable
fun PluginSettingsHost(
    pluginId: String,
    onNavigateBack: () -> Unit,
    onNavigateRoute: (String) -> Unit = {}
) {
    val manager = PluginFramework.manager()
    if (manager != null) {
        val installed by manager.installed.collectAsState()
        val name = installed.firstOrNull { it.manifest.id == pluginId }?.manifest?.name ?: pluginId
        ThemePageSlot(
            page = ThemePages.PLUGIN_SETTINGS,
            host = {
                ThemePageHost(
                    page = ThemePages.PLUGIN_SETTINGS,
                    items = emptyList(),
                    content = remember(installed, manager, pluginId) {
                        // 插件设置内容里的 ctx.close 必须真实返回：外壳返回按钮不能替代
                        manager.settingsPageFor(
                            pluginId,
                            close = { onNavigateBack() },
                            navigate = onNavigateRoute
                        )
                    },
                    state = mapOf(
                        "plugin_id" to pluginId,
                        "name" to name,
                        "installed" to (installed.any { it.manifest.id == pluginId }).toString()
                    ),
                    dispatch = { action ->
                        when (action) {
                            is ThemePageAction.Invoke -> if (action.id == "back") onNavigateBack()
                            is ThemePageAction.Open -> if (action.route == "back") onNavigateBack()
                        }
                    }
                )
            },
            fallback = {
                val content = manager.settingsPageFor(
                    pluginId,
                    close = { onNavigateBack() },
                    navigate = onNavigateRoute
                )
                if (content != null) {
                    content()
                } else {
                    PluginScaffold(title = name, onNavigateBack = onNavigateBack) {
                        PluginCenteredText(themeText("plugin_unavailable"))
                    }
                }
            }
        )
        return
    }
    if (manager == null) {
        PluginScaffold(title = pluginId, onNavigateBack = onNavigateBack) {
            PluginCenteredText(themeText("plugin_unavailable"))
        }
        return
    }
    val installed by manager.installed.collectAsState()
    val content = remember(installed, manager, pluginId) {
        manager.settingsPageFor(
            pluginId,
            close = { onNavigateBack() },
            navigate = onNavigateRoute
        )
    }

    if (content == null) {
        PluginScaffold(title = pluginId, onNavigateBack = onNavigateBack) {
            PluginCenteredText(themeText("plugin_page_unavailable"))
        }
    } else {
        content()
    }
}

@Composable
fun PluginInputActions(companionId: Long, input: String, onOpenPlugin: (String) -> Unit) {
    val manager = PluginFramework.manager() ?: return
    val installed by manager.installed.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = remember(installed) { manager.actions() }
    if (actions.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        actions.forEach { ref ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (manager.hasPage(ref.pluginId)) {
                            manager.rememberInput(companionId, input, ref.action.id)
                            onOpenPlugin(ref.pluginId)
                        } else {
                            scope.launch {
                                manager.rememberInput(companionId, input, ref.action.id)
                                manager.execute(
                                    ref.pluginId,
                                    PluginRequest(companionId, input, emptyList())
                                )
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = ref.action.label.ifBlank { ref.pluginName },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PluginChatCards(companionId: Long, input: String) {
    val manager = PluginFramework.manager() ?: return
    val installed by manager.installed.collectAsState()
    val outcomes by manager.pluginOutcomes.collectAsState()
    val scope = rememberCoroutineScope()
    val cards = remember(installed) { manager.chatCards() }
    if (cards.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        cards.forEach { ref ->
            // 每个插件只拿自己的结果：会话横幅是最后一条，可能来自别的插件
            val report = outcomes[ref.pluginId to companionId]?.report
            ref.card.content(
                PluginChatCardContext(
                    companionId = companionId,
                    input = input,
                    report = report,
                    execute = { request -> scope.launch { manager.execute(ref.pluginId, request) } }
                )
            )
        }
    }
}

@Composable
fun PluginFailureToast(companionId: Long) {
    val manager = PluginFramework.manager() ?: return
    val outcomes by manager.outcomes.collectAsState()
    val outcome = outcomes[companionId] ?: return
    val message = outcome.message ?: (outcome.report as? PluginReport.Failure)?.message.orEmpty()
    if (!message.startsWith(VISION_MODEL_FAILURE_PREFIX)) return
    val context = LocalContext.current
    LaunchedEffect(outcome.executionId, message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PluginPendingBanner(companionId: Long) {
    val manager = PluginFramework.manager() ?: return
    val outcomes by manager.outcomes.collectAsState()
    val outcome = outcomes[companionId] ?: return
    if (outcome.submitted) return
    val scope = rememberCoroutineScope()
    var retrying by remember(outcome.executionId) { mutableStateOf(false) }
    val message = outcome.message ?: (outcome.report as? PluginReport.Failure)?.message.orEmpty()
    if (message.isBlank()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !retrying,
                    onClick = {
                        retrying = true
                        scope.launch {
                            manager.retryOutcome(companionId)
                            retrying = false
                        }
                    }
                ) {
                    Text(themeText("plugin_retry"))
                }
                TextButton(onClick = { manager.dismissOutcome(companionId) }) {
                    Text(themeText("plugin_back_chat"))
                }
                if (outcome.originalText.isNotBlank()) {
                    TextButton(onClick = {
                        scope.launch {
                            // 发送被拒（未接入/队列已满）时保留横幅与待处理记录，别把原问题一起丢掉
                            if (manager.sendOriginal(companionId, outcome.originalText)) {
                                manager.dismissOutcome(companionId)
                            }
                        }
                    }) {
                        Text(themeText("plugin_send_original"))
                    }
                }
            }
        }
    }
}

// 插件页面上下文与聊天卡片共用同一条读取口径：按 (插件, 会话) 取本插件自己的最近一次结果。
// 会话单槽横幅只保证显示最后一条，别的插件占着横幅时页面不能从它那里取报告，否则永远刷新不到自己的新结果。
internal fun pageReportOf(
    outcomes: Map<Pair<String, Long>, PluginOutcome>,
    pluginId: String,
    companionId: Long
): PluginReport? = outcomes[pluginId to companionId]?.report
