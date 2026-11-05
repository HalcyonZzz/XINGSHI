package com.xingshi.ai.feature.chat.ui.viewmodel

import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ChatVerificationBus {

    data class Fragment(
        val companionId: Long,
        val text: String,
        val terminal: Boolean,
        val callId: String? = null,
        val request: ChannelRequestRef? = null,
        val inputMessageId: Long = 0L
    )

    private val _events = MutableSharedFlow<Fragment>(extraBufferCapacity = 64)
    val events: SharedFlow<Fragment> = _events.asSharedFlow()

    fun stream(
        companionId: Long,
        text: String,
        terminal: Boolean = false,
        callId: String? = null,
        request: ChannelRequestRef? = null,
        inputMessageId: Long = 0L
    ) {
        _events.tryEmit(Fragment(companionId, text, terminal, callId, request, inputMessageId))
    }
}
