package com.xingshi.ai.database.repository

import com.xingshi.ai.database.dao.ChatMessageDao
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    private val accountPartition = com.xingshi.ai.common.AccountStorage.partition
    private fun cacheKey(companionId: Long) = accountPartition to companionId

    /**
     * 内存缓存：companionId -> 最近一页解密后的消息。
     * HomeViewModel 加载列表时预热，ChatViewModel 进入时先读缓存避免 loading。
     * 放在 companion object 中，所有 ChatRepository 实例共享同一份缓存。
     */
    companion object {
        // 使用 ConcurrentHashMap 替代 mutableMapOf：多个 ViewModel/Worker 并发读写会触发
        // ConcurrentModificationException 或丢失数据。ConcurrentHashMap 提供线程安全读写。
        private val recentCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, List<ChatMessage>>()

        /** 普通可见层唯一规则：通话逐句实录仅服务内部正式上下文，CALL 卡照常显示。 */
        fun isVisibleInNormalUi(message: ChatMessage): Boolean =
            message.type == com.xingshi.ai.database.model.MessageType.CALL ||
                !message.linkString.startsWith("call:")
    }

    // 已写 CALL 卡的明文→实际密文映射：同一 Repository 实例内可免解密回查最近写入，
    // 跨实例/重启仍走下方 CALL 行解密比对，不依赖确定性加密。
    private val writtenCallLinks = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun List<ChatMessage>.visibleOnly(): List<ChatMessage> = filter(ChatRepository::isVisibleInNormalUi)

    /** 缓存预热：由 HomeViewModel 在加载列表时调用 */
    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        recentCache[cacheKey(companionId)] = messages
    }

    /** 读取缓存（可能为 null），ChatViewModel 进入时先用它做初始数据 */
    fun getCachedRecent(companionId: Long): List<ChatMessage>? = recentCache[cacheKey(companionId)]

    // --- 获取最近一页消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.visibleOnly().reversed() }
            .onEach { decrypted -> recentCache[cacheKey(companionId)] = decrypted }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getMessagesBefore(companionId, beforeTimestamp, beforeId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.visibleOnly().reversed() }

    data class VisibleMessages(val messages: List<ChatMessage>, val hasMore: Boolean)

    /**
     * 过滤感知分页：隐藏实录不占可见页名额，也不把“整页被隐藏”误判为已到底。
     * 原始游标仍按数据库 id/时间推进，返回按时间正序的可见消息。
     */
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
        // hasMore 必须按“实际交付页的最旧一条”判断；累积扫描可能多取到更旧可见行，
        // 用累积末尾会把尚未交付的可见行误判为已到底。
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
        // hasMore 必须按“实际交付页的最旧一条”判断；累积扫描可能多取到更旧可见行，
        // 用累积末尾会把尚未交付的可见行误判为已到底。
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
                // 最新 20 行可能全是隐藏实录：继续向前找可见预览，避免列表预览变空。
                if (found == null && list.size == 20) {
                    found = getVisibleRecent(companionId, 1).messages.lastOrNull()
                }
                found
            }

    suspend fun sendMessage(message: ChatMessage): Long {
        val stored = ChatMessageCrypto.encryptForStorage(message)
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

    /** 补齐旧页与滑动最新页之间的空洞（按 id 区间，正序返回）。 */
    suspend fun getMessagesBetweenIdsSync(companionId: Long, afterId: Long, beforeId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesBetweenIdsSync(companionId, afterId, beforeId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .visibleOnly()

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesForCompanionSync(companionId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun searchMessages(companionId: Long, query: String, limit: Int = 50): List<ChatMessage> {
        if (limit <= 0) return emptyList()
        // 隐藏实录会占满 DAO LIMIT：扩大原始查询直到可见命中补足或原始结果穷尽；
        // 到达查询上限时也必须返回已找到的可见命中，不能把结果丢掉。
        var rawLimit = limit
        while (true) {
            val raw = chatMessageDao.searchMessages(companionId, query, rawLimit)
            val visible = raw.map(ChatMessageCrypto::decryptFromStorage)
                .filter(ChatRepository::isVisibleInNormalUi)
            if (visible.size >= limit || raw.size < rawLimit) return visible.take(limit)
            // 不设固定原始查询上限：隐藏记录无限多时也要继续取到所需可见结果或真正穷尽。
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
        // 密文入参可走原 SQL 等值；明文必须先取 CALL 行解密比对，不能假设存储密文可由明文算出。
        val exactKey = if (linkString.startsWith("enc:v1:")) linkString else writtenCallLinks[linkString]
        if (exactKey != null) {
            val exact = runCatching { chatMessageDao.countCallCardsByLinkString(exactKey) }.getOrDefault(0)
            if (exact > 0) return exact
        }
        val cards = runCatching { chatMessageDao.getCallCardsSync() }.getOrDefault(emptyList())
        return cards.count { ChatMessageCrypto.decryptFromStorage(it).linkString == linkString }
    }

    suspend fun updateMessage(message: ChatMessage) {
        val stored = ChatMessageCrypto.encryptForStorage(message)
        chatMessageDao.updateMessage(stored)
        rememberCallLink(message, stored)
    }

    suspend fun sendMessageAndGetId(message: ChatMessage): Long {
        val stored = ChatMessageCrypto.encryptForStorage(message)
        val id = chatMessageDao.insertMessage(stored)
        rememberCallLink(message, stored)
        return id
    }
}
