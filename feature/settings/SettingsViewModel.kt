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

    
    
    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionResult>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionResult>> = _connectionStatus.asStateFlow()
    private val connectionStatusMap = java.util.concurrent.ConcurrentHashMap<String, ConnectionResult>()

     
    private fun updateConnectionStatus(key: String, value: ConnectionResult) {
        connectionStatusMap[key] = value
        _connectionStatus.value = connectionStatusMap.toMap()
    }
    private fun removeConnectionStatus(key: String) {
        connectionStatusMap.remove(key)
        _connectionStatus.value = connectionStatusMap.toMap()
    }

    
    private val _saveResult = MutableSharedFlow<SaveResult>(extraBufferCapacity = 8)
    val saveResult: SharedFlow<SaveResult> = _saveResult

    private val _contextLimit = MutableStateFlow(50)
    val contextLimit: StateFlow<Int> = _contextLimit.asStateFlow()

    private val _innerThoughtEnabled = MutableStateFlow(false)
    val innerThoughtEnabled: StateFlow<Boolean> = _innerThoughtEnabled.asStateFlow()

     
    enum class MainModelState { ACTIVE, DISABLED, UNAVAILABLE, PENDING }
    data class MainModelCloudState(
        val isDefault: Boolean,
        val state: MainModelState,
         
        val keyConfigured: Boolean = true
    )

    private val _mainModelCloud = MutableStateFlow<Map<Long, MainModelCloudState>>(emptyMap())
    val mainModelCloud: StateFlow<Map<Long, MainModelCloudState>> = _mainModelCloud.asStateFlow()

    private val _mainModelNotice = MutableStateFlow<String?>(null)

     
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

     
    private suspend fun publishMainConfiguration(config: ApiConfig) {
        val cloud = chatModelCloud()
        if (cloud == null || backendUserId() == null) {
            _saveResult.emit(SaveResult.Error("主模型配置以云端为准：请先登录后再保存"))
            return
        }
        val known = config.id > 0 && _mainModelCloud.value.containsKey(config.id)
        
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

     
    private suspend fun saveAuxiliaryConfiguration(config: ApiConfig) {
        val key = connectionKey(config)
        val draftStatus = _connectionStatus.value[key]
        
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

     
    fun sttPreset(): SpeechPreset? =
        PluginFramework.manager()?.speechRecognitionPlugin()?.speechPresets?.firstOrNull()

    fun saveSttConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                
                val merged = mergeExistingSecret(config).copy(kind = "STT")
                val error = ApiConfigValidator.validateStt(merged)
                if (error != null) {
                    _saveResult.emit(SaveResult.Error(error))
                    return@launch
                }
                val savedId = repository.saveConfig(merged)
                
                repository.deleteSttConfigsExcept(savedId)
                _saveResult.emit(SaveResult.Success("语音识别配置已保存"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save STT config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

     
    fun ttsVoices(): List<com.xingshi.ai.plugins.api.TtsVoice> =
        PluginFramework.manager()?.textToSpeechPlugin()?.ttsVoices.orEmpty()

     
    fun ttsPreset(): TtsPreset? =
        PluginFramework.manager()?.textToSpeechPlugin()?.ttsPresets?.firstOrNull { it.endpoint.isNotBlank() }

    fun saveTtsConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                
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
                        
                        apiKey = "",
                        extraApiKeys = "",
                    )
                }
                if (cached == null) {
                    
                    val inserted = repository.saveConfig(row)
                    try {
                        cloud.attach(inserted, configuration.configurationId)
                    } catch (failure: Exception) {
                        
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
            
            snapshot.pendingLocalIds.filter { it > 0 && it !in states }.forEach {
                states[it] = MainModelCloudState(isDefault = false, state = MainModelState.PENDING, keyConfigured = false)
            }
            
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
                    
                    current?.status == ConnectionStatus.TESTING -> ConnectionResult(ConnectionStatus.TESTING, current.latencyMs)
                    current?.status == ConnectionStatus.CONNECTED -> ConnectionResult(ConnectionStatus.CONNECTED, current.latencyMs)
                    current?.status == ConnectionStatus.FAILED -> ConnectionResult(ConnectionStatus.FAILED, current.latencyMs, current.errorMessage)
                    
                    config.connectionTested && current == null -> ConnectionResult(ConnectionStatus.HISTORICAL, config.latencyMs)
                    
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
