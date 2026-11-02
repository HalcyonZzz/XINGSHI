package com.xingshi.ai.common

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 单实例 Activity 的热启动 Intent 桥。
 *
 * MainActivity 是 singleTask：通知栏再次点击时只回调 onNewIntent，不会重建 Compose 页面。
 * 页面通过这里订阅新的 Intent，消费 open_chat 等导航参数；冷启动仍由 Activity.intent 处理。
 */
object AppIntentRouter {

    private val _incoming = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val incoming: SharedFlow<Intent> = _incoming.asSharedFlow()

    fun publish(intent: Intent) {
        _incoming.tryEmit(intent)
    }
}
