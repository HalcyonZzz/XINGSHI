package com.xingshi.ai.feature.chat.ui.viewmodel

class PageTurnContext {

    private val lock = Any()
    private val turns = ArrayDeque<ConversationIntake.Turn>()

    fun begin(turn: ConversationIntake.Turn) {
        synchronized(lock) {
            turns.addLast(turn)
        }
    }

    fun consumeTurn(): ConversationIntake.Turn? = synchronized(lock) {
        turns.removeFirstOrNull()
    }

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
