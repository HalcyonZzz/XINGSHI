package com.xingshi.ai.database.repository

import com.xingshi.ai.database.dao.ChatMessageDao
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

const val MESSAGE_BODY_BYTE_BUDGET: Int = 860 * 1024

const val MESSAGE_BODY_OVERSIZED_KEY: String = "ui_attach_too_large"

class ChatMessageCapacityExceeded : IllegalStateException("消息正文超过单行可读容量")

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    private val accountPartition = com.xingshi.ai.common.AccountStorage.partition
    private fun cacheKey(companionId: Long) = accountPartition to companionId

    companion object {
        private val recentCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, List<ChatMessage>>()

        fun isVisibleInNormalUi(message: ChatMessage): Boolean =
            message.type == com.xingshi.ai.database.model.MessageType.CALL ||
                !message.linkString.startsWith("call:")

        fun messageRowBytes(content: String, linkString: String): Long {
            val contentBytes = content.toByteArray(Charsets.UTF_8).size
            val linkBytes = linkString.toByteArray(Charsets.UTF_8).size
            return encryptedBytes(contentBytes) + contentBytes + encryptedBytes(linkBytes)
        }

        private fun encryptedBytes(plainBytes: Int): Long =
            if (plainBytes == 0) 0L else 7L + 4L * ((plainBytes + 12 + 16 + 2) / 3)
    }

    private val writtenCallLinks = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun List<ChatMessage>.visibleOnly(): List<ChatMessage> = filter(ChatRepository::isVisibleInNormalUi)

    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        recentCache[cacheKey(companionId)] = messages
    }

    fun getCachedRecent(companionId: Long): List<ChatMessage>? = recentCache[cacheKey(companionId)]

    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.visibleOnly().reversed() }
            .onEach { decrypted -> recentCache[cacheKey(companionId)] = decrypted }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getMessagesBefore(companionId, beforeTimestamp, beforeId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.visibleOnly().reversed() }

    data class VisibleMessages(val messages: List<ChatMessage>, val hasMore: Boolean)

    suspend fun getVisibleRecent(companionId: Long, limit: Int): VisibleMessages {
        var beforeTimestamp: Long? = null
        var beforeId: Long? = null
        var rawMore = true
        val newestFirst = mutableListOf<ChatMessage>()
        while (newestFirst.size < limit && rawMore) {
            val raw = if (beforeTimestamp == null) {
                chatMessageDao.getRecentMessagesSync(companionId, limit)
            } else {
                chatMessageDao.getMessagesBeforeSync(companionId, beforeTimestamp, beforeId!!, limit)
            }
            if (raw.isEmpty()) break
            rawMore = raw.size == limit
            newestFirst += raw.map(ChatMessageCrypto::decryptFromStorage)
                .filter(ChatRepository::isVisibleInNormalUi)
            beforeTimestamp = raw.last().timestamp
            beforeId = raw.last().id
        }
        val pageNewest = newestFirst.take(limit)
        val hasMore = if (pageNewest.size >= limit) {
            val cursor = pageNewest.lastOrNull()
            cursor != null && chatMessageDao.getMessagesBeforeSync(companionId, cursor.timestamp, cursor.id, 1).isNotEmpty()
        } else {
            rawMore
        }
        return VisibleMessages(pageNewest.reversed(), hasMore)
    }

    suspend fun getVisibleBefore(
        companionId: Long,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): VisibleMessages {
        var cursorTimestamp = beforeTimestamp
        var cursorId = beforeId
        var rawMore = true
        val newestFirst = mutableListOf<ChatMessage>()
        while (newestFirst.size < limit && rawMore) {
            val raw = chatMessageDao.getMessagesBeforeSync(companionId, cursorTimestamp, cursorId, limit)
            if (raw.isEmpty()) break
            rawMore = raw.size == limit
            newestFirst += raw.map(ChatMessageCrypto::decryptFromStorage)
                .filter(ChatRepository::isVisibleInNormalUi)
            cursorTimestamp = raw.last().timestamp
            cursorId = raw.last().id
        }
        val pageNewest = newestFirst.take(limit)
        val hasMore = if (pageNewest.size >= limit) {
            val cursor = pageNewest.lastOrNull()
            cursor != null && chatMessageDao.getMessagesBeforeSync(companionId, cursor.timestamp, cursor.id, 1).isNotEmpty()
        } else {
            rawMore
        }
        return VisibleMessages(pageNewest.reversed(), hasMore)
    }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, 20)
            .map { list ->
                var found = list.asSequence()
                    .map(ChatMessageCrypto::decryptFromStorage)
                    .firstOrNull(ChatRepository::isVisibleInNormalUi)
                if (found == null && list.size == 20) {
                    found = getVisibleRecent(companionId, 1).messages.lastOrNull()
                }
                found
            }

    private fun ChatMessage.requireWritableBody(): ChatMessage {
        if (content.toByteArray(Charsets.UTF_8).size > MESSAGE_BODY_BYTE_BUDGET) throw ChatMessageCapacityExceeded()
        return this
    }

    suspend fun sendMessage(message: ChatMessage): Long {
        val stored = ChatMessageCrypto.encryptForStorage(message.requireWritableBody())
        val id = chatMessageDao.insertMessage(stored)
        rememberCallLink(message, stored)
        return id
    }

    private fun rememberCallLink(original: ChatMessage, stored: ChatMessage) {
        if (original.type == com.xingshi.ai.database.model.MessageType.CALL &&
            original.linkString.startsWith("call:")
        ) {
            writtenCallLinks[original.linkString] = stored.linkString
        }
    }

    suspend fun deleteMessage(message: ChatMessage) = chatMessageDao.deleteMessage(message)

    suspend fun clearChatHistory(companionId: Long) {
        chatMessageDao.deleteMessagesForCompanion(companionId)
        recentCache.remove(cacheKey(companionId))
    }

    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getRecentMessagesSync(companionId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .visibleOnly()
            .also { recentCache[cacheKey(companionId)] = it.reversed() }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getMessagesBeforeSync(companionId, beforeTimestamp, beforeId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .visibleOnly()

    suspend fun getMessagesBetweenIdsSync(companionId: Long, afterId: Long, beforeId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesBetweenIdsSync(companionId, afterId, beforeId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .visibleOnly()

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesForCompanionSync(companionId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun searchMessages(companionId: Long, query: String, limit: Int = 50): List<ChatMessage> {
        if (limit <= 0) return emptyList()
        var rawLimit = limit
        while (true) {
            val raw = chatMessageDao.searchMessages(companionId, query, rawLimit)
            val visible = raw.map(ChatMessageCrypto::decryptFromStorage)
                .filter(ChatRepository::isVisibleInNormalUi)
            if (visible.size >= limit || raw.size < rawLimit) return visible.take(limit)
            if (rawLimit >= Int.MAX_VALUE / 2) return visible.take(limit)
            rawLimit *= 2
        }
    }

    suspend fun getMessagesByFileFormat(companionId: Long, fileFormat: FileFormat, limit: Int = 50): List<ChatMessage> =
        chatMessageDao.getMessagesByFileFormat(companionId, fileFormat, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormatBefore(
        companionId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        limit: Int = 50
    ): List<ChatMessage> =
        chatMessageDao.getMessagesByFileFormatBefore(companionId, fileFormat, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessageById(messageId: Long): ChatMessage? =
        chatMessageDao.getMessageById(messageId)?.let(ChatMessageCrypto::decryptFromStorage)

    suspend fun countCallCardsByLinkString(linkString: String): Int {
        val exactKey = if (linkString.startsWith("enc:v1:")) linkString else writtenCallLinks[linkString]
        if (exactKey != null) {
            val exact = runCatching { chatMessageDao.countCallCardsByLinkString(exactKey) }.getOrDefault(0)
            if (exact > 0) return exact
        }
        val cards = runCatching { chatMessageDao.getCallCardsSync() }.getOrDefault(emptyList())
        return cards.count { ChatMessageCrypto.decryptFromStorage(it).linkString == linkString }
    }

    suspend fun updateMessage(message: ChatMessage) {
        val stored = ChatMessageCrypto.encryptForStorage(message.requireWritableBody())
        chatMessageDao.updateMessage(stored)
        rememberCallLink(message, stored)
    }

    suspend fun sendMessageAndGetId(message: ChatMessage): Long {
        val stored = ChatMessageCrypto.encryptForStorage(message.requireWritableBody())
        val id = chatMessageDao.insertMessage(stored)
        rememberCallLink(message, stored)
        return id
    }
}
