package com.xingshi.ai.plugins.host

import android.app.Application
import com.xingshi.ai.common.AppForegroundTracker
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.plugins.api.PluginCompanionRef
import com.xingshi.ai.plugins.api.PluginHostServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal class HostPluginServices(private val app: Application) : PluginHostServices {

    private fun companionRepository() =
        CompanionRepository(AppDatabase.getDatabase(app).companionDao())

    override fun companions(): Flow<List<PluginCompanionRef>> = flow {
        emitAll(companionRepository().getAllCompanions().map { companions ->
            companions.map { PluginCompanionRef(it.id, it.name) }
        })
    }

    override suspend fun companionExists(companionId: Long): Boolean =
        companionRepository().getCompanionById(companionId) != null

    override fun isAppInForeground(): Boolean = AppForegroundTracker.isInForeground
}
