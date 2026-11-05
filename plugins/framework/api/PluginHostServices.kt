package com.xingshi.ai.plugins.api

import kotlinx.coroutines.flow.Flow

data class PluginCompanionRef(
    val id: Long,
    val name: String
)

interface PluginHostServices {
    fun companions(): Flow<List<PluginCompanionRef>>

    suspend fun companionExists(companionId: Long): Boolean

    fun isAppInForeground(): Boolean
}

object PluginHost {

    @Volatile
    private var installed: PluginHostServices? = null

    fun install(services: PluginHostServices) {
        installed = services
    }

    fun companions(): Flow<List<PluginCompanionRef>> = services().companions()

    suspend fun companionExists(companionId: Long): Boolean = services().companionExists(companionId)

    fun isAppInForeground(): Boolean = services().isAppInForeground()

    private fun services(): PluginHostServices =
        installed ?: throw IllegalStateException("宿主能力未接入")
}
