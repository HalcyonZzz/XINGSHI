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
            // 目标角色已删除：保留失效映射作为拒绝状态，不静默改派默认角色；
            // 只有用户显式重新映射才覆盖，连续多条消息均拒绝
            return null
        }
        return createDefaultMapping(wechatUserId)
    }

    /** 只承接用户显式配置且仍有效的默认映射；不得擅自取第一个角色。 */
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
