package com.xingshi.ai.plugins

import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.plugins.api.PluginReport
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

fun interface ChatSendEntry {
    suspend fun submit(companionId: Long, originalText: String, mergedText: String): Boolean

    suspend fun submitScoped(
        companionId: Long,
        originalText: String,
        mergedText: String,
        request: ChannelRequestRef?
    ): Boolean = submit(companionId, originalText, mergedText)

    suspend fun submitExisting(companionId: Long, originalText: String, mergedText: String,
        request: ChannelRequestRef?, localMessageId: Long): Boolean = submitScoped(companionId, originalText, mergedText, request)
}

class PluginSendGuard(private val wanted: () -> Boolean) : AbstractCoroutineContextElement(PluginSendGuard) {
    private val lock = Any()

    private var accepted = false

    private var abandoned = false

    private var revoked = false

    fun <T : Any> accept(enqueue: () -> T?): T? = synchronized(lock) {
        if (revoked || abandoned || !wanted()) return null
        enqueue()?.also { accepted = true }
    }

    fun stillWanted(): Boolean = synchronized(lock) { !revoked && !abandoned && wanted() }

    fun markAccepted(): Boolean = synchronized(lock) {
        if (revoked || abandoned || !wanted()) return false
        accepted = true
        true
    }

    fun isAccepted(): Boolean = synchronized(lock) { accepted }

    fun revoke(): Boolean = synchronized(lock) {
        revoked = true
        if (accepted) return true
        abandoned = true
        false
    }

    fun settle(): Boolean = revoke()

    companion object Key : CoroutineContext.Key<PluginSendGuard>
}

private const val PORT_CONFIRM_TIMEOUT_MS = 10_000L

data class PluginOutcome(
    val executionId: String,
    val companionId: Long,
    val pluginId: String,
    val pluginName: String,
    val report: PluginReport,
    val originalText: String,
    val mergedText: String,
    val submitted: Boolean,
    val message: String?,
    val createdAt: Long,
    val localMessageId: Long? = null
)

class PluginHandoff(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val delivered = mutableSetOf<String>()
    private val ports = mutableMapOf<String, CountDownLatch>()

    fun merge(pluginName: String, originalText: String, reportText: String): String {
        val reference = "参考资料（插件：$pluginName）：\n$reportText"
        return if (originalText.isBlank()) reference else "$originalText\n\n$reference"
    }

    suspend fun deliver(
        executionId: String,
        companionId: Long,
        pluginId: String,
        pluginName: String,
        originalText: String,
        report: PluginReport,
        entry: ChatSendEntry?,
        isActive: () -> Boolean,
        stop: CompletableDeferred<Unit>? = null,
        request: ChannelRequestRef? = null,
        localMessageId: Long? = null
    ): PluginOutcome {
        val successText = (report as? PluginReport.Success)?.text
        val merged = if (successText != null) merge(pluginName, originalText, successText) else ""
        val base = PluginOutcome(
            executionId = executionId,
            companionId = companionId,
            pluginId = pluginId,
            pluginName = pluginName,
            report = report,
            originalText = originalText,
            mergedText = merged,
            submitted = false,
            message = null,
            createdAt = now(),
            localMessageId = localMessageId
        )
        if (successText == null) {
            val reason = when (report) {
                is PluginReport.Failure -> report.message
                else -> "执行已取消"
            }
            return base.copy(message = reason)
        }
        if (!isActive()) {
            return base.copy(report = PluginReport.Cancelled, mergedText = "", message = "执行已取消")
        }
        if (entry == null) return base.copy(message = "对话发送口未接入，结果已保留")
        if (!isActive()) {
            return base.copy(report = PluginReport.Cancelled, mergedText = "", message = "执行已取消")
        }
        if (isDelivered(executionId)) return base.copy(message = "结果已提交")
        return try {
            when (submitPort(executionId, entry, companionId, originalText, merged, stop, request, localMessageId)) {
                null -> base.copy(report = PluginReport.Cancelled, mergedText = "", message = "执行已取消")
                true -> {
                    markDelivered(executionId)
                    base.copy(submitted = true)
                }
                else -> base.copy(message = "发送口未接受，可重试")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markDelivered(executionId)
            base.copy(submitted = true, message = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun retry(
        outcome: PluginOutcome,
        entry: ChatSendEntry?,
        stop: CompletableDeferred<Unit>? = null,
        request: ChannelRequestRef? = null
    ): PluginOutcome {
        if (outcome.submitted) return outcome
        if (isDelivered(outcome.executionId)) return outcome.copy(submitted = true, message = null)
        if (entry == null) return outcome.copy(submitted = false, message = "对话发送口未接入，结果已保留")
        if (outcome.mergedText.isBlank()) {
            return outcome.copy(submitted = false, message = outcome.message ?: "没有可发送的内容")
        }
        return try {
            when (
                submitPort(
                    outcome.executionId,
                    entry,
                    outcome.companionId,
                    outcome.originalText,
                    outcome.mergedText,
                    stop,
                    request,
                    outcome.localMessageId
                )
            ) {
                null -> outcome.copy(submitted = false, message = "执行已取消")
                true -> {
                    markDelivered(outcome.executionId)
                    outcome.copy(submitted = true, message = null)
                }
                else -> outcome.copy(submitted = false, message = "发送口未接受，可重试")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome.copy(submitted = false, message = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun submitOriginal(companionId: Long, originalText: String, entry: ChatSendEntry?): Boolean {
        if (entry == null) return false
        if (originalText.isBlank()) return false
        return try {
            entry.submit(companionId, originalText, originalText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun isDelivered(executionId: String): Boolean = synchronized(delivered) { delivered.contains(executionId) }

    fun isPortBusy(executionId: String): Boolean = synchronized(ports) { ports.containsKey(executionId) }

    private suspend fun submitPort(
        executionId: String,
        entry: ChatSendEntry,
        companionId: Long,
        originalText: String,
        mergedText: String,
        stop: CompletableDeferred<Unit>?,
        request: ChannelRequestRef?,
        localMessageId: Long?
    ): Boolean? {
        val latch = CountDownLatch(1)
        synchronized(ports) { ports[executionId] = latch }
        val guard = PluginSendGuard { stop?.isCompleted != true }
        val portScope = CoroutineScope(coroutineContext + SupervisorJob())
        val port = portScope.async(guard) {
            if (localMessageId != null) entry.submitExisting(companionId, originalText, mergedText, request, localMessageId)
            else entry.submitScoped(companionId, originalText, mergedText, request)
        }
        stop?.invokeOnCompletion { port.cancel() }
        return try {
            val verdict = withTimeoutOrNull(PORT_CONFIRM_TIMEOUT_MS) {
                if (stop == null) port.await() else select<Boolean?> { port.onAwait { it }; stop.onAwait { null } }
            }
            if (verdict == null) {
                if (guard.settle()) true else null
            } else {
                verdict
            }
        } catch (e: CancellationException) {
            when {
                guard.settle() -> true
                stop?.isCompleted == true -> null
                else -> {
                    coroutineContext.ensureActive()
                    throw e
                }
            }
        } finally {
            port.cancel()
            portScope.cancel()
            synchronized(ports) { ports.remove(executionId, latch) }
            latch.countDown()
            guard.settle()
        }
    }

    private fun markDelivered(executionId: String) {
        synchronized(delivered) { delivered.add(executionId) }
    }
}
