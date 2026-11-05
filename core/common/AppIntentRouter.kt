package com.xingshi.ai.common

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AppIntentRouter {

    private val _incoming = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val incoming: SharedFlow<Intent> = _incoming.asSharedFlow()

    fun publish(intent: Intent) {
        _incoming.tryEmit(intent)
    }
}
