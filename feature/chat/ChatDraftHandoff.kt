package com.xingshi.ai.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ChatDraftHandoff {

    data class Bilibili(
        val sourceUrl: String,
        val bv: String,
        val page: Int,
        val cid: Long,
        val title: String?
    )

    data class Pending(
        val id: String,
        val text: String = "",
        val bilibili: Bilibili? = null,
        val noticeKey: String? = null
    )

    private val mutable = MutableStateFlow<Map<Long, Pending>>(emptyMap())
    val entries: StateFlow<Map<Long, Pending>> = mutable.asStateFlow()

    fun offer(companionId: Long, pending: Pending) {
        if (companionId <= 0L) return
        synchronized(this) { mutable.value = mutable.value + (companionId to pending) }
    }

    fun take(companionId: Long, id: String): Pending? = synchronized(this) {
        val current = mutable.value[companionId] ?: return null
        if (current.id != id) return null
        mutable.value = mutable.value - companionId
        current
    }
}
