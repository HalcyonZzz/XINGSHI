package com.xingshi.ai.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object OptionalCapabilityCall {

    sealed interface Outcome<out T> {
        data class Completed<T>(val value: T) : Outcome<T>

        data object Unavailable : Outcome<Nothing>

        data object Cancelled : Outcome<Nothing>
    }

    interface Registry {
        fun trackOptionalExecution(pluginId: String, version: String, job: Job): Boolean
        fun untrackOptionalExecution(pluginId: String, job: Job)
    }

    suspend fun <T> invoke(
        manager: Registry,
        capabilityId: String?,
        version: String?,
        call: suspend () -> T
    ): Outcome<T> {
        val pluginId = capabilityId ?: return Outcome.Unavailable
        val token = version ?: return Outcome.Unavailable
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, token, gate)) return Outcome.Unavailable
        return try {
            when (val outcome = await(gate) { call() }) {
                is Outcome.Completed -> outcome
                else -> Outcome.Cancelled
            }
        } finally {
            manager.untrackOptionalExecution(pluginId, gate)
            gate.complete()
        }
    }

    suspend fun <T> await(gate: Job, call: suspend () -> T): Outcome<T> = coroutineScope {
        val task = async(context = gate, start = CoroutineStart.LAZY) { call() }
        task.start()
        try {
            Outcome.Completed(task.await())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Cancelled
        }
    }

    suspend fun callJob(): Job = checkNotNull(currentCoroutineContext()[Job]) {
        "可选能力调用体必须运行在已登记的资格门闩下"
    }
}
