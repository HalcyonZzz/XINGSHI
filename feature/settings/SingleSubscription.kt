package com.xingshi.ai.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

 
class SingleSubscription(private val scope: CoroutineScope) {

    private var job: Job? = null

    fun <T> subscribe(flow: Flow<T>, onEach: (T) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch { flow.collect(onEach) }
    }

    fun isCollecting(): Boolean = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
    }
}
