package com.xingshi.ai.feature.companion

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.domain.CompanionProvider

/**
 * Adapter that bridges domain CompanionProvider to CompanionRepository.
 * Registered in XingShiApplication via ServiceRegistry.
 */
class CompanionProviderImpl(context: Context) : CompanionProvider {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val repository = CompanionRepository(database.companionDao())

    override suspend fun getCompanionName(id: Long): String {
        return repository.getCompanionById(id)?.name ?: "未知"
    }

    override suspend fun getCompanionAvatar(id: Long): String {
        return repository.getCompanionById(id)?.avatarUrl ?: ""
    }

    override suspend fun isCompanionAvailable(id: Long): Boolean {
        return repository.getCompanionById(id) != null
    }
}
