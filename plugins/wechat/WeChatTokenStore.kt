package com.xingshi.ai.feature.wechat.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
@SerialName("A0")
data class A0(
    val botToken: String,
    val ilinkBotId: String,
    val ilinkUserId: String,
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    val accountId: String = "default"
)

/**
 * 插件持久化：必须跨插件类加载器安全。
 * DataStore 的活跃实例无法随插件重载而关闭，重载后会因同一文件出现两个实例而崩溃；
 * 这里改用平台 SharedPreferences（进程级单例，与类加载器无关）。
 */
class WeChatTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStore: WeChatSecureStore by lazy { WeChatSecureStore(context.applicationContext) }
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

    // ==================== Account (encrypted) ====================

    val accountFlow: Flow<A0?> = observe {
        secureStore.getAccountJson()?.let { json.decodeFromString(it) }
    }

    suspend fun getAccount(): A0? =
        secureStore.getAccountJson()?.let { json.decodeFromString(it) }

    suspend fun saveAccount(account: A0) {
        secureStore.setAccountJson(json.encodeToString(account))
        bump()
    }

    /** 卸载清理：只清本插件自己的登录材料、令牌与映射，不触碰宿主聊天/附件。 */
    fun clearAll() {
        // 先清平台可见的映射/设置，再清加密凭据；后者失败也不能让前者残留
        prefs.edit().clear().apply()
        bump()
        runCatching { secureStore.clearAll() }
    }

    suspend fun clearAccount() {
        secureStore.clearAccount()
        secureStore.clearContextTokens()
        writePrefs { it.remove(KEY_CURSOR) }
    }

    // ==================== Auto Reply ====================

    val autoReplyFlow: Flow<Boolean> = observe { prefs.getBoolean(KEY_AUTO_REPLY, true) }

    suspend fun getAutoReply(): Boolean = autoReplyFlow.first()

    suspend fun setAutoReply(enabled: Boolean): Unit = writePrefs { it.putBoolean(KEY_AUTO_REPLY, enabled) }

    // ==================== Notify Enabled ====================

    val notifyEnabledFlow: Flow<Boolean> = observe { prefs.getBoolean(KEY_NOTIFY_ENABLED, true) }

    suspend fun getNotifyEnabled(): Boolean = notifyEnabledFlow.first()

    suspend fun setNotifyEnabled(enabled: Boolean): Unit = writePrefs { it.putBoolean(KEY_NOTIFY_ENABLED, enabled) }

    // ==================== Forward Enabled ====================

    val forwardEnabledFlow: Flow<Boolean> = observe { prefs.getBoolean(KEY_FORWARD_ENABLED, true) }

    suspend fun getForwardEnabled(): Boolean = forwardEnabledFlow.first()

    suspend fun setForwardEnabled(enabled: Boolean): Unit = writePrefs { it.putBoolean(KEY_FORWARD_ENABLED, enabled) }

    // ==================== Default Companion ====================

    val defaultCompanionIdFlow: Flow<Long?> = observe {
        if (prefs.contains(KEY_DEFAULT_COMPANION_ID)) prefs.getLong(KEY_DEFAULT_COMPANION_ID, 0L) else null
    }

    suspend fun getDefaultCompanionId(): Long? = defaultCompanionIdFlow.first()

    suspend fun setDefaultCompanionId(companionId: Long?): Unit = writePrefs { editor ->
        if (companionId != null) editor.putLong(KEY_DEFAULT_COMPANION_ID, companionId)
        else editor.remove(KEY_DEFAULT_COMPANION_ID)
    }

    // ==================== Custom Bot Name ====================

    val customBotNameFlow: Flow<String?> = observe { prefs.getString(KEY_CUSTOM_BOT_NAME, null) }

    suspend fun getCustomBotName(): String? = customBotNameFlow.first()

    suspend fun setCustomBotName(name: String?): Unit = writePrefs { editor ->
        if (name != null) editor.putString(KEY_CUSTOM_BOT_NAME, name)
        else editor.remove(KEY_CUSTOM_BOT_NAME)
    }

    // ==================== User -> Companion Mapping ====================

    private fun readUserCompanionMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_USER_COMPANION_MAP, null) ?: return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val userCompanionMapFlow: Flow<Map<String, Long>> = observe { readUserCompanionMap() }

    suspend fun getCompanionIdForWechatUser(wechatUserId: String): Long? {
        val map = readUserCompanionMap()
        return map[wechatUserId]
    }

    suspend fun setCompanionIdForWechatUser(wechatUserId: String, companionId: Long) {
        val current = readUserCompanionMap().toMutableMap()
        current[wechatUserId] = companionId
        writePrefs { it.putString(KEY_USER_COMPANION_MAP, json.encodeToString(current)) }
    }

    suspend fun removeWechatUserMapping(wechatUserId: String) {
        val current = readUserCompanionMap().toMutableMap()
        current.remove(wechatUserId)
        writePrefs { it.putString(KEY_USER_COMPANION_MAP, json.encodeToString(current)) }
    }

    suspend fun getAllWechatUserMappings(): Map<String, Long> = readUserCompanionMap()

    suspend fun getWechatUserIdsForCompanionId(companionId: Long): List<String> {
        return readUserCompanionMap().filter { it.value == companionId }.keys.toList()
    }

    // ==================== Context Tokens (encrypted) ====================
    // Key format: "accountId:userId" -> contextToken

    private suspend fun getContextTokens(): Map<String, String> =
        secureStore.getContextTokensJson()?.let {
            try { json.decodeFromString(it) } catch (_: Exception) { emptyMap() }
        } ?: emptyMap()

    private suspend fun saveContextTokens(tokens: Map<String, String>) {
        secureStore.setContextTokensJson(json.encodeToString(tokens))
    }

    suspend fun getContextToken(accountId: String, userId: String): String? {
        return getContextTokens()["$accountId:$userId"]
    }

    suspend fun saveContextToken(accountId: String, userId: String, token: String) {
        val current = getContextTokens().toMutableMap()
        current["$accountId:$userId"] = token
        saveContextTokens(current)
    }

    suspend fun getContextTokens(accountId: String): Map<String, String> {
        val prefix = "$accountId:"
        return getContextTokens()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
    }

    // ==================== getUpdates Cursor ====================

    val cursorFlow: Flow<String> = observe { prefs.getString(KEY_CURSOR, "").orEmpty() }

    suspend fun getCursor(): String = cursorFlow.first()

    suspend fun saveCursor(cursor: String): Unit = writePrefs { it.putString(KEY_CURSOR, cursor) }

    // ==================== Helper ====================

    suspend fun isLoggedIn(): Boolean = getAccount() != null

    private companion object {
        const val PREFS_NAME = "wechat_prefs"
        const val KEY_CURSOR = "wechat_cursor"
        const val KEY_AUTO_REPLY = "wechat_auto_reply"
        const val KEY_NOTIFY_ENABLED = "wechat_notify_enabled"
        const val KEY_FORWARD_ENABLED = "wechat_forward_enabled"
        const val KEY_DEFAULT_COMPANION_ID = "wechat_default_companion_id"
        const val KEY_USER_COMPANION_MAP = "wechat_user_companion_map"
        const val KEY_CUSTOM_BOT_NAME = "wechat_custom_bot_name"
    }
}
