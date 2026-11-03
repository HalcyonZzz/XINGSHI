package com.xingshi.ai.feature.backup

import android.content.Context
import androidx.room.withTransaction
import com.xingshi.ai.common.DeviceIdProvider
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.*
import com.xingshi.ai.database.repository.ChatMessageCrypto
import com.xingshi.ai.database.repository.MemoryCrypto
import com.xingshi.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 
class BackupImportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    suspend fun import(data: BackupData): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            
            
            db.withTransaction {
                
                db.groupMessageDao().let { dao ->
                    data.chatGroups.forEach { dao.deleteMessagesForGroup(it.id) }
                }
                db.chatMessageDao().let { dao ->
                    data.companions.forEach { dao.deleteMessagesForCompanion(it.id) }
                }
                
                db.memoryDao().deleteAllMemories(deviceId)
                db.memoryDao().deleteAllTempMemories(deviceId)
                db.tokenUsageDao().deleteAll(deviceId)
                db.groupMessageDao().let { dao ->
                    db.chatGroupDao().getAllGroupsSync().forEach { dao.deleteMessagesForGroup(it.id) }
                }
                db.chatMessageDao().let { dao ->
                    db.companionDao().getAllCompanionsSync().forEach { dao.deleteMessagesForCompanion(it.id) }
                }
                db.chatGroupDao().getAllGroupsSync().forEach { db.chatGroupDao().deleteGroup(it) }
                db.companionDao().getAllCompanionsSync().forEach { db.companionDao().deleteCompanion(it) }

                
                data.companions.forEach { s ->
                    db.companionDao().insertCompanion(
                        CompanionEntity(
                            id = s.id, name = s.name, avatarUrl = s.avatarUrl, age = s.age,
                            personality = s.personality, backstory = s.backstory,
                            speakingStyle = s.speakingStyle, tags = s.tags,
                            rawPrompt = s.rawPrompt, systemPrompt = s.systemPrompt,
                            intimacy = s.intimacy, createdAt = s.createdAt,
                            updatedAt = s.updatedAt
                        )
                    )
                }

                data.chatGroups.forEach { s ->
                    db.chatGroupDao().insertGroup(
                        ChatGroup(
                            id = s.id, name = s.name, avatarUrl = s.avatarUrl,
                            companionIds = s.companionIds, createdAt = s.createdAt,
                            updatedAt = s.updatedAt
                        )
                    )
                }

                data.chatMessages.forEach { s ->
                    val msg = ChatMessage(
                        id = s.id, companionId = s.companionId, content = s.content,
                        isFromUser = s.isFromUser, timestamp = s.timestamp,
                        type = safeEnum<MessageType>(s.type),
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString
                    )
                    db.chatMessageDao().insertMessage(ChatMessageCrypto.encryptForStorage(msg))
                }

                data.groupMessages.forEach { s ->
                    val msg = GroupMessage(
                        id = s.id, groupId = s.groupId, companionId = s.companionId,
                        content = s.content, timestamp = s.timestamp,
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString
                    )
                    db.groupMessageDao().insertMessage(ChatMessageCrypto.encryptForStorage(msg))
                }

                data.memoryEntries.forEach { s ->
                    val encryptedContext = if (s.context.isNotBlank()) {
                        try { MemoryCrypto.encrypt(s.context) } catch (_: Exception) { s.context }
                    } else ""
                    db.memoryDao().insertMemory(
                        MemoryEntry(
                            id = s.id, companionId = s.companionId, content = s.content,
                            category = safeEnum<MemoryCategory>(s.category),
                            importance = s.importance, context = encryptedContext,
                            accessCount = s.accessCount, timestamp = s.timestamp,
                            lastAccessed = s.lastAccessed, deviceId = deviceId
                        )
                    )
                }

                data.tempMemories.forEach { s ->
                    db.memoryDao().insertTempMemory(
                        TempMemory(
                            id = s.id, companionId = s.companionId,
                            userInput = s.userInput, botResponse = s.botResponse,
                            timestamp = s.timestamp, deviceId = deviceId
                        )
                    )
                }

                data.tokenUsages.forEach { s ->
                    db.tokenUsageDao().insert(
                        TokenUsage(
                            id = s.id, companionId = s.companionId, date = s.date,
                            inputTokens = s.inputTokens, outputTokens = s.outputTokens,
                            totalTokens = s.totalTokens, requestCount = s.requestCount,
                            timestamp = s.timestamp, deviceId = deviceId
                        )
                    )
                }
            }
        }
    }

     
    private inline fun <reified T : Enum<T>> safeEnum(name: String): T {
        return try { enumValueOf<T>(name) }
        catch (_: Exception) { enumValues<T>().first() }
    }
}
