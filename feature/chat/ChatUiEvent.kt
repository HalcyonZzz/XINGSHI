package com.xingshi.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable

 
@Stable
sealed class ChatUiEvent {
     
    data class Info(val message: String) : ChatUiEvent()

     
    data class Error(val message: String) : ChatUiEvent()

     
    data class ContentBlocked(val reason: String) : ChatUiEvent()

    data class ThemedInfo(val key: String) : ChatUiEvent()

    data class ThemedError(val key: String, val detail: String? = null) : ChatUiEvent()
}
