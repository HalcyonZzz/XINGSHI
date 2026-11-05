package com.xingshi.ai.feature.backup

import android.content.Context
import com.xingshi.ai.common.DeviceIdProvider
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.GroupMessage
import com.xingshi.ai.database.repository.ChatMessageCrypto
import com.xingshi.ai.database.repository.MemoryCrypto
import com.xingshi.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupExportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    suspend fun export(): BackupData = withContext(Dispatchers.IO) {
        val companions = db.companionDao().getAllCompanionsSync().map { it.toSnapshot() }
        val chatMessages = mutableListOf<ChatMessageSnapshot>()
        val chatGroups = db.chatGroupDao().getAllGroupsSync().map { it.toSnapshot() }
        val groupMessages = mutableListOf<GroupMessageSnapshot>()
        val memoryEntries = db.memoryDao().getAllMemoriesSync(deviceId).map { it.toDecryptedSnapshot() }
        val tempMemories = db.memoryDao().getAllTempMemoriesSync(deviceId).map { it.toSnapshot() }
        val tokenUsages = db.tokenUsageDao().getAllUsageSync(deviceId).map { it.toSnapshot() }

        for (c in companions) {
            val raw = db.chatMessageDao().getMessagesForCompanionSync(c.id)
            chatMessages.addAll(raw.map { ChatMessageCrypto.decryptFromStorage(it).toSnapshot() })
        }

        for (g in chatGroups) {
            val raw = db.groupMessageDao().getMessagesForGroupSync(g.id)
            groupMessages.addAll(raw.map { ChatMessageCrypto.decryptFromStorage(it).toSnapshot() })
        }

        BackupData(
            exportedAt = System.currentTimeMillis(),
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            companions = companions,
            chatMessages = chatMessages,
            chatGroups = chatGroups,
            groupMessages = groupMessages,
            memoryEntries = memoryEntries,
            tempMemories = tempMemories,
            tokenUsages = tokenUsages
        )
    }
}

private fun com.xingshi.ai.database.model.CompanionEntity.toSnapshot() = CompanionSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, age = age,
    personality = personality, backstory = backstory, speakingStyle = speakingStyle,
    tags = tags, rawPrompt = rawPrompt, systemPrompt = systemPrompt,
    intimacy = intimacy, createdAt = createdAt, updatedAt = updatedAt
)

private fun ChatMessage.toSnapshot() = ChatMessageSnapshot(
    id = id, companionId = companionId, content = content, isFromUser = isFromUser,
    timestamp = timestamp, type = type.name, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString
)

private fun com.xingshi.ai.database.model.ChatGroup.toSnapshot() = ChatGroupSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, companionIds = companionIds,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun GroupMessage.toSnapshot() = GroupMessageSnapshot(
    id = id, groupId = groupId, companionId = companionId, content = content,
    timestamp = timestamp, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString
)

private fun com.xingshi.ai.database.model.MemoryEntry.toDecryptedSnapshot(): MemoryEntrySnapshot {
    val decryptedContext = try {
        MemoryCrypto.decrypt(context)
    } catch (_: Exception) {
        context
    }
    return MemoryEntrySnapshot(
        id = id, companionId = companionId, content = content, category = category.name,
        importance = importance, context = decryptedContext, accessCount = accessCount,
        timestamp = timestamp, lastAccessed = lastAccessed, deviceId = deviceId
    )
}

private fun com.xingshi.ai.database.model.TempMemory.toSnapshot() = TempMemorySnapshot(
    id = id, companionId = companionId, userInput = userInput, botResponse = botResponse,
    timestamp = timestamp, deviceId = deviceId
)

private fun com.xingshi.ai.database.model.TokenUsage.toSnapshot() = TokenUsageSnapshot(
    id = id, companionId = companionId, date = date, inputTokens = inputTokens,
    outputTokens = outputTokens, totalTokens = totalTokens, requestCount = requestCount,
    timestamp = timestamp, deviceId = deviceId
)
