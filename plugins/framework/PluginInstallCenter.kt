package com.xingshi.ai.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

 
object PluginInstallCenter {

    data class State(
        val pluginId: String,
        val received: Long = 0L,
        val total: Long = 0L,
        val running: Boolean = true,
        val error: String? = null
    ) {
        val fraction: Float?
            get() = if (total > 0L) (received.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    }

    private val statesRef = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = statesRef.asStateFlow()

    fun start(pluginId: String, total: Long) {
        statesRef.update { it + (pluginId to State(pluginId = pluginId, total = total)) }
    }

    fun progress(pluginId: String, received: Long, total: Long) {
        statesRef.update { current ->
            val state = current[pluginId] ?: State(pluginId = pluginId)
            current + (pluginId to state.copy(received = received, total = if (total > 0L) total else state.total))
        }
    }

    fun finish(pluginId: String, error: String? = null) {
        statesRef.update { current ->
            val state = current[pluginId] ?: return@update current - pluginId
            current + (pluginId to state.copy(running = false, error = error))
        }
    }

    fun clear(pluginId: String) {
        statesRef.update { it - pluginId }
    }
}
