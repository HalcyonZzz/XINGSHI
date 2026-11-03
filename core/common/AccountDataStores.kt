package com.xingshi.ai.common

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import java.io.File

 
object AccountDataStores {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun get(context: Context, name: String): DataStore<Preferences> =
        stores.getOrPut("${AccountStorage.partition}:$name") {
            val path = File(context.applicationContext.filesDir, "datastore/$name.preferences_pb")
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { path })
        }
    @Synchronized fun close() { scope.cancel(); stores.clear(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
