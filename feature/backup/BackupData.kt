package com.xingshi.ai.feature.backup.model

import com.xingshi.ai.database.model.FileFormat
import com.xingshi.ai.database.model.MemoryCategory
import com.xingshi.ai.database.model.MessageType
import kotlinx.serialization.Serializable

 
@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    val companions: List<CompanionSnapshot>,
    val chatMessages: List<ChatMessageSnapshot>,
    val chatGroups: List<ChatGroupSnapshot>,
    val groupMessages: List<GroupMessageSnapshot>,
    val memoryEntries: List<MemoryEntrySnapshot>,
    val tempMemories: List<TempMemorySnapshot>,
    val tokenUsages: List<TokenUsageSnapshot>
)

@Serializable
data class CompanionSnapshot(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
    val age: Int? = null,
    val personality: String,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val tags: String? = null,
    val rawPrompt: String? = null,
    val systemPrompt: String? = null,
    val intimacy: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ChatMessageSnapshot(
    val id: Long,
    val companionId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long,
    val type: String = "TEXT",
    val searchContent: String = "",
    val fileFormat: String = "TEXT",
    val linkString: String = ""
)

@Serializable
data class ChatGroupSnapshot(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
    val companionIds: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GroupMessageSnapshot(
    val id: Long,
    val groupId: Long,
    val companionId: Long,
    val content: String,
    val timestamp: Long,
    val searchContent: String = "",
    val fileFormat: String = "TEXT",
    val linkString: String = ""
)

@Serializable
data class MemoryEntrySnapshot(
    val id: Long,
    val companionId: Long,
    val content: String,
    val category: String = "FACT",
    val importance: Float = 0.5f,
    val context: String = "",
    val accessCount: Int = 1,
    val timestamp: Long,
    val lastAccessed: Long,
    val deviceId: String = ""
)

@Serializable
data class TempMemorySnapshot(
    val id: Long,
    val companionId: Long,
    val userInput: String,
    val botResponse: String,
    val timestamp: Long,
    val deviceId: String = ""
)

@Serializable
data class TokenUsageSnapshot(
    val id: Long,
    val companionId: Long,
    val date: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val requestCount: Int = 0,
    val timestamp: Long,
    val deviceId: String = ""
)
