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

    /**
     * 带原请求归属的提交：插件结果（含视觉结论）交回对话入口时保留该通道请求，
     * 后续回复按同一请求回原会话。应用级入口可覆盖；页面入口默认忽略归属。
     */
    suspend fun submitScoped(
        companionId: Long,
        originalText: String,
        mergedText: String,
        request: ChannelRequestRef?
    ): Boolean = submit(companionId, originalText, mergedText)

    /** Host-owned original input identity, preserved through media processing and retries. */
    suspend fun submitExisting(companionId: Long, originalText: String, mergedText: String,
        request: ChannelRequestRef?, localMessageId: Long): Boolean = submitScoped(companionId, originalText, mergedText, request)
}

// 入队边界（宿主内部发送合同，不属于插件作者侧 SPI）：框架在调用 ChatSendEntry.submit 时把它挂进
// 协程上下文，宿主必须用 accept { } 真正入队，撤销与实际接受在同一临界区里决出先后：撤销先到，
// accept 返回 null，宿主不得入队；入队先完成，框架不得改判成未提交。入队动作本身必须写在 accept 的
// lambda 里（见《最小接入规范》§8）：先复核再入队是两步，撤销落在中间时仍会迟到入队一次。
class PluginSendGuard(private val wanted: () -> Boolean) : AbstractCoroutineContextElement(PluginSendGuard) {
    private val lock = Any()

    private var accepted = false

    private var abandoned = false

    // 一旦撤销，过去已接受的责任保留，但未来任何 accept 都不再放行。
    private var revoked = false

    // 宿主唯一的入队动作：与撤销互斥，返回 null 表示这次没有入队（撤销先到，或入队动作自己失败如队列满），
    // 两种情况下都不留接受凭据
    fun <T : Any> accept(enqueue: () -> T?): T? = synchronized(lock) {
        if (revoked || abandoned || !wanted()) return null
        enqueue()?.also { accepted = true }
    }

    // 只用于提前放弃这次发送（不入队）：撤销已生效、或框架已经放弃这次等待（超时/调用方取消）时返回 false。
    // 它不是「复核通过就可以稍后入队」的许可——入队必须在 accept 的同一临界区里完成
    fun stillWanted(): Boolean = synchronized(lock) { !revoked && !abandoned && wanted() }

    // 兼容既有两步式宿主的补记：撤销已生效或框架已放弃时返回 false，表示这次接受来晚了、不能算已提交。
    // 新宿主不要用它代替 accept——补记发生在入队之后，补记失败时那次入队已经收不回来
    fun markAccepted(): Boolean = synchronized(lock) {
        if (revoked || abandoned || !wanted()) return false
        accepted = true
        true
    }

    fun isAccepted(): Boolean = synchronized(lock) { accepted }

    // 撤销/挂断给出的结论：与 accept 共用同一把锁；撤销先到则 accept 必须失败，接受先到则保留责任。
    fun revoke(): Boolean = synchronized(lock) {
        revoked = true
        if (accepted) return true
        abandoned = true
        false
    }

    // 框架给出结论：与 accept 互斥，返回这次发送是否已经接受（已接受不伪撤回）
    fun settle(): Boolean = revoke()

    companion object Key : CoroutineContext.Key<PluginSendGuard>
}

// 等宿主给出结论的上限：真实宿主入队前要过内容安全管道（预算 8s），所以这里必须覆盖它，
// 提前判「未提交」会把插件的成功结果换成「执行已取消」，重试也只是让插件再跑一遍；
// 只有宿主完全不响应取消时才等满这个上限，且撤销/卸载另有更短的收尾等待，不会拖住调用方
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
        // 进入发送口前再确认一次：取消发生在两步之间时不再发送
        if (!isActive()) {
            return base.copy(report = PluginReport.Cancelled, mergedText = "", message = "执行已取消")
        }
        // 同一次执行已经交付过就不再进发送口，避免重复消息
        if (isDelivered(executionId)) return base.copy(message = "结果已提交")
        return try {
            when (submitPort(executionId, entry, companionId, originalText, merged, stop, request, localMessageId)) {
                null -> base.copy(report = PluginReport.Cancelled, mergedText = "", message = "执行已取消")
                // 发送口返回 true 即已入队：入队先完成就必须算已提交，不能因为随后到来的取消改判成
                // 未提交，否则会留下可重试的待发记录，重试就变成重复发送
                true -> {
                    markDelivered(executionId)
                    base.copy(submitted = true)
                }
                else -> base.copy(message = "发送口未接受，可重试")
            }
        } catch (e: CancellationException) {
            // 入队前被取消：不能标记已交付，否则重试材料会被丢掉
            throw e
        } catch (e: Exception) {
            // 发送口抛异常时无法判断是否已经入队；按“已尝试提交”处理，避免重试造成重复消息
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
        // 上次交付其实已经入队（只是收尾时没能确认）时不再重发，避免重复消息
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
                    // 重试入队成功与首发一样记交付：同一次执行再也不会被重发
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

    // 发送口是否正在挂起等待（撤销时要等它真正退出，否则调用方会在没收尾的窗口里继续跑）
    fun isPortBusy(executionId: String): Boolean = synchronized(ports) { ports.containsKey(executionId) }

    // 发送口可能长时间挂起（宿主要先做输入校验）：撤销信号一到就取消这次提交并等它给出结论，
    // 以便把「撤销先于入队」与「入队先完成」分开：前者宿主不得入队，后者不得伪撤回
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
        // 宿主按合同在 accept { } 里入队：撤销已生效时 accept 返回 null、宿主不得入队；入队先完成则留下接受凭据
        val guard = PluginSendGuard { stop?.isCompleted != true }
        // 宿主实现可能挂在不响应取消的等待上：这次提交单独起一个作用域，撤销时放弃等待而不是连带卡住调用方
        val portScope = CoroutineScope(coroutineContext + SupervisorJob())
        val port = portScope.async(guard) {
            if (localMessageId != null) entry.submitExisting(companionId, originalText, mergedText, request, localMessageId)
            else entry.submitScoped(companionId, originalText, mergedText, request)
        }
        stop?.invokeOnCompletion { port.cancel() }
        return try {
            val verdict = withTimeoutOrNull(PORT_CONFIRM_TIMEOUT_MS) {
                // 撤销一到就不再等这个宿主：它可能挂在不可取消的等待上，继续等等于把撤销拖成迟到入队；
                // 没有撤销时等它自己给出结论，上限必须覆盖宿主入队前的校验预算
                if (stop == null) port.await() else select<Boolean?> { port.onAwait { it }; stop.onAwait { null } }
            }
            // 超时/撤销也要先看接受凭据：撤销与接受在同一临界区里决出先后，接受在先算已提交，
            // 撤销在先宿主不得再入队；宿主已入队只是回得慢，不能改判成未提交，否则重试会第二次入队
            if (verdict == null) {
                if (guard.settle()) true else null
            } else {
                verdict
            }
        } catch (e: CancellationException) {
            when {
                // 已留下接受凭据：这次发送已经入队 —— 入队先完成就必须算已提交，调用方随后被取消
                // 也不能改判成未提交，否则会留下可重试记录，重试就变成第二次入队
                guard.settle() -> true
                // 撤销已生效：宿主按合同不得入队，材料留给重试
                stop?.isCompleted == true -> null
                // 没有接受凭据：调用方自己被取消时继续上抛（发送口自己的取消也按原样上抛），交给上层保留材料
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
            // 结论已定：宣布放弃这次等待（已接受的除外），宿主若还在 accept 前复核必须看到 false，
            // 不能事后补一次入队——否则框架判「未提交」而宿主实际入了队，重试就重复发送
            guard.settle()
        }
    }

    private fun markDelivered(executionId: String) {
        synchronized(delivered) { delivered.add(executionId) }
    }
}
