package com.xingshi.ai.plugins.ui

import com.xingshi.ai.plugins.PluginOutcome
import com.xingshi.ai.plugins.api.PluginReport

// 插件页面上下文与聊天卡片共用同一条读取口径：按 (插件, 会话) 取本插件自己的最近一次结果。
internal fun pageReportOf(
    outcomes: Map<Pair<String, Long>, PluginOutcome>,
    pluginId: String,
    companionId: Long
): PluginReport? = outcomes[pluginId to companionId]?.report
