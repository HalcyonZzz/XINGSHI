package com.xingshi.ai.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 一个作用域内的单份订阅：初始化、页面进入、手动刷新可以重复调用，
 * 但同一时刻只有一个活跃收集者，刷新不会叠加无限 collect。
 */
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
