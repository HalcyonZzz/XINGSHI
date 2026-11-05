package com.xingshi.ai.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsStore(context: Context) {

    private val dataStore = AccountDataStores.get(context, "app_settings")

    private companion object {
        private val CONTEXT_LIMIT_KEY = intPreferencesKey("context_message_limit")
        private const val DEFAULT_CONTEXT_LIMIT = 50
        private const val MIN_CONTEXT_LIMIT = 1
        private const val MAX_CONTEXT_LIMIT = 10000

        private val INNER_THOUGHT_ENABLED_KEY = booleanPreferencesKey("inner_thought_enabled")
        private const val DEFAULT_INNER_THOUGHT_ENABLED = false

        private val SHOW_REASONING_KEY = booleanPreferencesKey("show_reasoning")
        private const val DEFAULT_SHOW_REASONING = false
    }

    val contextLimitFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CONTEXT_LIMIT_KEY]?.coerceIn(MIN_CONTEXT_LIMIT, MAX_CONTEXT_LIMIT) ?: DEFAULT_CONTEXT_LIMIT
    }

    suspend fun setContextLimit(limit: Int) {
        dataStore.edit { prefs ->
            prefs[CONTEXT_LIMIT_KEY] = limit.coerceIn(MIN_CONTEXT_LIMIT, MAX_CONTEXT_LIMIT)
        }
    }

    val innerThoughtEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[INNER_THOUGHT_ENABLED_KEY] ?: DEFAULT_INNER_THOUGHT_ENABLED
    }

    suspend fun getInnerThoughtEnabled(): Boolean = innerThoughtEnabledFlow.first()

    suspend fun setInnerThoughtEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[INNER_THOUGHT_ENABLED_KEY] = enabled }
    }

    suspend fun getShowReasoning(): Boolean = dataStore.data.first()[SHOW_REASONING_KEY] ?: DEFAULT_SHOW_REASONING
}
