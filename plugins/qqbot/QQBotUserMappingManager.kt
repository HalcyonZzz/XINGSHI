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
            // 目标角色已删除：保留失效映射作为拒绝状态，不静默改派默认角色；
            // 只有用户显式重新映射才覆盖，连续多条消息均拒绝
            return null
        }
        return createDefaultMapping(qqUserId)
    }

    /** 只承接用户显式配置且仍有效的默认映射；不得擅自取第一个角色。 */
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
            // 失效映射保留到用户显式重设：不得在读取时删除并让后续消息套默认角色
        }
        return null
    }
}
