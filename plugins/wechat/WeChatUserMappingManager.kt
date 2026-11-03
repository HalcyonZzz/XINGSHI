package com.xingshi.ai.feature.wechat.data

import com.xingshi.ai.database.repository.CompanionRepository

class WeChatUserMappingManager(
    private val tokenStore: WeChatTokenStore,
    private val companionRepository: CompanionRepository
) {

    suspend fun getOrCreateMapping(wechatUserId: String): Long? {
        val existingId = tokenStore.getCompanionIdForWechatUser(wechatUserId)
        if (existingId != null && existingId > 0) {
            val companion = companionRepository.getCompanionById(existingId)
            if (companion != null) return existingId
            
            
            return null
        }
        return createDefaultMapping(wechatUserId)
    }

     
    private suspend fun createDefaultMapping(wechatUserId: String): Long? {
        val defaultCompanionId = tokenStore.getDefaultCompanionId()
        if (defaultCompanionId == null || defaultCompanionId <= 0) return null
        if (companionRepository.getCompanionById(defaultCompanionId) == null) return null
        tokenStore.setCompanionIdForWechatUser(wechatUserId, defaultCompanionId)
        return defaultCompanionId
    }

    suspend fun updateMapping(wechatUserId: String, companionId: Long): Boolean {
        val companion = companionRepository.getCompanionById(companionId)
        if (companion == null) return false
        tokenStore.setCompanionIdForWechatUser(wechatUserId, companionId)
        return true
    }

    suspend fun removeMapping(wechatUserId: String) {
        tokenStore.removeWechatUserMapping(wechatUserId)
    }

    suspend fun getAllMappings(): Map<String, Long> {
        return tokenStore.getAllWechatUserMappings()
    }

    suspend fun getMappingForWechatUser(wechatUserId: String): Long? {
        val existingId = tokenStore.getCompanionIdForWechatUser(wechatUserId)
        if (existingId != null && existingId > 0) {
            val companion = companionRepository.getCompanionById(existingId)
            if (companion != null) return existingId
            tokenStore.removeWechatUserMapping(wechatUserId)
        }
        return null
    }
}
