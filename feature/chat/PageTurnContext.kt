package com.xingshi.ai.feature.chat.ui.viewmodel


/**
 * 聊天页正在处理的轮次归属。
 *
 * 页面路径与无页面回退路径必须使用同一份「原请求」合同：页面收到轮次时记录，
 * 受控回复终结时按原轮次身份取出并立即移除。不能只保留当前单槽，否则迟到的
 * 旧轮片段会借到新轮身份；同一通话的多轮按到达顺序排队，按输入 ID / call_id
 * 可精确定位。
 */
class PageTurnContext {

    private val lock = Any()
    private val turns = ArrayDeque<ConversationIntake.Turn>()

    fun begin(turn: ConversationIntake.Turn) {
        synchronized(lock) {
            turns.addLast(turn)
        }
    }

    /** 终结一次页面回复：按到达顺序返回最早未终结轮次并移除；没有通道轮次时返回 null。 */
    fun consumeTurn(): ConversationIntake.Turn? = synchronized(lock) {
        turns.removeFirstOrNull()
    }

    /**
     * 按原轮次身份终结：优先正式输入 ID，其次 call_id；都未命中时不借用其它轮次身份。
     */
    fun consumeTurn(callId: String?, inputMessageId: Long = 0L): ConversationIntake.Turn? =
        synchronized(lock) {
            val index = when {
                inputMessageId > 0L -> turns.indexOfFirst { it.userMessageId == inputMessageId }
                callId != null -> turns.indexOfFirst { it.callId == callId }
                else -> 0
            }
            if (index >= 0) turns.removeAt(index) else null
        }

    fun clear() {
        synchronized(lock) { turns.clear() }
    }
}
