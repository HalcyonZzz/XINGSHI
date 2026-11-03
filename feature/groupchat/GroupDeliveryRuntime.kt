package com.xingshi.ai.feature.groupchat

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

 
class GroupDeliveryAccount private constructor(
    val deliveries: GroupPendingDeliveryStore,
    val boundary: SharedPreferences,
    val memberEvents: SharedPreferences,
    private val partition: String
) {
     
    fun isCurrent(): Boolean = partition == com.xingshi.ai.common.AccountStorage.partition

    companion object {
         
        fun capture(preferences: (String) -> SharedPreferences): GroupDeliveryAccount = GroupDeliveryAccount(
            deliveries = GroupPendingDeliveryStore(preferences(GroupPendingDeliveryStore.PREFS)),
            boundary = preferences("group_sync_boundary"),
            memberEvents = preferences("group_member_events"),
            partition = com.xingshi.ai.common.AccountStorage.partition
        )
    }
}

 
object GroupDeliveryRuntime {

    private val lock = Any()
    private var scope = newScope()

    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        synchronized(lock) { scope.launch(block = block) }

    fun stopAccount() = synchronized(lock) {
        scope.coroutineContext[Job]?.cancel()
        scope = newScope()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
