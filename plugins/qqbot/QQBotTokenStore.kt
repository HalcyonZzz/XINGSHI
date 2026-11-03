package com.xingshi.ai.feature.qqbot.data

import android.content.Context
import android.content.SharedPreferences
import com.xingshi.ai.feature.qqbot.data.model.QQBotAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

 
class QQBotTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStore: QQBotSecureStore by lazy { QQBotSecureStore(context.applicationContext) }
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0)

    private fun bump() {
        revision.value += 1
    }

    private fun <T> observe(read: () -> T): Flow<T> = revision.map { read() }

    private fun writePrefs(block: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.apply()
        bump()
    }

    val accountFlow: Flow<QQBotAccount?> = observe {
        secureStore.getAccountJson()?.let { json.decodeFromString(it) }
    }

    suspend fun getAccount(): QQBotAccount? =
        secureStore.getAccountJson()?.let { json.decodeFromString(it) }

    suspend fun saveAccount(account: QQBotAccount) {
        secureStore.setAccountJson(json.encodeToString(account))
        bump()
    }

     
    fun clearAll() {
        
        prefs.edit().clear().apply()
        bump()
        runCatching { secureStore.clearAll() }
    }

    suspend fun clearAccount() {
        secureStore.clearAccount()
        writePrefs {
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_TOKEN_EXPIRE_AT)
            it.remove(KEY_SESSION_ID)
            it.remove(KEY_LAST_SEQUENCE)
        }
    }

    suspend fun isLoggedIn(): Boolean = getAccount() != null

    val autoReplyFlow: Flow<Boolean> = observe { prefs.getBoolean(KEY_AUTO_REPLY, true) }
    suspend fun getAutoReply(): Boolean = autoReplyFlow.first()
    suspend fun setAutoReply(enabled: Boolean): Unit = writePrefs { it.putBoolean(KEY_AUTO_REPLY, enabled) }

    val notifyEnabledFlow: Flow<Boolean> = observe { prefs.getBoolean(KEY_NOTIFY_ENABLED, true) }
    suspend fun getNotifyEnabled(): Boolean = notifyEnabledFlow.first()
    suspend fun setNotifyEnabled(enabled: Boolean): Unit = writePrefs { it.putBoolean(KEY_NOTIFY_ENABLED, enabled) }

    val defaultCompanionIdFlow: Flow<Long?> = observe {
        if (prefs.contains(KEY_DEFAULT_COMPANION_ID)) prefs.getLong(KEY_DEFAULT_COMPANION_ID, 0L) else null
    }
    suspend fun getDefaultCompanionId(): Long? = defaultCompanionIdFlow.first()
    suspend fun setDefaultCompanionId(companionId: Long?): Unit = writePrefs { editor ->
        if (companionId != null) editor.putLong(KEY_DEFAULT_COMPANION_ID, companionId)
        else editor.remove(KEY_DEFAULT_COMPANION_ID)
    }

    val customBotNameFlow: Flow<String?> = observe { prefs.getString(KEY_CUSTOM_BOT_NAME, null) }
    suspend fun getCustomBotName(): String? = customBotNameFlow.first()
    suspend fun setCustomBotName(name: String?): Unit = writePrefs { editor ->
        if (name != null) editor.putString(KEY_CUSTOM_BOT_NAME, name)
        else editor.remove(KEY_CUSTOM_BOT_NAME)
    }

    suspend fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    suspend fun setAccessToken(token: String?): Unit = writePrefs { editor ->
        if (token != null) editor.putString(KEY_ACCESS_TOKEN, token)
        else editor.remove(KEY_ACCESS_TOKEN)
    }

    suspend fun getTokenExpireAt(): Long = prefs.getLong(KEY_TOKEN_EXPIRE_AT, 0L)
    suspend fun setTokenExpireAt(timestamp: Long): Unit = writePrefs { it.putLong(KEY_TOKEN_EXPIRE_AT, timestamp) }

    suspend fun getSessionId(): String? = prefs.getString(KEY_SESSION_ID, null)
    suspend fun setSessionId(sessionId: String?): Unit = writePrefs { editor ->
        if (sessionId != null) editor.putString(KEY_SESSION_ID, sessionId)
        else editor.remove(KEY_SESSION_ID)
    }

    suspend fun getLastSequence(): Long = prefs.getLong(KEY_LAST_SEQUENCE, 0L)
    suspend fun setLastSequence(seq: Long): Unit = writePrefs { it.putLong(KEY_LAST_SEQUENCE, seq) }

    private fun readUserCompanionMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_USER_COMPANION_MAP, null) ?: return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val userCompanionMapFlow: Flow<Map<String, Long>> = observe { readUserCompanionMap() }

    suspend fun getCompanionIdForQQUser(qqUserId: String): Long? = readUserCompanionMap()[qqUserId]

    suspend fun setCompanionIdForQQUser(qqUserId: String, companionId: Long) {
        val current = readUserCompanionMap().toMutableMap()
        current[qqUserId] = companionId
        writePrefs { it.putString(KEY_USER_COMPANION_MAP, json.encodeToString(current)) }
    }

    suspend fun removeQQUserMapping(qqUserId: String) {
        val current = readUserCompanionMap().toMutableMap()
        current.remove(qqUserId)
        writePrefs { it.putString(KEY_USER_COMPANION_MAP, json.encodeToString(current)) }
    }

    suspend fun getAllQQUserMappings(): Map<String, Long> = readUserCompanionMap()

    private companion object {
        const val PREFS_NAME = "qqbot_prefs"
        const val KEY_AUTO_REPLY = "qqbot_auto_reply"
        const val KEY_NOTIFY_ENABLED = "qqbot_notify_enabled"
        const val KEY_DEFAULT_COMPANION_ID = "qqbot_default_companion_id"
        const val KEY_USER_COMPANION_MAP = "qqbot_user_companion_map"
        const val KEY_CUSTOM_BOT_NAME = "qqbot_custom_bot_name"
        const val KEY_ACCESS_TOKEN = "qqbot_access_token"
        const val KEY_TOKEN_EXPIRE_AT = "qqbot_token_expire_at"
        const val KEY_SESSION_ID = "qqbot_session_id"
        const val KEY_LAST_SEQUENCE = "qqbot_last_sequence"
    }
}
