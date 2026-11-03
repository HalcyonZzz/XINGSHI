package com.xingshi.ai.feature.qqbot.data

import com.xingshi.ai.database.repository.CompanionRepository

class QQBotUserMappingManager(
    private val tokenStore: QQBotTokenStore,
    private val companionRepository: CompanionRepository
) {

    suspend fun getOrCreateMapping(qqUserId: String): Long? {
        val existingId = tokenStore.getCompanionIdForQQUser(qqUserId)
        if (existingId != null && existingId > 0) {
            val companion = companionRepository.getCompanionById(existingId)
            if (companion != null) return existingId
            
            
            return null
        }
        return createDefaultMapping(qqUserId)
    }

     
    private suspend fun createDefaultMapping(qqUserId: String): Long? {
        val defaultCompanionId = tokenStore.getDefaultCompanionId()
        if (defaultCompanionId == null || defaultCompanionId <= 0) return null
        if (companionRepository.getCompanionById(defaultCompanionId) == null) return null
        tokenStore.setCompanionIdForQQUser(qqUserId, defaultCompanionId)
        return defaultCompanionId
    }

    suspend fun updateMapping(qqUserId: String, companionId: Long): Boolean {
        val companion = companionRepository.getCompanionById(companionId)
        if (companion == null) return false
        tokenStore.setCompanionIdForQQUser(qqUserId, companionId)
        return true
    }

    suspend fun removeMapping(qqUserId: String) {
        tokenStore.removeQQUserMapping(qqUserId)
    }

    suspend fun getAllMappings(): Map<String, Long> {
        return tokenStore.getAllQQUserMappings()
    }

    suspend fun getMappingForQQUser(qqUserId: String): Long? {
        val existingId = tokenStore.getCompanionIdForQQUser(qqUserId)
        if (existingId != null && existingId > 0) {
            val companion = companionRepository.getCompanionById(existingId)
            if (companion != null) return existingId
            
        }
        return null
    }
}
