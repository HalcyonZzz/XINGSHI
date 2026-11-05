package com.xingshi.ai.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthTokenStore(context: Context) : com.xingshi.ai.domain.BackendSessionStore {
    var onSessionCleared: (() -> Unit)? = null
    private val prefs: SharedPreferences
    private val legacyPrefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyPlaintextPrefs()
    }

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var userNickname: String?
        get() = prefs.getString(KEY_USER_NICKNAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NICKNAME, value).apply()

    var userAvatar: String?
        get() = prefs.getString(KEY_USER_AVATAR, null)
        set(value) = prefs.edit().putString(KEY_USER_AVATAR, value).apply()

    @Synchronized
    override fun current(): com.xingshi.ai.domain.BackendSession? {
        val environment = prefs.getString("backend_environment", null) ?: return null
        val userId = prefs.getString("backend_user_id", null) ?: return null
        val access = authToken?.takeIf { it.isNotBlank() } ?: return null
        return com.xingshi.ai.domain.BackendSession(environment, userId,
            prefs.getString("backend_session_id", "").orEmpty(), access, refreshToken.orEmpty(),
            prefs.getString("access_expires_at", "").orEmpty(), prefs.getString("session_expires_at", "").orEmpty(),
            userNickname.orEmpty(), prefs.getLong("connection_generation", 0))
    }

    @Synchronized
    fun saveSession(session: com.xingshi.ai.domain.BackendSession) {
        check(prefs.edit().putString("backend_environment", session.environment)
            .putString("backend_user_id", session.userId).putString("backend_session_id", session.sessionId)
            .putString(KEY_AUTH_TOKEN, session.accessToken).putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString("access_expires_at", session.accessExpiresAt).putString("session_expires_at", session.sessionExpiresAt)
            .putString(KEY_USER_NICKNAME, session.nickname).putLong("connection_generation", session.generation).commit()) { "无法安全保存登录状态" }
    }

    fun nextGeneration(): Long = prefs.getLong("connection_generation", 0) + 1

    @Synchronized
    override fun replace(expectedGeneration: Long, session: com.xingshi.ai.domain.BackendSession): Boolean {
        if (current()?.generation != expectedGeneration) return false
        saveSession(session)
        return true
    }

    @Synchronized
    override fun clear(expectedGeneration: Long) {
        if (current()?.generation == expectedGeneration) clear()
    }

    fun clear() {
        val generation = nextGeneration()
        prefs.edit().clear().putLong("connection_generation", generation).commit()
        onSessionCleared?.invoke()
        legacyPrefs.edit().clear().apply()
    }

    private fun migrateLegacyPlaintextPrefs() {
        if (prefs.contains(KEY_AUTH_TOKEN) || prefs.contains(KEY_REFRESH_TOKEN)) {
            legacyPrefs.edit().clear().apply()
            return
        }

        val legacyAuthToken = legacyPrefs.getString(KEY_AUTH_TOKEN, null)
        val legacyRefreshToken = legacyPrefs.getString(KEY_REFRESH_TOKEN, null)
        val legacyUserAvatar = legacyPrefs.getString(KEY_USER_AVATAR, null)

        if (legacyAuthToken.isNullOrBlank() && legacyRefreshToken.isNullOrBlank()) {
            legacyPrefs.edit().clear().apply()
            return
        }

        prefs.edit()
            .putString(KEY_AUTH_TOKEN, legacyAuthToken)
            .putString(KEY_REFRESH_TOKEN, legacyRefreshToken)
            .putString(KEY_USER_AVATAR, legacyUserAvatar)
            .apply()
        legacyPrefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "auth_store_encrypted"
        private const val LEGACY_PREFS_NAME = "auth_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val KEY_USER_AVATAR = "user_avatar"
    }
}
