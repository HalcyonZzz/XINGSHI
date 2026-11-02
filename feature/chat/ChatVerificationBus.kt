package com.xingshi.ai.feature.chat.ui.viewmodel

import com.xingshi.ai.plugins.api.ChannelRequestRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 无模型时的可控流式输入：仅 Debug 构建由宿主调试广播驱动，用于验证主题对
 * 「固定片段增长 → 末段完整保存」的呈现，不进入发布路径，也不伪造真实模型结果。
 */
object ChatVerificationBus {

    data class Fragment(
        val companionId: Long,
        val text: String,
        val terminal: Boolean,
        /** 通话轮次归属：非空时只能由该 call_id 的正式结果交接消费。 */
        val callId: String? = null,
        /** 原通道请求归属，随正式终结一起交回。 */
        val request: ChannelRequestRef? = null,
        /** 本轮正式输入消息 ID；Debug 终结链按它终结，不能从当前页面槽借用身份。 */
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
