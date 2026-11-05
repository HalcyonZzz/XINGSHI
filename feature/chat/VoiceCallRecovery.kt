package com.xingshi.ai.feature.chat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.MessageType
import com.xingshi.ai.database.repository.ChatRepository

object VoiceCallRecovery {

    private const val PREFS = "voice_call_state"
    private const val KEY_CALL_ID = "call_id"
    private const val KEY_COMPANION = "companion_id"
    private const val KEY_MODE = "mode"
    private const val KEY_STARTED = "started_at"
    private const val KEY_LAST_ACTIVE = "last_active"

    fun markStarted(context: Context, callId: String, companionId: Long, mode: String, startedAt: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CALL_ID, callId)
            .putLong(KEY_COMPANION, companionId)
            .putString(KEY_MODE, mode)
            .putLong(KEY_STARTED, startedAt)
            .putLong(KEY_LAST_ACTIVE, startedAt)
            .apply()
    }

    fun heartbeat(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_ACTIVE, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun recoverIfNeeded(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val callId = prefs.getString(KEY_CALL_ID, null) ?: return
        val companionId = prefs.getLong(KEY_COMPANION, -1L)
        val mode = prefs.getString(KEY_MODE, "VOICE_CALL") ?: "VOICE_CALL"
        val startedAt = prefs.getLong(KEY_STARTED, 0L)
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE, startedAt)
        if (companionId <= 0L) {
            prefs.edit().clear().apply()
            return
        }
        val link = "call:$callId"
        val database = AppDatabase.getDatabase(context)
        val repository = ChatRepository(database.chatMessageDao())
        val now = System.currentTimeMillis()
        val effectiveEnd = lastActive.takeIf { it >= startedAt } ?: startedAt
        val seconds = if (startedAt > 0L) ((effectiveEnd - startedAt) / 1000).coerceAtLeast(0) else 0L
        val consumer = com.xingshi.ai.domain.ServiceRegistry.get(com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer::class.java)
            ?: return
        consumer.endCall(companionId, callId, mode, (effectiveEnd - startedAt).coerceAtLeast(0))
        if (repository.countCallCardsByLinkString(link) > 0) {
            check(prefs.edit().clear().commit()) { "无法完成通话恢复" }
            return
        }
        val text = "call-card|$mode|$seconds|interrupted"
        repository.sendMessage(
            ChatMessage(
                companionId = companionId,
                content = text,
                isFromUser = false,
                timestamp = now,
                type = MessageType.CALL,
                linkString = link
            )
        )
        check(prefs.edit().clear().commit()) { "无法完成通话恢复" }
    }
}
