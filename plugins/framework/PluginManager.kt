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

// 取消/卸载等发送口收尾的上限：正常是毫秒级，超时就不等了，避免卡住调用方
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
    // 已装载实例对应的包版本：未变化包的页面、运行、清理必须共用同一实例所有者
    private val loadedVersions = mutableMapOf<String, String>()
    private val sessions = mutableMapOf<Long, ChatSendEntry>()
    /** 无页面时的应用级发送口：视觉等插件结果仍进入统一对话入口，不依赖聊天页是否创建。 */
    private var applicationEntry: ChatSendEntry? = null
    /** 本次执行携带的通道请求归属：按执行 ID 绑定，不按插件/角色残留给后续图片。 */
    private val executionRequests = mutableMapOf<String, ChannelRequestRef>()
    private val pendingInputs = mutableMapOf<Long, String>()
    private val pendingActions = mutableMapOf<Long, String>()
    private val requests = mutableMapOf<Pair<String, Long>, PluginRequest>()
    private val executionLock = Any()
    // reload 的「读快照 → 逐个装载 → 整体换入」必须串行：并发卸载/安装各触发一次 reload 时，
    // 后完成的旧快照会覆盖先完成的新列表，已卸载插件复活、刚安装插件消失
    private val reloadLock = Any()
    // 结果账目的发布与清账互斥：卸载清账和重试回填撞在一起时，不能留下已卸载插件的幽灵横幅
    private val outcomeLock = Any()
    private val retrying = mutableSetOf<Long>()
    // 每个「插件+会话」只认最新一次执行：旧执行的迟到结果不能覆盖它
    private val executions = mutableMapOf<Pair<String, Long>, Execution>()
    private val stopSignals = mutableMapOf<Pair<String, Long>, CompletableDeferred<Unit>>()
    /** 可选能力（STT/TTS/Vision）的在飞调用；卸载/更新必须能撤销，不能只覆盖通用工具循环。 */
    private val optionalExecutions = mutableMapOf<String, MutableSet<Job>>()
    /** 已显式选择的能力提供者，避免安装顺序决定实现。 */
    private val selectedCapabilities = mutableMapOf<String, String>()

    /** 远程目录只在进入市场或点击刷新时重新读取；失败不冒充「没有插件」。 */
    private val catalogState = MutableStateFlow(PluginCatalogState())
    val marketplace: StateFlow<PluginCatalogState> = catalogState
    private val catalogLock = Mutex()

    /** 同一插件的并发点击合并为一次安装/更新。 */
    private val installGates = mutableMapOf<String, CompletableDeferred<PluginInstallResult>>()

    private companion object {
        const val IMAGE_PROMPT =
            "只描述这张图片中实际可见的人物、物体、位置、状态和可读文字；" +
                "模糊、遮挡或无法判断的部分要保留不确定性，不猜身份、不推测画面外的事情。"
    }

    // 撤销过的「插件@版本」：撤销与迟到登记共用 executionLock 裁决，重新装载成功后才清除。
    // 可为空以兼容测试/夹具直接分配实例；首次在锁内使用时初始化。
    private var invalidatedGenerations: MutableMap<String, MutableSet<String>>? = null

    private fun invalidationMap(): MutableMap<String, MutableSet<String>> =
        invalidatedGenerations ?: mutableMapOf<String, MutableSet<String>>().also { invalidatedGenerations = it }

    private val selectionFile: File = File(store.rootDir, "capability-selection.properties")

    init {
        // 显式提供者选择跨重启绑定；缺失/卸载时返回无提供者，绝不静默切到另一实现。
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

    // 会话横幅只保留最后一条结果，卡片需要各自插件的结果，所以再按插件各存一份
    val pluginOutcomes: StateFlow<Map<Pair<String, Long>, PluginOutcome>> = pluginOutcomeMap

    val loadErrors: StateFlow<Map<String, String>> = loadErrorMap

    val running: StateFlow<Set<String>> =
        activeMap.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val catalogConfigured: Boolean get() = catalog.isConfigured

    fun isInstalled(id: String): Boolean = records.value.any { it.manifest.id == id }

    /** 已加载插件实例（含可选通道）；未加载/加载失败返回 null。 */
    fun loadedPlugin(id: String): XingShiPlugin? = synchronized(reloadLock) { loaded[id] }

    fun installedIds(): List<String> = records.value.map { it.manifest.id }

    // 卸载期间的判断必须看磁盘上的真实状态：内存快照要等 reload 之后才更新，
    // 用它会把「正在卸载」的插件当成还在，从而把结果/待发材料挂回已卸载的插件
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
        // 本次装载的实例先收在本地：installed 是 UI 记住入口（按钮/卡片/页面内容）的键，
        // 装载途中就换了它，界面会把空入口缓存下来，加载完成也没有新键让它重算
        val next = mutableMapOf<String, XingShiPlugin>()
        val nextVersions = mutableMapOf<String, String>()
        for (record in all) {
            val id = record.manifest.id
            val version = record.manifest.version
            val dir = store.packageDir(id)
            // 未变化包直接沿用原实例：重载会换掉 ServiceLocator/TokenStore，令页面绑定、运行通道与
            // 卸载清理分裂成不同实例所有者；安装/卸载其它插件不应重建本插件
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
        // 装载完成后再一起换入：先实例、再错误、最后才是 UI 消费的入口列表
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

    /** 已加载的可选语音识别插件；按稳定插件 id 排序，不依赖安装顺序。 */
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

    /** 已加载的可选语音合成插件；按稳定插件 id 排序，不依赖安装顺序。 */
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

    /**
     * 当前已加载实例的代际令牌：包版本 + 真实实例身份，同版本重装也会得到不同令牌。
     * 外部只把它当作不透明凭据回传，不能自行用版本号构造。
     */
    fun capabilityVersion(pluginId: String): String? = synchronized(reloadLock) {
        val version = loadedVersions[pluginId]?.takeIf { loaded.containsKey(pluginId) } ?: return null
        val instance = loaded[pluginId] ?: return null
        "$version#${System.identityHashCode(instance)}"
    }

    private fun manifestVersion(pluginId: String): String? = synchronized(reloadLock) {
        loadedVersions[pluginId]?.takeIf { loaded.containsKey(pluginId) }
    }

    /** 注册可选能力调用；代际已变化或该代际已撤销时拒绝登记并取消该调用。 */
    fun trackOptionalExecution(pluginId: String, version: String, job: Job): Boolean {
        val current = capabilityVersion(pluginId)
        val manifest = manifestVersion(pluginId)
        if (current != version && manifest != version) {
            job.cancel()
            return false
        }
        // 版本检查与登记之间可能发生撤销：失效代际表在同一把 executionLock 里裁决。
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

    /** 整次通话能力会话：任一提供者卸载/更新即回调撤销，采集与播放一并收尾。 */
    fun registerOptionalCapabilityWatch(pluginIds: List<String>, onRevoked: () -> Unit): Job? {
        // 本次通话所需提供者必须全部存在且未失效；缺任意一项都不能以“还剩一项”放行。
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

    /** 已加载的可选视觉分析插件（视频通话按轮读图）；旧视觉插件不受影响。 */
    fun visionAnalysisPlugin(): com.xingshi.ai.plugins.api.VisionAnalysisPlugin? =
        visionAnalysisPluginId()?.let { loaded[it] as? com.xingshi.ai.plugins.api.VisionAnalysisPlugin }

    /** 是否有接图插件；调用方据此决定视觉链路是否接管本轮，避免再排裸标记/原文轮次。 */
    fun hasImageConsumer(): Boolean = records.value.any { record ->
        loaded[record.manifest.id]?.let { runCatching { it.acceptsSentImages() }.getOrDefault(false) } == true
    }

    fun startForSentImage(
        companionId: Long,
        imagePath: String,
        request: ChannelRequestRef? = null
    ) = startForSentImage(companionId, imagePath, request, text = "")

    /**
     * 图片处理入口。
     *
     * - 带原通道请求时只交给一个接图插件：同一请求最终只能交接一个正式轮次，否则共用的一次性
     *   回复路由只够其中一轮消费；普通本地图片（request 为空）保持既有全部接图插件并行能力。
     * - [text] 是图文入站的原问题，随视觉报告合并进同一个轮次，不另排原文轮。
     */
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
            // 原请求绑定本次执行：同一角色之后的本地图片不会继承上一条通道归属
            scope.launch { execute(id, pluginRequest, request, localMessageId) }
        }
    }

    fun hasPendingInput(localMessageId: Long) = store.pending().any { it.localMessageId == localMessageId }

    /** 应用级发送口由宿主注入；页面未创建时插件结果仍进入统一对话入口。 */
    fun bindApplicationEntry(entry: ChatSendEntry?) {
        applicationEntry = entry
    }

    /**
     * 发送口选择：带原请求的结果不依赖页面，直接走应用级入口；
     * 没有外部归属的普通结果仍优先页面会话，保持页面内的既有呈现。
     */
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

    /**
     * 图片入口：宿主用独立 VISUAL 配置构造请求，交给插件的真实视觉能力。
     * 文件名、路径与「图片已读取」文本都不能代替图片数据，也不回退主模型工具循环。
     */
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
            // 并发占用只是这次点击被拒绝，不是一次执行结果：只更新该插件的报告，不动会话横幅，
            // 否则会把已经发送的结果顶成可重试，再点一次重试就重复发送
            val rejected = failureOutcome(pluginId, request, "插件正在执行，请稍候")
            // 与卸载清账互斥，并在锁内复核仍安装：卸载先发生就不能再把这条被拒绝的报告挂回内存表
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
            // 卸载可能刚好落在「确认可加载」与「登记执行」之间：登记后再复核一次，
            // 撤销才来得及取消这个执行口，否则卸载之后还会再发出一次消息
            if (!isStillInstalled(pluginId)) return fail(pluginId, request, "插件已卸载", executionId, localMessageId)
            // 请求只属于本次执行；本地图片传入 null，重试沿用原执行归属
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
            // 旧执行的迟到结果不能覆盖后续执行的结果与待处理材料
            if (isCurrent(pluginId, request.companionId, executionId)) {
                if (outcome.submitted) executionRequests.remove(executionId)
                val attachments = durableAttachments(pluginId, request)
                // 身份核验、落库与发布必须在同一把锁里：执行身份在附件复制期间被更替时，
                // 旧执行既不能再写结果，也不能把新执行留下的待发材料与横幅顶回去
                synchronized(outcomeLock) {
                    if (isCurrent(pluginId, request.companionId, executionId)) {
                        // 用 outcome.report 而不是原始 report：被取消的迟到成功结果不应落库
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
                            // companionId 为 0 表示没有会话（例如从插件管理页执行），落盘的记录没有界面能展示
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
            // 宿主协程被取消（例如离开会话页）时，未提交的输入与附件仍要落盘，重启后还能重试
            runCatching {
                // 同样必须与卸载清账互斥：谁先拿到锁，结果都是干净状态
                synchronized(outcomeLock) {
                    if (isCurrent(pluginId, request.companionId, executionId) &&
                        isStillInstalled(pluginId) && request.companionId > 0L && !handoff.isDelivered(executionId)
                    ) {
                        val cancelled = failureOutcome(pluginId, request, "执行已取消")
                            .copy(report = PluginReport.Cancelled, localMessageId = localMessageId)
                        store.savePending(cancelled.toPending(request, durableAttachments(pluginId, request)))
                        // 取消的结果也要进内存表，否则「返回聊天」清不到它，重启后会复活成幽灵横幅
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
        // 撤销在飞的发送口等待：取消之后不能再算作已发送
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

    /** 所有者解绑：只移除调用方自己绑定的发送口，避免详情页/旧实例覆盖新会话入口。 */
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
                    // 卸载可能刚好落在「确认仍安装」与「登记执行」之间：登记后再复核一次，
                    // 撤销才来得及取消这个执行口，否则卸载之后还会再发出一次消息
                    if (!isStillInstalled(current.pluginId)) return null
                    handoff.retry(current, if (current.localMessageId != null) applicationEntry else sendEntryFor(companionId, channelRequest), stop, channelRequest)
                } finally {
                    endExecution(current.pluginId, companionId, current.executionId)
                }
                // 重试成功也要收尾持久记录，否则重启后它会复活成待发，再重试就重复发送；
                // 同一个 (会话, 插件) 只有一份 pending，可能已被同插件的新执行改写，所以收尾与发布都要留在
                // outcomeLock 内并确认记录仍属于本次执行，别把新执行刚写下的材料删掉。
                // 材料归插件自己的记录所有，会话横幅只有一个槽位：别的插件抢走横幅不代表材料易主，
                // 所以清账按插件槽判归属、发布才看会话槽
                synchronized(outcomeLock) {
                    if (pluginOutcomeMap.value[current.pluginId to companionId]?.executionId == current.executionId) {
                        if (retried.submitted) {
                            runCatching { store.clearPending(companionId, current.pluginId) }
                            executionRequests.remove(current.executionId)
                        }
                    }
                    if (outcomeMap.value[companionId]?.executionId == current.executionId) {
                        // 重试期间被卸载（或已有新执行接手）时不能再把结果挂回去，否则会留下幽灵横幅；
                        // 这里不能再用 isCurrent：finally 已经把这次执行登记撤掉了，否则成功结果永远挂不回可见状态
                        if (isStillInstalled(current.pluginId) && !isExecuting(current.pluginId, companionId)) {
                            publishOutcome(retried)
                        }
                    }
                }
                retried
            } else {
                // 失败重执行沿用原请求归属：新执行仍可按同一通道请求回复
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
        // 这个会话里每个插件各有一条待处理记录，只清最后一条会让别的插件重启后重新冒出横幅；
        // 取键、清盘、清内存表都在同一把锁里：与结果发布互斥，既不会把新执行刚写下的待发材料删掉，
        // 也不会留下「盘上有材料、内存横幅没了」的幽灵
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

    /** 重新检查远程目录：成功即替换缓存；失败保留缓存并标记「暂时无法刷新」。 */
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
        // 重复点击合并：后来者等待同一结果，不再发起第二次下载
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
                    // 更新同样撤销在飞可选能力：旧版本返回值不能继续落到新版本。
                    revoke(entry.id)
                    PluginChannels.stop(entry.id)
                    reloadOffMain()
                    // 更新目标先停旧起新；更新失败不会走到这里，旧版本与旧连接保持可用
                    PluginChannels.restore(force = setOf(entry.id))
                }
                else -> {
                    reloadOffMain()
                    // 只补启本次安装涉及的通道；已在运行的其它通道保持执行实例
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
        // 卸载即撤销：在飞的重试不能再发出消息，也不能把结果挂回已卸载的插件
        revoke(id)
        activeMap.update { it - id }
        requests.entries.removeAll { it.key.first == id }
        val removed = synchronized(outcomeLock) {
            outcomeMap.update { map -> map.filterValues { it.pluginId != id } }
            pluginOutcomeMap.update { map -> map.filterKeys { it.first != id } }
            // 清账与摘除登记必须在同一把锁里：否则重试的回填会落在清账之后，变成幽灵横幅
            installer.uninstall(id)
        }
        // 删盘已提交：停止、卸载清理与列表刷新都不可取消，取消不能跳过收尾
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
        // 身份更替与最终落库/发布共用同一把锁：否则被取代的旧执行可以在附件复制期间逃过身份核验，
        // 把新执行刚写下的结果与待发材料顶回旧结果
        synchronized(outcomeLock) {
            synchronized(executionLock) {
                val key = pluginId to companionId
                // 上一次执行还没结束：先撤销它的发送口等待，旧结果不能迟到发送
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

    // 这个插件在这个会话里是否还有执行在飞：收尾时判断有没有被新执行取代
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
                // 只有发送口正挂着等才需要等收尾；插件自身还在跑、或调用方就是执行线程时都不等，别卡住调用方
                val busy = current != null && current.thread !== Thread.currentThread() &&
                    handoff.isPortBusy(current.id)
                stopSignals.remove(key)?.complete(Unit)
                if (busy) current?.done else null
            }
        }
        // 锁外等待：收尾线程回来时会拿同一把锁
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
        // 旧执行的迟到异常不能覆盖后续执行的结果；与卸载清账互斥，避免卸载后还挂上幽灵横幅
        synchronized(outcomeLock) {
            if (isStillInstalled(pluginId) && (executionId == null || isCurrent(pluginId, request.companionId, executionId))) {
                publishOutcome(outcome)
            }
        }
        return outcome
    }

    // 会话级横幅只显示一条：别的插件的待发记录不能被已提交的新结果顶掉，否则那条记录要等到重启才再出现；
    // 同一次执行自己的重试成功必须顶掉它，否则横幅还能再点一次重试，宿主会收到第二条相同消息；
    // 同插件的新执行已经提交时它已按插件清掉了那份待发材料，旧横幅必须让位，否则点重试会把旧文本补发到新结果之后
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

    // 附件复制到插件自己的目录：缓存被系统清理后，重试/重启仍能读到图片
    private fun durableAttachments(pluginId: String, request: PluginRequest): List<PluginAttachment> =
        request.attachments.map { attachment ->
            val copied = runCatching {
                store.saveAttachment(pluginId, attachment.path, File(attachment.path).name).absolutePath
            }.getOrNull()
            if (copied == null) attachment else attachment.copy(path = copied)
        }

    private fun restorePending() {
        // 已卸载插件残留的待发文件不能复活成幽灵横幅：卸载与取消收尾竞态时晚一步落下的产物就在这里被挡住
        val pending = store.pending().filter { installer.isInstalled(it.pluginId) }
        if (pending.isEmpty()) return
        // 每条恢复结果都要有独立交接身份：共用常量会让第二条被当成已投递而漏发，却仍报已提交并清掉材料
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
