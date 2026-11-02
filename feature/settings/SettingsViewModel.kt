package com.xingshi.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.AppSettingsStore
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.domain.BackendAccountGateway
import com.xingshi.ai.domain.ChatModelCloudGateway
import com.xingshi.ai.domain.CloudChatModelPublish
import com.xingshi.ai.domain.MainChatConfigurationException
import com.xingshi.ai.domain.MainChatConfigurationIssue
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.feature.settings.ApiConfigValidator
import com.xingshi.ai.feature.settings.SingleSubscription
import com.xingshi.ai.feature.settings.mainConfigurationIssue
import com.xingshi.ai.feature.settings.toChatModelDraft
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.SpeechPreset
import com.xingshi.ai.plugins.api.TtsPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettingsStore = AppSettingsStore(application)
    private val statusSubscription = SingleSubscription(viewModelScope)
    private lateinit var repository: ApiConfigRepository
    val configs: Flow<List<ApiConfig>>

    /** 安装态随插件列表刷新：未安装对应插件时不显示独立配置项。 */
    val sttAvailable: StateFlow<Boolean> = run {
        val manager = PluginFramework.manager()
        val initial = manager?.speechRecognitionPlugins()?.isNotEmpty() == true
        manager?.installed
            ?.map { manager.speechRecognitionPlugins().isNotEmpty() }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, initial)
            ?: MutableStateFlow(initial)
    }

    val ttsAvailable: StateFlow<Boolean> = run {
        val manager = PluginFramework.manager()
        val initial = manager?.textToSpeechPlugins()?.isNotEmpty() == true
        manager?.installed
            ?.map { manager.textToSpeechPlugins().isNotEmpty() }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, initial)
            ?: MutableStateFlow(initial)
    }

    // 连接状态由 StateFlow + ConcurrentHashMap 承载：它会被多个 Dispatchers.IO
    // 协程并发写入，不适合直接写 Compose 快照状态。
    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionResult>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionResult>> = _connectionStatus.asStateFlow()
    private val connectionStatusMap = java.util.concurrent.ConcurrentHashMap<String, ConnectionResult>()

    /** 线程安全更新 connectionStatus：合并到 ConcurrentMap 后整体发布到 StateFlow */
    private fun updateConnectionStatus(key: String, value: ConnectionResult) {
        connectionStatusMap[key] = value
        _connectionStatus.value = connectionStatusMap.toMap()
    }
    private fun removeConnectionStatus(key: String) {
        connectionStatusMap.remove(key)
        _connectionStatus.value = connectionStatusMap.toMap()
    }

    // _saveResult 加 extraBufferCapacity，避免无订阅者时 emit 永久挂起
    private val _saveResult = MutableSharedFlow<SaveResult>(extraBufferCapacity = 8)
    val saveResult: SharedFlow<SaveResult> = _saveResult

    private val _contextLimit = MutableStateFlow(50)
    val contextLimit: StateFlow<Int> = _contextLimit.asStateFlow()

    private val _innerThoughtEnabled = MutableStateFlow(false)
    val innerThoughtEnabled: StateFlow<Boolean> = _innerThoughtEnabled.asStateFlow()

    /** 主模型在云端的确认状态；本地行只是展示缓存，启停和默认都不再由它决定。 */
    enum class MainModelState { ACTIVE, DISABLED, UNAVAILABLE, PENDING }
    data class MainModelCloudState(
        val isDefault: Boolean,
        val state: MainModelState,
        /** 云端是否仍有可用凭据：停用/不可用的配置显示启用入口前要先判断。 */
        val keyConfigured: Boolean = true
    )

    private val _mainModelCloud = MutableStateFlow<Map<Long, MainModelCloudState>>(emptyMap())
    val mainModelCloud: StateFlow<Map<Long, MainModelCloudState>> = _mainModelCloud.asStateFlow()

    private val _mainModelNotice = MutableStateFlow<String?>(null)

    /** 主模型列表的只读原因：未登录或云端不可用时，这里只显示本机展示缓存。 */
    val mainModelNotice: StateFlow<String?> = _mainModelNotice.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsStore.contextLimitFlow.collect { limit ->
                _contextLimit.value = limit
            }
        }

        viewModelScope.launch {
            appSettingsStore.innerThoughtEnabledFlow.collect { enabled ->
                _innerThoughtEnabled.value = enabled
            }
        }
    }

    fun setContextLimit(limit: Int) {
        viewModelScope.launch {
            appSettingsStore.setContextLimit(limit)
        }
    }

    fun setInnerThoughtEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setInnerThoughtEnabled(enabled)
        }
    }

    enum class ConnectionStatus {
        UNKNOWN, TESTING, CONNECTED, FAILED, HISTORICAL
    }
    data class ConnectionResult(
        val status: ConnectionStatus,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null,
        val errorCode: String? = null,
    )

    fun connectionKey(config: ApiConfig): String =
        if (config.id > 0) "id:${config.id}" else "draft:${config.provider.name}:${config.name}:${config.baseUrl}"

    sealed class SaveResult {
        data class Success(val message: String) : SaveResult()
        data class Error(val message: String, val mainIssue: MainChatConfigurationIssue? = null) : SaveResult()
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ApiConfigRepository(database.apiConfigDao())
        configs = repository.getAllConfigs()
        // 连接状态恢复统一交给下面的持续 collect：再单独 first() 会形成第二份 DB 订阅。
        refreshConnectionStatus()
        refreshMainConfigurations()
    }

    private fun chatModelCloud(): ChatModelCloudGateway? = ServiceRegistry.get(ChatModelCloudGateway::class.java)

    private fun backendUserId(): String? = ServiceRegistry.get(BackendAccountGateway::class.java)?.state?.value?.userId

    fun saveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (config.kind == "MAIN") publishMainConfiguration(config) else saveAuxiliaryConfiguration(config)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                SecureLog.e("SettingsViewModel", "Save config failed: ${failure.message}")
                _saveResult.emit(SaveResult.Error(failure.message ?: "保存失败，请检查配置", (failure as? MainChatConfigurationException)?.issue))
            }
        }
    }

    /**
     * 主模型只走云端：本地行仅作展示缓存和未确认草稿。
     *
     * 发布确认后用云端回执刷新缓存并清除手机上的 Key 长期副本；发布结果未知时
     * 运行层保留原操作身份和加密草稿，这里提示用户用原内容重试做幂等确认。
     */
    private suspend fun publishMainConfiguration(config: ApiConfig) {
        val cloud = chatModelCloud()
        if (cloud == null || backendUserId() == null) {
            _saveResult.emit(SaveResult.Error("主模型配置以云端为准：请先登录后再保存"))
            return
        }
        val known = config.id > 0 && _mainModelCloud.value.containsKey(config.id)
        // 云端已确认的配置不在手机保存 Key，留空表示沿用云端凭据；草稿才需要本地密钥。
        val merged = if (config.apiKey.isBlank() && known) config else mergeExistingSecret(config)
        merged.mainConfigurationIssue(allowStoredKey = known)?.let { issue ->
            _saveResult.emit(SaveResult.Error("", issue))
            return
        }
        ApiConfigValidator.validate(merged, allowBlankKey = known)?.let { error ->
            _saveResult.emit(SaveResult.Error(error))
            return
        }
        val localId = if (config.id > 0) config.id else repository.saveConfig(merged.copy(isEnabled = false))
        val published = try {
            cloud.publish(merged.toChatModelDraft(localId, merged.apiKey))
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            SecureLog.e("SettingsViewModel", "Publish main configuration failed: ${failure.message}")
            syncMainConfigurations()
            _saveResult.emit(SaveResult.Error(failure.message ?: "主模型配置发布失败，请重试"))
            return
        }
        when (published) {
            is CloudChatModelPublish.Rejected -> {
                syncMainConfigurations()
                _saveResult.emit(SaveResult.Error(published.reason))
            }
            is CloudChatModelPublish.Unknown -> {
                // 结果未知：把本次内容留成待确认草稿，用户重开编辑看到的就是原内容，
                // 重试时运行层才能用同一操作身份做幂等确认。
                repository.saveConfig(merged.copy(id = localId, isEnabled = false))
                syncMainConfigurations()
                _saveResult.emit(SaveResult.Error("${published.reason}；命令与草稿已保留，请用原内容重试以确认"))
            }
            is CloudChatModelPublish.Confirmed -> {
                val confirmed = published.configuration
                val cached = repository.getConfigById(localId) ?: merged.copy(id = localId)
                repository.saveConfig(
                    cached.copy(
                        provider = apiProvider(confirmed.provider),
                        baseUrl = confirmed.baseUrl.ifBlank { cached.baseUrl },
                        model = confirmed.model.ifBlank { cached.model },
                        temperature = confirmed.temperature,
                        maxTokens = confirmed.maxTokens,
                        formatHint = cloudFormatHint(confirmed.protocol),
                        isEnabled = confirmed.status == "ACTIVE",
                        // 云端已确认发布：清理手机上的 Key 长期副本，只保留展示缓存。
                        apiKey = "",
                        extraApiKeys = "",
                    )
                )
                var failedBindings: List<String> = emptyList()
                var activationProblem: String? = null
                if (confirmed.status == "ACTIVE") {
                    try {
                        failedBindings = cloud.activate(localId).failed
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        SecureLog.e("SettingsViewModel", "Activate main configuration failed: ${failure.message}")
                        activationProblem = failure.message ?: "请稍后重试"
                    }
                }
                syncMainConfigurations()
                val message = when {
                    activationProblem != null -> "主模型配置已在云端发布；账号默认切换未完成：$activationProblem"
                    failedBindings.isNotEmpty() -> "主模型配置已发布并启用，以下助手未生效：${failedBindings.joinToString()}"
                    else -> "主模型配置已发布并启用"
                }
                _saveResult.emit(SaveResult.Success(message))
            }
        }
    }

    /** 视觉/语音等辅助配置仍按本机配置保存，不参与云端主模型生效链。 */
    private suspend fun saveAuxiliaryConfiguration(config: ApiConfig) {
        val key = connectionKey(config)
        val draftStatus = _connectionStatus.value[key]
        // 编辑态未重填 apiKey 时沿用库里的值，避免 REPLACE 把原密钥/字段顶掉。
        val mergedConfig = mergeExistingSecret(config)
        ApiConfigValidator.validate(mergedConfig)?.let { error ->
            _saveResult.emit(SaveResult.Error(error))
            return
        }
        val configToSave = if (draftStatus?.status == ConnectionStatus.CONNECTED) {
            mergedConfig.copy(
                connectionTested = true,
                connectionTestedAt = System.currentTimeMillis(),
                latencyMs = draftStatus.latencyMs
            )
        } else {
            mergedConfig
        }
        SecureLog.d("SettingsViewModel", "Saving config: provider=${configToSave.provider}, model=${configToSave.model}, keys=${configToSave.getAllApiKeys().size}")
        val savedId = repository.saveConfig(configToSave)
        updateConnectionStatus(connectionKey(configToSave.copy(id = savedId)), draftStatus ?: ConnectionResult(ConnectionStatus.UNKNOWN))
        _saveResult.emit(SaveResult.Success("配置已保存并已启用"))
    }

    fun saveVisionConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 视觉模型配置独立保存，不参与主模型的单活跃切换
                val merged = mergeExistingSecret(config).copy(kind = "VISUAL")
                ApiConfigValidator.validate(merged)?.let { error ->
                    _saveResult.emit(SaveResult.Error(error))
                    return@launch
                }
                repository.saveConfig(merged)
                _saveResult.emit(SaveResult.Success("视觉模型已保存"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save vision config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

    /** 语音识别插件提供的首版预设（端点/模型默认值）；未安装返回 null。 */
    fun sttPreset(): SpeechPreset? =
        PluginFramework.manager()?.speechRecognitionPlugin()?.speechPresets?.firstOrNull()

    fun saveSttConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 独立全局配置：编辑留空 Key 时合并旧值；新建无 Key 不允许保存。
                val merged = mergeExistingSecret(config).copy(kind = "STT")
                val error = ApiConfigValidator.validateStt(merged)
                if (error != null) {
                    _saveResult.emit(SaveResult.Error(error))
                    return@launch
                }
                val savedId = repository.saveConfig(merged)
                // 语音识别只有一个生效配置，不留第二份可选供应商
                repository.deleteSttConfigsExcept(savedId)
                _saveResult.emit(SaveResult.Success("语音识别配置已保存"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save STT config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

    /** 语音合成插件提供的可用音色；未安装返回空列表。 */
    fun ttsVoices(): List<com.xingshi.ai.plugins.api.TtsVoice> =
        PluginFramework.manager()?.textToSpeechPlugin()?.ttsVoices.orEmpty()

    /** 语音合成插件提供的首版预设（端点/模型/音色默认值）；未安装返回 null。 */
    fun ttsPreset(): TtsPreset? =
        PluginFramework.manager()?.textToSpeechPlugin()?.ttsPresets?.firstOrNull { it.endpoint.isNotBlank() }

    fun saveTtsConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 独立全局配置：Key/端点/模型/音色必须由语音合成自己的配置提供。
                val merged = mergeExistingSecret(config).copy(kind = "TTS")
                val error = ApiConfigValidator.validateTts(merged)
                if (error != null) {
                    _saveResult.emit(SaveResult.Error(error))
                    return@launch
                }
                val savedId = repository.saveConfig(merged)
                repository.deleteTtsConfigsExcept(savedId)
                _saveResult.emit(SaveResult.Success("语音合成配置已保存"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save TTS config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

    fun deleteTtsConfig(config: ApiConfig) {
        if (config.id <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteConfigById(config.id)
                _saveResult.emit(SaveResult.Success("语音合成配置已清除"))
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("操作失败"))
            }
        }
    }

    fun deleteSttConfig(config: ApiConfig) {
        if (config.id <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteConfigById(config.id)
                _saveResult.emit(SaveResult.Success("语音识别配置已清除"))
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("操作失败"))
            }
        }
    }

    fun deleteVisionConfig(config: ApiConfig) {
        if (config.id <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteConfigById(config.id)
                _saveResult.emit(SaveResult.Success("视觉模型已清除"))
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("操作失败"))
            }
        }
    }

    fun toggleConfigEnabled(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (config.kind != "MAIN") {
                    if (config.id <= 0L) return@launch
                    val enabled = !config.isEnabled
                    repository.updateConfig(config.copy(isEnabled = enabled))
                    _saveResult.emit(SaveResult.Success(if (enabled) "配置已启用" else "配置已禁用"))
                    return@launch
                }
                val cloud = chatModelCloud()
                if (cloud == null || backendUserId() == null) {
                    _saveResult.emit(SaveResult.Error("主模型配置以云端为准：请先登录后再操作"))
                    return@launch
                }
                val state = _mainModelCloud.value[config.id]
                if (state == null) {
                    _saveResult.emit(SaveResult.Error("该主模型配置尚未发布到云端，请先保存后再启用"))
                    return@launch
                }
                when (state.state) {
                    MainModelState.PENDING -> _saveResult.emit(SaveResult.Error("上次主模型发布结果尚待确认，请先用原内容重新保存"))
                    MainModelState.ACTIVE -> {
                        cloud.deleteConfiguration(config.id)
                        syncMainConfigurations()
                        _saveResult.emit(SaveResult.Success("${config.provider.displayName} 已停用"))
                    }
                    MainModelState.DISABLED, MainModelState.UNAVAILABLE -> {
                        if (!state.keyConfigured) {
                            _saveResult.emit(SaveResult.Error("云端凭据不可用，请重新填写 API Key 后保存"))
                            return@launch
                        }
                        // 重新发布即启用；不带 Key 表示沿用云端已保存的凭据。
                        when (val published = cloud.publish(config.copy(apiKey = "").toChatModelDraft(config.id, ""))) {
                            is CloudChatModelPublish.Confirmed -> {
                                val activationProblem = try {
                                    cloud.activate(config.id)
                                    null
                                } catch (failure: Exception) {
                                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                                    failure.message ?: "请稍后重试"
                                }
                                syncMainConfigurations()
                                _saveResult.emit(SaveResult.Success(if (activationProblem == null) "${config.provider.displayName} 已启用" else "${config.provider.displayName} 已启用；账号默认切换未完成：$activationProblem"))
                            }
                            is CloudChatModelPublish.Rejected -> {
                                syncMainConfigurations()
                                _saveResult.emit(SaveResult.Error(published.reason))
                            }
                            is CloudChatModelPublish.Unknown -> {
                                syncMainConfigurations()
                                _saveResult.emit(SaveResult.Error("${published.reason}；请稍后用原内容重试以确认"))
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                SecureLog.e("SettingsViewModel", "Toggle config failed: ${failure.message}")
                _saveResult.emit(SaveResult.Error(failure.message ?: "操作失败", (failure as? MainChatConfigurationException)?.issue))
            }
        }
    }

    fun selectActiveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (config.kind != "MAIN") {
                    _saveResult.emit(SaveResult.Error("仅普通聊天模型可设为账号默认"))
                    return@launch
                }
                val cloud = chatModelCloud()
                if (cloud == null || backendUserId() == null) {
                    _saveResult.emit(SaveResult.Error("主模型配置以云端为准：请先登录后再操作"))
                    return@launch
                }
                val state = _mainModelCloud.value[config.id]
                if (state == null) {
                    _saveResult.emit(SaveResult.Error("该主模型配置尚未发布到云端，请先保存"))
                    return@launch
                }
                if (state.state == MainModelState.PENDING) {
                    _saveResult.emit(SaveResult.Error("上次主模型发布结果尚待确认，请先用原内容重新保存"))
                    return@launch
                }
                val result = cloud.activate(config.id)
                syncMainConfigurations()
                _saveResult.emit(
                    SaveResult.Success(
                        if (result.failed.isEmpty()) "已设为账号默认"
                        else "账号默认已切换，以下助手未生效：${result.failed.joinToString()}"
                    )
                )
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                SecureLog.e("SettingsViewModel", "Switch active config failed: ${failure.message}")
                _saveResult.emit(SaveResult.Error(failure.message ?: "切换失败", (failure as? MainChatConfigurationException)?.issue))
            }
        }
    }

    /**
     * 编辑已有配置但未重新输入密钥时沿用库内已解密的密钥：
     * 保存使用 REPLACE，空 apiKey 会把原配置顶掉。
     */
    private suspend fun mergeExistingSecret(config: ApiConfig): ApiConfig {
        if (config.apiKey.isNotBlank() || config.id <= 0L) return config
        val existing = runCatching { repository.getConfigById(config.id) }.getOrNull() ?: return config
        return if (existing.apiKey.isNotBlank()) config.copy(apiKey = existing.apiKey) else config
    }

    fun deleteConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (config.kind == "MAIN") {
                    deleteMainConfiguration(config)
                    return@launch
                }
                if (config.id > 0) repository.deleteConfigById(config.id) else repository.deleteConfig(config.provider)
                removeConnectionStatus(connectionKey(config))
            } catch (failure: Exception) {
                _saveResult.emit(SaveResult.Error(failure.message ?: "删除配置失败"))
            }
        }
    }

    /** 云端没有删除入口：已发布的主模型配置只能停用，只有未发布的本地草稿才直接丢弃。 */
    private suspend fun deleteMainConfiguration(config: ApiConfig) {
        if (config.id <= 0L) {
            _saveResult.emit(SaveResult.Error("该配置尚未保存"))
            return
        }
        val cloud = chatModelCloud()
        if (cloud == null || backendUserId() == null) {
            _saveResult.emit(SaveResult.Error("主模型配置以云端为准：请先登录后再操作"))
            return
        }
        if (!_mainModelCloud.value.containsKey(config.id)) {
            repository.deleteConfigById(config.id)
            removeConnectionStatus(connectionKey(config))
            _saveResult.emit(SaveResult.Success("未发布的主模型草稿已丢弃"))
            return
        }
        cloud.deleteConfiguration(config.id)
        syncMainConfigurations()
        _saveResult.emit(SaveResult.Success("主模型配置已停用"))
    }

    fun refreshMainConfigurations() {
        viewModelScope.launch(Dispatchers.IO) { syncMainConfigurations() }
    }

    /**
     * 云端主模型配置是列表/启停/默认的唯一来源：这里把云端结果落到展示缓存，
     * 并清理已发布配置的本地 Key；未登录或云端不可用时只显示缓存并给出原因。
     */
    private suspend fun syncMainConfigurations() {
        val cloud = chatModelCloud()
        if (cloud == null || backendUserId() == null) {
            _mainModelCloud.value = emptyMap()
            _mainModelNotice.value = "主模型配置以云端为准：请先登录后查看和修改，本机只显示上次的展示缓存。"
            return
        }
        try {
            val snapshot = cloud.configurations()
            val cache = repository.getAllConfigs().first().filter { it.kind == "MAIN" }.associateBy { it.id }
            val states = linkedMapOf<Long, MainModelCloudState>()
            for (configuration in snapshot.configurations) {
                var localId = configuration.localId
                val cached = cache[localId]
                val row = if (cached == null) {
                    val provider = apiProvider(configuration.provider)
                    ApiConfig(
                        provider = provider,
                        name = "",
                        apiKey = "",
                        baseUrl = configuration.baseUrl,
                        model = configuration.model,
                        temperature = configuration.temperature,
                        maxTokens = configuration.maxTokens,
                        isEnabled = configuration.status == "ACTIVE",
                        formatHint = cloudFormatHint(configuration.protocol),
                        kind = "MAIN",
                    )
                } else if (configuration.pending) {
                    // 上次发布结果未知：缓存行就是用户重试时要用的原内容（含加密草稿），
                    // 不能被云端旧版本覆盖，否则幂等确认会因内容不一致而无法重试。
                    cached
                } else {
                    cached.copy(
                        provider = apiProvider(configuration.provider),
                        baseUrl = configuration.baseUrl.ifBlank { cached.baseUrl },
                        model = configuration.model.ifBlank { cached.model },
                        temperature = configuration.temperature,
                        maxTokens = configuration.maxTokens,
                        formatHint = cloudFormatHint(configuration.protocol),
                        isEnabled = configuration.status == "ACTIVE",
                        // 云端已确认发布后不再长期保存 Key；未确认的命令保留加密草稿。
                        apiKey = "",
                        extraApiKeys = "",
                    )
                }
                if (cached == null) {
                    // 云端存在但本机没有对应缓存（换机/本地行早已删除）：新建一行并登记引用。
                    val inserted = repository.saveConfig(row)
                    try {
                        cloud.attach(inserted, configuration.configurationId)
                    } catch (failure: Exception) {
                        // 登记引用失败就回滚刚插入的展示行，避免下次同步重复插草稿。
                        repository.deleteConfigById(inserted)
                        throw failure
                    }
                    localId = inserted
                } else if (row != cached) {
                    repository.saveConfig(row)
                }
                states[localId] = MainModelCloudState(
                    isDefault = configuration.configurationId == snapshot.defaultConfigurationId,
                    state = mainModelState(configuration.status, configuration.pending),
                    keyConfigured = configuration.keyConfigured,
                )
            }
            // 尚未出现在云端列表里的本地行，若还有未确认命令则只能等待幂等确认，不能当成草稿删除。
            snapshot.pendingLocalIds.filter { it > 0 && it !in states }.forEach {
                states[it] = MainModelCloudState(isDefault = false, state = MainModelState.PENDING, keyConfigured = false)
            }
            // 旧安装遗留的本地启用行不再参与生效判定：云端没有确认的 MAIN 行只当草稿。
            cache.values.filter { it.id !in states && it.isEnabled }
                .forEach { repository.saveConfig(it.copy(isEnabled = false)) }
            _mainModelCloud.value = states
            _mainModelNotice.value = null
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            SecureLog.e("SettingsViewModel", "Refresh main model configurations failed: ${failure.message}")
            _mainModelNotice.value = "云端主模型配置读取失败：${failure.message ?: "请稍后重试"}；当前列表为本机展示缓存，修改前请先恢复云端连接。"
        }
    }

    private fun mainModelState(status: String, pending: Boolean): MainModelState = when {
        pending -> MainModelState.PENDING
        status == "ACTIVE" -> MainModelState.ACTIVE
        status == "DISABLED" -> MainModelState.DISABLED
        else -> MainModelState.UNAVAILABLE
    }

    private fun apiProvider(name: String): ApiProvider = ApiProvider.values().firstOrNull { it.name == name } ?: ApiProvider.CUSTOM

    private fun cloudFormatHint(protocol: String): String = when (protocol) {
        "ANTHROPIC_MESSAGES" -> "anthropic"
        "IFLYTEK_HTTP" -> "iflytek"
        else -> "openai"
    }

    fun refreshConnectionStatus() {
        statusSubscription.subscribe(repository.getAllConfigs()) { list ->
            list.forEach { config ->
                val key = connectionKey(config)
                val current = _connectionStatus.value[key]
                val status = when {
                    // Preserve current status — don't downgrade from known state
                    current?.status == ConnectionStatus.TESTING -> ConnectionResult(ConnectionStatus.TESTING, current.latencyMs)
                    current?.status == ConnectionStatus.CONNECTED -> ConnectionResult(ConnectionStatus.CONNECTED, current.latencyMs)
                    current?.status == ConnectionStatus.FAILED -> ConnectionResult(ConnectionStatus.FAILED, current.latencyMs, current.errorMessage)
                    // Only set from config if we don't already have a known state
                    config.connectionTested && current == null -> ConnectionResult(ConnectionStatus.HISTORICAL, config.latencyMs)
                    // Keep UNKNOWN only for fresh starts (no prior state)
                    current != null -> current
                    else -> ConnectionResult(ConnectionStatus.UNKNOWN)
                }
                updateConnectionStatus(key, status)
            }
        }
    }

    override fun onCleared() {
        statusSubscription.cancel()
        super.onCleared()
    }
}
