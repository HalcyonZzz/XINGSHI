package com.xingshi.ai.plugins.ui

import com.xingshi.ai.plugins.PluginOutcome
import com.xingshi.ai.plugins.api.PluginReport


internal fun pageReportOf(
    outcomes: Map<Pair<String, Long>, PluginOutcome>,
    pluginId: String,
    companionId: Long
): PluginReport? = outcomes[pluginId to companionId]?.report
