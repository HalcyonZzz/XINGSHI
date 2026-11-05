package com.xingshi.ai.feature.chat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xingshi.ai.common.ChatDetailSettingsDataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CompanionChatDetailSettings(
    val backgroundKey: String? = null,
    val useGlobalBackground: Boolean = true,
    val blocked: Boolean = false,
    val wechatSyncEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

class ChatDetailSettingsStore(context: Context) {

    private val dataStore = ChatDetailSettingsDataStoreProvider.get(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val SETTINGS_MAP_KEY = stringPreferencesKey("companion_chat_detail_settings_map")
    }

    val settingsMapFlow: Flow<Map<Long, CompanionChatDetailSettings>> = dataStore.data.map { prefs ->
        prefs[SETTINGS_MAP_KEY]?.let(::decodeSettingsMap) ?: emptyMap()
    }

    fun settingsFlow(companionId: Long): Flow<CompanionChatDetailSettings> =
        settingsMapFlow.map { it[companionId] ?: CompanionChatDetailSettings() }

    suspend fun updateSettings(companionId: Long, transform: (CompanionChatDetailSettings) -> CompanionChatDetailSettings) {
        dataStore.edit { prefs ->
            val currentMap = prefs[SETTINGS_MAP_KEY]?.let(::decodeSettingsMap)?.toMutableMap() ?: mutableMapOf()
            val currentSettings = currentMap[companionId] ?: CompanionChatDetailSettings()
            currentMap[companionId] = transform(currentSettings).copy(updatedAt = System.currentTimeMillis())
            prefs[SETTINGS_MAP_KEY] = json.encodeToString(currentMap)
        }
    }

    private fun decodeSettingsMap(raw: String): Map<Long, CompanionChatDetailSettings> =
        runCatching { json.decodeFromString<Map<Long, CompanionChatDetailSettings>>(raw) }.getOrElse { emptyMap() }
}
