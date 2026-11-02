package com.xingshi.ai.feature.settings

import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.domain.ChatModelDraft
import com.xingshi.ai.domain.MainChatConfigurationIssue
import com.xingshi.ai.domain.configurationIssue
import com.xingshi.ai.domain.mainChatProtocol

fun ApiConfig.toChatModelDraft(localId: Long = id, apiKey: String = this.apiKey) = ChatModelDraft(
    localId, mainChatProtocol(provider.name, formatHint).orEmpty(), baseUrl, model,
    apiKey, temperature, maxTokens, provider.name
)

/** Blank on an existing edit retains the remote credential; a new MAIN needs its own Key. */
fun ApiConfig.mainConfigurationIssue(allowStoredKey: Boolean = id > 0): MainChatConfigurationIssue? =
    toChatModelDraft().configurationIssue()
        ?: MainChatConfigurationIssue.KEY_REQUIRED.takeIf { apiKey.isBlank() && !allowStoredKey }
