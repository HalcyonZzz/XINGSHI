package com.xingshi.ai.feature.groupchat

import android.app.Application
import android.content.Context
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.GroupMessage
import com.xingshi.ai.database.repository.ChatMessageCrypto
import com.xingshi.ai.database.repository.GroupMessageRepository
import com.xingshi.ai.domain.GroupConversationGateway
import com.xingshi.ai.domain.GroupReplyChunk
import com.xingshi.ai.domain.ServiceRegistry
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 
object GroupReplyReceiver {
    suspend fun saveFormal(app: Application, groupId: Long, companionId: Long, messageId: String, content: String, createdAt: Long, payload: String, authorStableId: String): com.xingshi.ai.database.BackendDeliveryReceipt {
        val db = AppDatabase.getDatabase(app)
        return db.withTransaction {
            db.backendHandoffDao().receipt(messageId) ?: run {
                check(db.chatGroupDao().getGroupById(groupId) != null) { "原群已不存在" }
                val author = db.backendHandoffDao().link(companionId) ?: error("群作者云端身份不可读取")
                val localId = GroupMessageRepository(db.groupMessageDao()).sendMessage(GroupMessage(groupId = groupId, companionId = companionId, content = content,
                    timestamp = createdAt, linkString = "backend-message:$messageId", publishedAuthorName = db.companionDao().getCompanionById(companionId)?.name,
                    publishedAuthorStableId = authorStableId, publishedAuthorInstanceId = author.instanceId))
                com.xingshi.ai.database.BackendDeliveryReceipt(messageId, companionId, localId, ChatMessageCrypto.encrypt(payload)).also { db.backendHandoffDao().insertReceipt(it) }
            }
        }
    }

    private const val PREFS = "group_reply_receiver"
    private const val KEY_HANDLED = "handled_operations"
    private const val MAX_HANDLED = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffers = mutableMapOf<String, StringBuilder>()
     
    private val retryTexts = mutableMapOf<String, String>()
    private val inFlight = mutableSetOf<String>()
    private val handled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val durableReplyLinks = mutableMapOf<Long, MutableSet<String>>()
    private val durableLinksLock = Mutex()
    private val lock = Any()

    @Volatile
    private var started = false
    private val accountJobs = mutableListOf<kotlinx.coroutines.Job>()

    fun stopAccount() {
        synchronized(this) {
            accountJobs.forEach { it.cancel() }; accountJobs.clear(); started = false
            buffers.clear(); retryTexts.clear(); inFlight.clear(); handled.clear(); durableReplyLinks.clear()
        }
    }

    fun start(app: Application) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        handled.addAll(
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_HANDLED, emptySet()).orEmpty()
        )
        accountJobs += scope.launch { consume(GroupReplyHandoff.events, app) }
        
        accountJobs += scope.launch {
            var currentType: Class<*>? = null
            while (true) {
                runCatching {
                    val gateway = ServiceRegistry.get(GroupConversationGateway::class.java)
                    if (gateway != null && gateway.javaClass != currentType) {
                        currentType = gateway.javaClass
                        launch { consume(gateway.replyEvents, app) }
                        
                        launch { GroupConversationSync.retryPending(app) }
                    }
                }
                delay(1_000L)
            }
        }
    }

    private suspend fun consume(events: Flow<GroupReplyChunk>, app: Application) {
        events.collect { chunk ->
            val key = if (chunk.operationId.isNotBlank()) {
                "${chunk.groupId}:op:${chunk.operationId}"
            } else {
                "${chunk.groupId}:${chunk.sourceMessageId}:${chunk.targetCompanionId}:"
            }
            if (key in handled) return@collect
            if (!chunk.terminal) {
                synchronized(lock) {
                    if (retryTexts[key] == null) buffers.getOrPut(key) { StringBuilder() }.append(chunk.text)
                }
                return@collect
            }
            synchronized(lock) {
                if (!inFlight.add(key)) return@collect
            }
            var fullForRetry: String? = null
            try {
                val full = synchronized(lock) {
                    val retry = retryTexts[key]
                    if (retry != null) {
                        retry
                    } else {
                        val buffer = buffers.remove(key)
                        buildString {
                            buffer?.let { append(it) }
                            append(chunk.text)
                        }.trim()
                    }
                }
                fullForRetry = full
                if (full.isBlank()) {
                    
                    markHandled(app, key)
                    return@collect
                }
                val database = AppDatabase.getDatabase(app)
                if (database.chatGroupDao().getGroupById(chunk.groupId) == null) return@collect
                val linkString = "group-reply:${chunk.operationId}"
                val inserted = durableLinksLock.withLock {
                    try {
                        val saved = database.withTransaction {
                            val known = durableReplyLinks(database, chunk.groupId)
                            if (chunk.operationId.isNotBlank() && linkString in known) {
                                false
                            } else {
                                GroupMessageRepository(database.groupMessageDao()).sendMessage(
                                    GroupMessage(
                                        groupId = chunk.groupId,
                                        companionId = chunk.targetCompanionId,
                                        content = full,
                                        timestamp = System.currentTimeMillis(),
                                        linkString = linkString
                                    )
                                )
                                true
                            }
                        }
                        if (saved && chunk.operationId.isNotBlank()) {
                            durableReplyLinks.getValue(chunk.groupId).add(linkString)
                        }
                        saved
                    } catch (e: Exception) {
                        durableReplyLinks.remove(chunk.groupId)
                        throw e
                    }
                }
                synchronized(lock) { retryTexts.remove(key) }
                markHandled(app, key)
                if (!inserted) SecureLog.i("GroupReplyReceiver", "Duplicate group reply ignored: $linkString")
            } catch (e: Exception) {
                
                if (fullForRetry != null) synchronized(lock) { retryTexts[key] = fullForRetry }
                SecureLog.e("GroupReplyReceiver", "save group reply failed", e)
            } finally {
                synchronized(lock) { inFlight.remove(key) }
            }
        }
    }

    private suspend fun durableReplyLinks(
        database: AppDatabase,
        groupId: Long
    ): MutableSet<String> {
        durableReplyLinks[groupId]?.let { return it }
        val loaded = database.groupMessageDao().getMessagesForGroupSync(groupId)
            .mapNotNull { stored ->
                runCatching { ChatMessageCrypto.decryptFromStorage(stored).linkString }.getOrNull()
            }
            .filter { it.startsWith("group-reply:") }
            .toMutableSet()
        return loaded.also { durableReplyLinks[groupId] = it }
    }

    private fun markHandled(app: Application, key: String) {
        handled.add(key)
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = handled.toCollection(java.util.LinkedHashSet())
        while (snapshot.size > MAX_HANDLED) snapshot.remove(snapshot.first())
        preferences.edit().putStringSet(KEY_HANDLED, snapshot).commit()
    }
}
