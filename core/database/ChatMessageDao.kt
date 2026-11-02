package com.xingshi.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE companionId != :companionId")
    suspend fun getOtherCompanionMessages(companionId: Long): List<ChatMessage>
    // Cursor pagination: 稳定双字段顺序，同毫秒消息靠 id 决出先后，不会跨页跳过。
    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getRecentMessagesForCompanion(companionId: Long, limit: Int): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage>

    // 旧页与滑动最新页之间的空洞补齐：按 id 区间取交界消息。
    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND id > :afterId AND id < :beforeId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesBetweenIdsSync(companionId: Long, afterId: Long, beforeId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId ORDER BY timestamp ASC")
    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): ChatMessage?

    @Update
    suspend fun updateMessage(message: ChatMessage): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE linkString = :linkString AND type = 'CALL'")
    suspend fun countCallCardsByLinkString(linkString: String): Int

    @Query("SELECT * FROM chat_messages WHERE type = 'CALL'")
    suspend fun getCallCardsSync(): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND searchContent LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchMessages(companionId: Long, query: String, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND fileFormat = :fileFormat ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesByFileFormat(companionId: Long, fileFormat: FileFormat, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE companionId = :companionId AND fileFormat = :fileFormat AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesByFileFormatBefore(companionId: Long, fileFormat: FileFormat, beforeTimestamp: Long, limit: Int): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Delete
    suspend fun deleteMessage(message: ChatMessage): Int

    @Query("DELETE FROM chat_messages WHERE companionId = :companionId")
    suspend fun deleteMessagesForCompanion(companionId: Long): Int
}
