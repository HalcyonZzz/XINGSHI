package com.xingshi.ai.plugins

import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.plugins.api.ChannelRequestRef
import com.xingshi.ai.plugins.api.PluginAttachment
import com.xingshi.ai.plugins.api.PluginAttachmentKind
import com.xingshi.ai.plugins.api.PluginChatCard
import com.xingshi.ai.plugins.api.PluginInputAction
import com.xingshi.ai.plugins.api.PluginModel
import com.xingshi.ai.plugins.api.PluginPageContent
import androidx.compose.runtime.Composable
import com.xingshi.ai.plugins.api.PluginPageContext
import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import com.xingshi.ai.plugins.api.PluginRuntime
import com.xingshi.ai.plugins.api.PluginTool
import com.xingshi.ai.plugins.api.PluginToolLoop
import com.xingshi.ai.plugins.api.XingShiPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PluginActionRef(
    val pluginId: String,
    val pluginName: String,
    val action: PluginInputAction
)

data class PluginChatCardRef(
    val pluginId: String,
    val pluginName: String,
    val card: PluginChatCard
)


private const val PORT_UNWIND_TIMEOUT_MS = 250L

private class Execution(val id: String, val thread: Thread) {
    val done = CountDownLatch(1)
}

class PluginManager(
    private val store: PluginStore,
    private val installer: PluginInstaller,
    private val loader: PluginLoader,
    private val catalog: PluginCatalog,
    private val modelProvider: suspend (String, Boolean) -> PluginModel,
    private val visionConfigProvider: (suspend () -> HostModelConfig?)? = null,
    private val handoff: PluginHandoff = PluginHandoff(),
    private val parentClassLoader: () -> ClassLoader,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val records = MutableStateFlow<List<InstalledPluginRecord>>(emptyList())
    private val outcomeMap = MutableStateFlow<Map<Long, PluginOutcome>>(emptyMap())
    private val pluginOutcomeMap = MutableStateFlow<Map<Pair<String, Long>, PluginOutcome>>(emptyMap())
    private val loadErrorMap = MutableStateFlow<Map<String, String>>(emptyMap())
    private val activeMap = MutableStateFlow<Map<String, String>>(emptyMap())
    @Volatile private var accountClosed = false
    private val deletedCompanions = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val loaded = mutableMapOf<String, XingShiPlugin>()
    
    private val loadedVersions = mutableMapOf<String, String>()
    private val sessions = mutableMapOf<Long, ChatSendEntry>()
     
    private var applicationEntry: ChatSendEntry? = null
     
    private val executionRequests = mutableMapOf<String, ChannelRequestRef>()
    private val pendingInputs = mutableMapOf<Long, String>()
    private val pendingActions = mutableMapOf<Long, String>()
    private val requests = mutableMapOf<Pair<String, Long>, PluginRequest>()
    private val executionLock = Any()
    
    
    private val reloadLock = Any()
    
    private val outcomeLock = Any()
    private val retrying = mutableSetOf<Long>()
    
    private val executions = mutableMapOf<Pair<String, Long>, Execution>()
    private val stopSignals = mutableMapOf<Pair<String, Long>, CompletableDeferred<Unit>>()
     
    private val optionalExecutions = mutableMapOf<String, MutableSet<Job>>()
     
    private val selectedCapabilities = mutableMapOf<String, String>()

     
    private val catalogState = MutableStateFlow(PluginCatalogState())
    val marketplace: StateFlow<PluginCatalogState> = catalogState
    private val catalogLock = Mutex()

     
    private val installGates = mutableMapOf<String, CompletableDeferred<PluginInstallResult>>()

    private companion object {
        const val IMAGE_PROMPT =
            "只描述这张图片中实际可见的人物、物体、位置、状态和可读文字；" +
                "模糊、遮挡或无法判断的部分要保留不确定性，不猜身份、不推测画面外的事情。"
    }

    
    
    private var invalidatedGenerations: MutableMap<String, MutableSet<String>>? = null

    private fun invalidationMap(): MutableMap<String, MutableSet<String>> =
        invalidatedGenerations ?: mutableMapOf<String, MutableSet<String>>().also { invalidatedGenerations = it }

    private val selectionFile: File = File(store.rootDir, "capability-selection.properties")

    init {
        
        runCatching {
            if (selectionFile.isFile) {
                selectionFile.readLines().forEach { line ->
                    val index = line.indexOf('=')
                    if (index > 0) selectedCapabilities[line.substring(0, index)] = line.substring(index + 1)
                }
            }
        }
    }

    private fun persistSelection() {
        runCatching {
            selectionFile.parentFile?.mkdirs()
            selectionFile.writeText(selectedCapabilities.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    init {
        restorePending()
    }

    val installed: StateFlow<List<InstalledPluginRecord>> = records

    val outcomes: StateFlow<Map<Long, PluginOutcome>> = outcomeMap

    
    val pluginOutcomes: StateFlow<Map<Pair<String, Long>, PluginOutcome>> = pluginOutcomeMap

    val loadErrors: StateFlow<Map<String, String>> = loadErrorMap

    val running: StateFlow<Set<String>> =
        activeMap.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val catalogConfigured: Boolean get() = catalog.isConfigured

    fun isInstalled(id: String): Boolean = records.value.any { it.manifest.id == id }

     
    fun loadedPlugin(id: String): XingShiPlugin? = synchronized(reloadLock) { loaded[id] }

    fun installedIds(): List<String> = records.value.map { it.manifest.id }

    
    
    private fun isStillInstalled(id: String): Boolean = installer.isInstalled(id)

    fun rememberInput(companionId: Long, input: String, actionId: String? = null) {
        pendingInputs[companionId] = input
        if (actionId == null) pendingActions.remove(companionId) else pendingActions[companionId] = actionId
    }

    fun pendingInput(companionId: Long): String = pendingInputs[companionId].orEmpty()

    fun pendingAction(companionId: Long): String? = pendingActions[companionId]

    fun reload() = synchronized(reloadLock) {
        val all = store.installed()
        val parent = parentClassLoader()
        val errors = mutableMapOf<String, String>()
        
        
        val next = mutableMapOf<String, XingShiPlugin>()
        val nextVersions = mutableMapOf<String, String>()
        for (record in all) {
            val id = record.manifest.id
            val version = record.manifest.version
            val dir = store.packageDir(id)
            
            
            val existing = loaded[id]
            if (existing != null && loadedVersions[id] == version) {
                next[id] = existing
                nextVersions[id] = version
                continue
            }
            val plugin = loader.load(record.manifest, dir, parent)
            if (plugin == null) {
                errors[id] = when {
                    !dir.isDirectory -> "插件包缺失"
                    dir.listFiles { file -> file.isFile && file.name.endsWith(".dex") }.isNullOrEmpty() -> "插件包缺少代码"
                    else -> "插件代码无法加载"
                }
            } else {
                next[id] = plugin
                nextVersions[id] = version
            }
        }
        
        loaded.clear()
        loaded.putAll(next)
        loadedVersions.clear()
        loadedVersions.putAll(nextVersions)
        loadErrorMap.value = errors
        records.value = all
    }

    fun actions(): List<PluginActionRef> = records.value.flatMap { record ->
        val plugin = loaded[record.manifest.id]
        if (plugin == null) {
            emptyList()
        } else {
            runCatching { plugin.inputActions() }.getOrElse { emptyList() }
                .map { PluginActionRef(record.manifest.id, record.manifest.name, it) }
        }
    }

    fun hasPage(pluginId: String): Boolean = runCatching { loaded[pluginId]?.page() != null }.getOrDefault(false)

    fun chatCards(): List<PluginChatCardRef> = records.value.flatMap { record ->
        val plugin = loaded[record.manifest.id]
        if (plugin == null) {
            emptyList()
        } else {
            runCatching { plugin.chatCards() }.getOrElse { emptyList() }
                .map { PluginChatCardRef(record.manifest.id, record.manifest.name, it) }
        }
    }

    private fun <T> capabilitiesOf(type: Class<T>): List<Pair<String, T>> =
        records.value.mapNotNull { record ->
            loaded[record.manifest.id]
                ?.takeIf { type.isInstance(it) }
                ?.let { record.manifest.id to type.cast(it) }
        }.sortedBy { it.first }

     
    fun speechRecognitionPlugins(): List<com.xingshi.ai.plugins.api.SpeechRecognitionPlugin> =
        capabilitiesOf(com.xingshi.ai.plugins.api.SpeechRecognitionPlugin::class.java).map { it.second }

    fun speechRecognitionPluginId(): String? = if (selectedCapabilities.containsKey("stt")) {
        preferredCapabilityId("stt")
    } else {
        capabilitiesOf(com.xingshi.ai.plugins.api.SpeechRecognitionPlugin::class.java).firstOrNull()?.first
    }

    fun speechRecognitionPlugin(): com.xingshi.ai.plugins.api.SpeechRecognitionPlugin? =
        speechRecognitionPluginId()?.let { loaded[it] as? com.xingshi.ai.plugins.api.SpeechRecognitionPlugin }

    fun selectSpeechRecognitionPlugin(pluginId: String) {
        selectedCapabilities["stt"] = pluginId
        persistSelection()
    }

     
    fun textToSpeechPlugins(): List<com.xingshi.ai.plugins.api.TextToSpeechPlugin> =
        capabilitiesOf(com.xingshi.ai.plugins.api.TextToSpeechPlugin::class.java).map { it.second }

    fun textToSpeechPluginId(): String? = if (selectedCapabilities.containsKey("tts")) {
        preferredCapabilityId("tts")
    } else {
        capabilitiesOf(com.xingshi.ai.plugins.api.TextToSpeechPlugin::class.java).firstOrNull()?.first
    }

    fun textToSpeechPlugin(): com.xingshi.ai.plugins.api.TextToSpeechPlugin? =
        textToSpeechPluginId()?.let { loaded[it] as? com.xingshi.ai.plugins.api.TextToSpeechPlugin }

    fun selectTextToSpeechPlugin(pluginId: String) {
        selectedCapabilities["tts"] = pluginId
        persistSelection()
    }

    fun visionAnalysisPluginId(): String? = if (selectedCapabilities.containsKey("vision")) {
        preferredCapabilityId("vision")
    } else {
        capabilitiesOf(com.xingshi.ai.plugins.api.VisionAnalysisPlugin::class.java).firstOrNull()?.first
    }

    fun selectVisionAnalysisPlugin(pluginId: String) {
        selectedCapabilities["vision"] = pluginId
        persistSelection()
    }

    private fun preferredCapabilityId(capability: String): String? =
        selectedCapabilities[capability]?.let { id -> id.takeIf { loaded[id] != null } }

     
    fun capabilityVersion(pluginId: String): String? = synchronized(reloadLock) {
        val version = loadedVersions[pluginId]?.takeIf { loaded.containsKey(pluginId) } ?: return null
        val instance = loaded[pluginId] ?: return null
        "$version#${System.identityHashCode(instance)}"
    }

    private fun manifestVersion(pluginId: String): String? = synchronized(reloadLock) {
        loadedVersions[pluginId]?.takeIf { loaded.containsKey(pluginId) }
    }

     
    fun trackOptionalExecution(pluginId: String, version: String, job: Job): Boolean {
        val current = capabilityVersion(pluginId)
        val manifest = manifestVersion(pluginId)
        if (current != version && manifest != version) {
            job.cancel()
            return false
        }
        
        synchronized(executionLock) {
            if (accountClosed) { job.cancel(); return false }
            val invalidated = invalidationMap()[pluginId].orEmpty()
            val sameManifestAlreadyRevoked = version == manifest &&
                invalidated.any { it.startsWith("$manifest#") }
            if (version in invalidated || sameManifestAlreadyRevoked) {
                job.cancel()
                return false
            }
            optionalExecutions.getOrPut(pluginId) { mutableSetOf() }.add(job)
        }
        return true
    }

     
    fun registerOptionalCapabilityWatch(pluginIds: List<String>, onRevoked: () -> Unit): Job? {
        
        val required = pluginIds.distinct()
        if (required.isEmpty()) return null
        val candidates = required.map { id ->
            capabilityVersion(id)?.let { version -> id to version } ?: return null
        }
        val parent = Job()
        val registered = synchronized(executionLock) {
            val invalidated = invalidationMap()
            if (accountClosed || candidates.any { (id, version) -> version in invalidated[id].orEmpty() }) {
                false
            } else {
                candidates.forEach { (id, _) ->
                    optionalExecutions.getOrPut(id) { mutableSetOf() }.add(parent)
                }
                true
            }
        }
        if (!registered) return null
        parent.invokeOnCompletion { onRevoked() }
        return parent
    }

    fun unregisterOptionalCapabilityWatch(pluginIds: List<String>, job: Job) {
        synchronized(executionLock) {
            pluginIds.forEach { id ->
                optionalExecutions[id]?.remove(job)
                if (optionalExecutions[id]?.isEmpty() == true) optionalExecutions.remove(id)
            }
        }
        job.cancel()
    }

    fun untrackOptionalExecution(pluginId: String, job: Job) {
        synchronized(executionLock) {
            optionalExecutions[pluginId]?.remove(job)
            if (optionalExecutions[pluginId]?.isEmpty() == true) optionalExecutions.remove(pluginId)
        }
    }

     
    fun visionAnalysisPlugin(): com.xingshi.ai.plugins.api.VisionAnalysisPlugin? =
        visionAnalysisPluginId()?.let { loaded[it] as? com.xingshi.ai.plugins.api.VisionAnalysisPlugin }

     
    fun hasImageConsumer(): Boolean = records.value.any { record ->
        loaded[record.manifest.id]?.let { runCatching { it.acceptsSentImages() }.getOrDefault(false) } == true
    }

    fun startForSentImage(
        companionId: Long,
        imagePath: String,
        request: ChannelRequestRef? = null
    ) = startForSentImage(companionId, imagePath, request, text = "")

     
    fun startForSentImage(
        companionId: Long,
        imagePath: String,
        request: ChannelRequestRef?,
        text: String,
        localMessageId: Long? = null
    ) {
        if (accountClosed || companionId in deletedCompanions) return
        val ids = records.value.map { it.manifest.id }.filter { id ->
            loaded[id]?.let { runCatching { it.acceptsSentImages() }.getOrDefault(false) } == true
        }
        if (ids.isEmpty()) return
        val targets = if (request != null || localMessageId != null) listOf(ids.first()) else ids
        val pluginRequest = PluginRequest(
            companionId = companionId,
            text = text,
            attachments = listOf(PluginAttachment(imagePath, "image/*", PluginAttachmentKind.IMAGE))
        )
        targets.forEach { id ->
            if (localMessageId != null) {
                val pending = failureOutcome(id, pluginRequest, "图片处理尚待完成").copy(localMessageId = localMessageId)
                store.savePending(pending.toPending(pluginRequest, pluginRequest.attachments, request))
            }
            
            scope.launch { execute(id, pluginRequest, request, localMessageId) }
        }
    }

    fun hasPendingInput(localMessageId: Long) = store.pending().any { it.localMessageId == localMessageId }

     
    fun bindApplicationEntry(entry: ChatSendEntry?) {
        applicationEntry = entry
    }

     
    private fun sendEntryFor(companionId: Long, request: ChannelRequestRef? = null): ChatSendEntry? =
        if (request != null) applicationEntry ?: sessions[companionId]
        else sessions[companionId] ?: applicationEntry

    fun pageFor(
        pluginId: String,
        companionId: Long,
        input: String,
        close: () -> Unit,
        navigate: (String) -> Unit = {}
    ): (@Composable () -> Unit)? {
        val content = runCatching { loaded[pluginId]?.page() }.getOrNull() ?: return null
        val context = PluginPageContext(
            companionId = companionId,
            input = input,
            actionId = pendingActions[companionId],
            close = close,
            execute = { request -> scope.launch { execute(pluginId, request) } },
            cancel = { cancel(pluginId) },
            report = outcomeFor(companionId, pluginId)?.report,
            navigate = navigate
        )
        return { content(context) }
    }

    fun settingsPageFor(
        pluginId: String,
        close: () -> Unit = {},
        navigate: (String) -> Unit = {}
    ): (@Composable () -> Unit)? {
        val content = runCatching { loaded[pluginId]?.settingsPage() }.getOrNull() ?: return null
        val context = PluginPageContext(
            companionId = 0L, input = "", close = close, execute = {}, cancel = {}, report = null, navigate = navigate
        )
        return { content(context) }
    }

     
    private suspend fun analyzeImage(
        plugin: XingShiPlugin,
        request: PluginRequest,
        runtime: PluginRuntime
    ): PluginReport {
        val vision = plugin as? com.xingshi.ai.plugins.api.VisionAnalysisPlugin
            ?: return plugin.run(request, runtime)
        val imagePath = request.attachments
            .firstOrNull { it.kind == PluginAttachmentKind.IMAGE }
            ?.path
            ?: return plugin.run(request, runtime)
        val config = visionConfigProvider?.invoke() ?: return PluginReport.Failure("缺少视觉模型配置")
        if (config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return PluginReport.Failure("缺少视觉模型配置")
        }
        val result = vision.analyze(
            com.xingshi.ai.plugins.api.VisionAnalysisRequest(
                imagePath = imagePath,
                prompt = request.text.ifBlank { IMAGE_PROMPT },
                endpoint = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model
            )
        )
        return when (result) {
            is com.xingshi.ai.plugins.api.VisionAnalysisResult.Described -> PluginReport.Success(result.description)
            is com.xingshi.ai.plugins.api.VisionAnalysisResult.Failure -> PluginReport.Failure(result.reason)
            com.xingshi.ai.plugins.api.VisionAnalysisResult.Cancelled -> PluginReport.Cancelled
        }
    }

    suspend fun execute(
        pluginId: String,
        request: PluginRequest,
        channelRequest: ChannelRequestRef? = null,
        localMessageId: Long? = null
    ): PluginOutcome {
        if (accountClosed || request.companionId in deletedCompanions) return failureOutcome(pluginId, request, "账号或助手连接已关闭").copy(localMessageId = localMessageId)
        val record = records.value.firstOrNull { it.manifest.id == pluginId }
        val plugin = loaded[pluginId]
        if (record == null || plugin == null) {
            return fail(pluginId, request, "插件未安装或无法加载", localMessageId = localMessageId)
        }
        val executionId = idFactory()
        if (!claim(pluginId, executionId)) {
            
            
            val rejected = failureOutcome(pluginId, request, "插件正在执行，请稍候")
            
            synchronized(outcomeLock) {
                if (isStillInstalled(pluginId)) {
                    pluginOutcomeMap.update { it + ((pluginId to request.companionId) to rejected) }
                }
            }
            return rejected
        }
        requests[pluginId to request.companionId] = request
        val stop = beginExecution(pluginId, request.companionId, executionId)
        return try {
            
            
            if (!isStillInstalled(pluginId)) return fail(pluginId, request, "插件已卸载", executionId, localMessageId)
            
            if (channelRequest != null) executionRequests[executionId] = channelRequest
            val runtime = PluginRuntimeImpl(
                companionId = request.companionId,
                model = modelProvider(pluginId, runCatching { plugin.requiresVisionModel() }.getOrDefault(false)),
                activeCheck = { isActive(pluginId, executionId) },
                pluginId = pluginId,
                store = store
            )
            val report = try {
                analyzeImage(plugin, request, runtime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PluginReport.Failure(e.message ?: e.javaClass.simpleName)
            }
            val outcome = handoff.deliver(
                executionId = executionId,
                companionId = request.companionId,
                pluginId = pluginId,
                pluginName = record.manifest.name,
                originalText = request.text,
                report = report,
                entry = if (localMessageId != null) applicationEntry else sendEntryFor(request.companionId, channelRequest),
                isActive = { isActive(pluginId, executionId) },
                stop = stop,
                request = executionRequests[executionId],
                localMessageId = localMessageId
            )
            
            if (isCurrent(pluginId, request.companionId, executionId)) {
                if (outcome.submitted) executionRequests.remove(executionId)
                val attachments = durableAttachments(pluginId, request)
                
                
                synchronized(outcomeLock) {
                    if (isCurrent(pluginId, request.companionId, executionId)) {
                        
                        (outcome.report as? PluginReport.Success)?.let { success ->
                            runCatching {
                                store.saveResult(
                                    request.companionId,
                                    PluginResultRecord(
                                        pluginId = pluginId,
                                        text = success.text,
                                        submitted = outcome.submitted,
                                        createdAt = now(),
                                        originalText = request.text,
                                        attachmentPaths = attachments.map { it.path }
                                    )
                                )
                            }
                        }
                        if (outcome.submitted) {
                            runCatching { store.clearPending(request.companionId, pluginId) }
                        } else if (isStillInstalled(pluginId) && request.companionId > 0L) {
                            
                            runCatching { store.savePending(outcome.toPending(request, attachments)) }
                        }
                        if (isStillInstalled(pluginId)) {
                            publishOutcome(outcome)
                        }
                    }
                }
            }
            outcome
        } catch (e: CancellationException) {
            
            runCatching {
                
                synchronized(outcomeLock) {
                    if (isCurrent(pluginId, request.companionId, executionId) &&
                        isStillInstalled(pluginId) && request.companionId > 0L && !handoff.isDelivered(executionId)
                    ) {
                        val cancelled = failureOutcome(pluginId, request, "执行已取消")
                            .copy(report = PluginReport.Cancelled, localMessageId = localMessageId)
                        store.savePending(cancelled.toPending(request, durableAttachments(pluginId, request)))
                        
                        publishOutcome(cancelled)
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            fail(pluginId, request, e.message ?: e.javaClass.simpleName, executionId, localMessageId)
        } finally {
            activeMap.update { if (it[pluginId] == executionId) it - pluginId else it }
            endExecution(pluginId, request.companionId, executionId)
        }
    }

    fun cancel(pluginId: String) {
        
        revoke(pluginId)
        activeMap.update { it - pluginId }
    }

    fun cancelAll() {
        revokeAll()
        activeMap.value = emptyMap()
    }

    fun closeAccount() {
        val optional = synchronized(executionLock) {
            accountClosed = true
            optionalExecutions.values.flatMap { it.toList() }.also { optionalExecutions.clear() }
        }
        optional.forEach { it.cancel() }
        cancelAll()
        scope.coroutineContext[Job]?.cancel()
    }

    fun removeCompanion(companionId: Long, sharedPaths: Collection<String> = emptyList()) {
        deletedCompanions += companionId
        synchronized(executionLock) { executions.keys.filter { it.second == companionId }.map { it.first } }.forEach(::revoke)
        synchronized(outcomeLock) {
            store.removeCompanion(companionId, sharedPaths)
            outcomeMap.update { it - companionId }
            pluginOutcomeMap.update { values -> values.filterKeys { it.second != companionId } }
            requests.keys.filter { it.second == companionId }.forEach { requests.remove(it) }
            sessions.remove(companionId)
        }
    }

    fun bindSession(companionId: Long, entry: ChatSendEntry) {
        sessions[companionId] = entry
    }

    fun unbindSession(companionId: Long) {
        sessions.remove(companionId)
    }

     
    fun unbindSession(companionId: Long, entry: ChatSendEntry) {
        if (sessions[companionId] === entry) {
            sessions.remove(companionId)
        }
    }

    fun entryFor(companionId: Long): ChatSendEntry? = sessions[companionId]

    fun outcomeFor(companionId: Long): PluginOutcome? = outcomeMap.value[companionId]

    fun outcomeFor(companionId: Long, pluginId: String): PluginOutcome? =
        pluginOutcomeMap.value[pluginId to companionId]

    suspend fun retryOutcome(companionId: Long): PluginOutcome? {
        val current = outcomeMap.value[companionId] ?: return null
        if (current.submitted) return current
        synchronized(retrying) { if (!retrying.add(companionId)) return current }
        return try {
            val request = requests[current.pluginId to companionId]
            if (current.report is PluginReport.Success || request == null) {
                if (!isStillInstalled(current.pluginId)) return null
                val stop = beginExecution(current.pluginId, companionId, current.executionId)
                val channelRequest = executionRequests[current.executionId]
                val retried = try {
                    
                    
                    if (!isStillInstalled(current.pluginId)) return null
                    handoff.retry(current, if (current.localMessageId != null) applicationEntry else sendEntryFor(companionId, channelRequest), stop, channelRequest)
                } finally {
                    endExecution(current.pluginId, companionId, current.executionId)
                }
                
                
                
                
                
                synchronized(outcomeLock) {
                    if (pluginOutcomeMap.value[current.pluginId to companionId]?.executionId == current.executionId) {
                        if (retried.submitted) {
                            runCatching { store.clearPending(companionId, current.pluginId) }
                            executionRequests.remove(current.executionId)
                        }
                    }
                    if (outcomeMap.value[companionId]?.executionId == current.executionId) {
                        
                        
                        if (isStillInstalled(current.pluginId) && !isExecuting(current.pluginId, companionId)) {
                            publishOutcome(retried)
                        }
                    }
                }
                retried
            } else {
                
                val retriedChannelRequest = executionRequests.remove(current.executionId)
                execute(current.pluginId, request, retriedChannelRequest, current.localMessageId)
            }
        } finally {
            synchronized(retrying) { retrying.remove(companionId) }
        }
    }

    suspend fun sendOriginal(companionId: Long, text: String): Boolean =
        handoff.submitOriginal(companionId, text, sendEntryFor(companionId))

    fun dismissOutcome(companionId: Long) {
        
        
        
        synchronized(outcomeLock) {
            pluginOutcomeMap.value.keys
                .filter { it.second == companionId }
                .forEach { store.clearPending(companionId, it.first) }
            outcomeMap.update { it - companionId }
            pluginOutcomeMap.update { map -> map.filterKeys { it.second != companionId } }
        }
    }

    suspend fun search(query: String): List<PluginCatalogEntry> {
        val state = catalogState.value
        if (!state.loaded && !state.loading) refreshCatalog()
        val entries = catalogState.value.entries
        val keyword = query.trim()
        if (keyword.isEmpty()) return entries
        return entries.filter {
            it.name.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true)
        }
    }

     
    suspend fun refreshCatalog(): List<PluginCatalogEntry> {
        if (!catalog.isConfigured) {
            catalogState.update { PluginCatalogState(loaded = true, error = "插件目录未配置") }
            return emptyList()
        }
        catalogLock.withLock {
            catalogState.update { it.copy(loading = true) }
            val result = try {
                catalog.refresh()
            } catch (e: CancellationException) {
                catalogState.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                PluginCatalogResult(error = e.message ?: "插件目录读取失败")
            }
            catalogState.update {
                PluginCatalogState(
                    entries = result.entries,
                    loaded = true,
                    loading = false,
                    cached = result.cached,
                    error = result.error
                )
            }
            return result.entries
        }
    }

    suspend fun install(entry: PluginCatalogEntry): PluginInstallResult = serializedInstall(entry, updating = false)

    suspend fun update(entry: PluginCatalogEntry): PluginInstallResult = serializedInstall(entry, updating = true)

    private suspend fun serializedInstall(entry: PluginCatalogEntry, updating: Boolean): PluginInstallResult {
        val gate: CompletableDeferred<PluginInstallResult>
        val owner: Boolean
        synchronized(installGates) {
            val existing = installGates[entry.id]
            if (existing != null) {
                gate = existing
                owner = false
            } else {
                gate = CompletableDeferred()
                installGates[entry.id] = gate
                owner = true
            }
        }
        
        if (!owner) return runCatching { gate.await() }.getOrElse { PluginInstallResult.Failure("安装未完成") }
        var result: PluginInstallResult = PluginInstallResult.Failure("安装已取消")
        try {
            PluginInstallCenter.start(entry.id, 0L)
            val progress: (Long, Long) -> Unit = { received, total ->
                PluginInstallCenter.progress(entry.id, received, total)
            }
            result = try {
                if (updating) installer.update(entry, onProgress = progress) else installer.install(entry, onProgress = progress)
            } catch (e: CancellationException) {
                PluginInstallResult.Failure("安装已取消")
            } catch (e: Exception) {
                PluginInstallResult.Failure(e.message ?: e.javaClass.simpleName)
            }
            when {
                result !is PluginInstallResult.Success -> Unit
                updating -> {
                    
                    revoke(entry.id)
                    PluginChannels.stop(entry.id)
                    reloadOffMain()
                    
                    PluginChannels.restore(force = setOf(entry.id))
                }
                else -> {
                    reloadOffMain()
                    
                    PluginChannels.restore(force = setOf(entry.id))
                }
            }
            return result
        } finally {
            synchronized(installGates) { installGates.remove(entry.id) }
            PluginInstallCenter.finish(entry.id, (result as? PluginInstallResult.Failure)?.message)
            gate.complete(result)
        }
    }

    suspend fun uninstall(id: String): Boolean {
        
        revoke(id)
        activeMap.update { it - id }
        requests.entries.removeAll { it.key.first == id }
        val removed = synchronized(outcomeLock) {
            outcomeMap.update { map -> map.filterValues { it.pluginId != id } }
            pluginOutcomeMap.update { map -> map.filterKeys { it.first != id } }
            
            installer.uninstall(id)
        }
        
        withContext(Dispatchers.IO + NonCancellable) {
            PluginChannels.stop(id)
            loadedVersions.remove(id)
            runCatching { loaded.remove(id)?.cleanup() }
                .onFailure { SecureLog.e("PluginManager", "cleanup failed for $id", it) }
            reload()
        }
        return removed
    }

    private suspend fun reloadOffMain() = withContext(Dispatchers.IO) { reload() }

    private fun isActive(pluginId: String, executionId: String): Boolean = !accountClosed && activeMap.value[pluginId] == executionId &&
        synchronized(executionLock) { executions.entries.none { it.value.id == executionId && it.key.second in deletedCompanions } }

    private fun beginExecution(pluginId: String, companionId: Long, executionId: String): CompletableDeferred<Unit> =
        
        
        synchronized(outcomeLock) {
            synchronized(executionLock) {
                val key = pluginId to companionId
                
                stopSignals.remove(key)?.complete(Unit)
                executions[key] = Execution(executionId, Thread.currentThread())
                CompletableDeferred<Unit>().also { stopSignals[key] = it }
            }
        }

    private fun endExecution(pluginId: String, companionId: Long, executionId: String) = synchronized(executionLock) {
        val key = pluginId to companionId
        val current = executions[key]
        if (current?.id == executionId) {
            executions.remove(key)
            stopSignals.remove(key)
            current.done.countDown()
        }
    }

    private fun isCurrent(pluginId: String, companionId: Long, executionId: String): Boolean = companionId !in deletedCompanions &&
        synchronized(executionLock) { executions[pluginId to companionId]?.id == executionId }

    
    private fun isExecuting(pluginId: String, companionId: Long): Boolean =
        synchronized(executionLock) { executions[pluginId to companionId] != null }

    private fun revoke(pluginId: String) {
        val version = capabilityVersion(pluginId)
        val optional = synchronized(executionLock) {
            if (version != null) {
                invalidationMap().getOrPut(pluginId) { mutableSetOf() }.add(version)
            }
            optionalExecutions.remove(pluginId)?.toList().orEmpty()
        }
        optional.forEach { it.cancel() }
        val pending = synchronized(executionLock) {
            stopSignals.keys.filter { it.first == pluginId }.mapNotNull { key ->
                val current = executions[key]
                
                val busy = current != null && current.thread !== Thread.currentThread() &&
                    handoff.isPortBusy(current.id)
                stopSignals.remove(key)?.complete(Unit)
                if (busy) current?.done else null
            }
        }
        
        pending.forEach { runCatching { it.await(PORT_UNWIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) } }
    }

    private fun revokeAll() = synchronized(executionLock) {
        stopSignals.values.forEach { it.complete(Unit) }
        stopSignals.clear()
    }

    private fun claim(pluginId: String, executionId: String): Boolean = synchronized(executionLock) {
        if (activeMap.value.containsKey(pluginId)) {
            false
        } else {
            activeMap.update { it + (pluginId to executionId) }
            true
        }
    }

    private fun fail(
        pluginId: String,
        request: PluginRequest,
        message: String,
        executionId: String? = null,
        localMessageId: Long? = null
    ): PluginOutcome {
        val outcome = failureOutcome(pluginId, request, message).copy(localMessageId = localMessageId)
        
        synchronized(outcomeLock) {
            if (isStillInstalled(pluginId) && (executionId == null || isCurrent(pluginId, request.companionId, executionId))) {
                publishOutcome(outcome)
            }
        }
        return outcome
    }

    
    
    
    private fun publishOutcome(outcome: PluginOutcome) {
        outcomeMap.update { map ->
            val previous = map[outcome.companionId]
            if (previous != null && !previous.submitted && outcome.submitted &&
                previous.executionId != outcome.executionId && previous.pluginId != outcome.pluginId
            ) {
                map
            } else {
                map + (outcome.companionId to outcome)
            }
        }
        pluginOutcomeMap.update { it + ((outcome.pluginId to outcome.companionId) to outcome) }
    }

    
    private fun durableAttachments(pluginId: String, request: PluginRequest): List<PluginAttachment> =
        request.attachments.map { attachment ->
            val copied = runCatching {
                store.saveAttachment(pluginId, attachment.path, File(attachment.path).name).absolutePath
            }.getOrNull()
            if (copied == null) attachment else attachment.copy(path = copied)
        }

    private fun restorePending() {
        
        val pending = store.pending().filter { installer.isInstalled(it.pluginId) }
        if (pending.isEmpty()) return
        
        val restored = pending.map { it to it.toOutcome(idFactory()) }
        restored.forEach { (record, outcome) ->
            requests[record.pluginId to record.companionId] = record.toRequest()
            record.channelRequest?.let { executionRequests[outcome.executionId] = it.restore() }
        }
        pluginOutcomeMap.value = restored.associate { (record, outcome) -> (record.pluginId to record.companionId) to outcome }
        outcomeMap.value = restored.groupBy { it.first.companionId }.mapValues { (_, pairs) -> pairs.last().second }
    }

    private fun PendingOutcomeRecord.toOutcome(executionId: String): PluginOutcome = PluginOutcome(
        executionId = executionId,
        companionId = companionId,
        pluginId = pluginId,
        pluginName = pluginName,
        report = when (reportKind) {
            "success" -> PluginReport.Success(reportText)
            "failure" -> PluginReport.Failure(reportText)
            else -> PluginReport.Cancelled
        },
        originalText = originalText,
        mergedText = mergedText,
        submitted = false,
        message = message,
        createdAt = createdAt,
        localMessageId = localMessageId
    )

    private fun PendingOutcomeRecord.toRequest(): PluginRequest = PluginRequest(
        companionId = companionId,
        text = requestText,
        attachments = attachments.map {
            PluginAttachment(it.path, it.mimeType, runCatching { PluginAttachmentKind.valueOf(it.kind) }.getOrDefault(PluginAttachmentKind.IMAGE))
        }
    )

    private fun PluginOutcome.toPending(request: PluginRequest, attachments: List<PluginAttachment>,
        channelRequest: ChannelRequestRef? = executionRequests[executionId]): PendingOutcomeRecord =
        PendingOutcomeRecord(
            companionId = companionId,
            pluginId = pluginId,
            pluginName = pluginName,
            reportKind = when (report) {
                is PluginReport.Success -> "success"
                is PluginReport.Failure -> "failure"
                else -> "cancelled"
            },
            reportText = when (report) {
                is PluginReport.Success -> report.text
                is PluginReport.Failure -> report.message
                else -> ""
            },
            originalText = originalText,
            mergedText = mergedText,
            message = message,
            createdAt = createdAt,
            requestText = request.text,
            attachments = attachments.map { PendingAttachment(it.path, it.mimeType, it.kind.name) },
            localMessageId = localMessageId,
            channelRequest = channelRequest?.let { PendingChannelRequest(it.pluginId, it.replyRoute.accountId,
                it.replyRoute.externalConversationId, it.replyRoute.senderId, it.replyRoute.platformMessageId, it.replyRoute.opaqueRoute) }
        )

    private fun failureOutcome(pluginId: String, request: PluginRequest, message: String) = PluginOutcome(
        executionId = idFactory(),
        companionId = request.companionId,
        pluginId = pluginId,
        pluginName = records.value.firstOrNull { it.manifest.id == pluginId }?.manifest?.name ?: pluginId,
        report = PluginReport.Failure(message),
        originalText = request.text,
        mergedText = "",
        submitted = false,
        message = message,
        createdAt = now()
    )
}

private class PluginRuntimeImpl(
    override val companionId: Long,
    override val model: PluginModel,
    private val activeCheck: () -> Boolean,
    private val pluginId: String,
    private val store: PluginStore
) : PluginRuntime {

    private val loop = PluginToolLoop(model)

    override fun isActive(): Boolean = activeCheck()

    override suspend fun runToolLoop(
        prompt: String,
        input: String,
        tools: List<PluginTool>,
        maxRounds: Int
    ): PluginReport = loop.run(prompt, input, tools, maxRounds, activeCheck)

    override suspend fun saveAttachment(path: String, mimeType: String, kind: PluginAttachmentKind): String =
        store.saveAttachment(pluginId, path, File(path).name).absolutePath
}
