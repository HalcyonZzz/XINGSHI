package com.xingshi.ai.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

 
object ChatDetailSettingsDataStoreProvider {
    private const val NAME = "chat_detail_settings"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = NAME)

    fun get(context: Context): DataStore<Preferences> = AccountDataStores.get(context, NAME)
}
