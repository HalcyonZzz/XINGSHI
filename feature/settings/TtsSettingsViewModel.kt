package com.xingshi.ai.feature.settings.ui.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.api.TextToSpeechPlugin
import com.xingshi.ai.plugins.api.TtsCloneRequest
import com.xingshi.ai.plugins.api.TtsDesignRequest
import com.xingshi.ai.plugins.api.TtsPreset
import com.xingshi.ai.plugins.api.TtsRequest
import com.xingshi.ai.plugins.api.TtsResult
import com.xingshi.ai.plugins.api.TtsVoice
import com.xingshi.ai.plugins.api.TtsVoiceResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val pluginInstalled: Boolean = false,
        val presets: List<TtsPreset> = emptyList(),
        val voices: List<TtsVoice> = emptyList(),
        val providerId: String = "",
        val mode: String = "preset",
        val presetVoiceId: String = "",
        val samplePath: String = "",
        val sampleLabel: String = "",
        val designPrompt: String = "",
        val previewText: String = "你好，这就是试听效果，请确认后再保存。",
        val apiKey: String = "",
        val region: String = "",
        val workspace: String = "",
        val savedVoiceId: String = "",
        val pendingVoiceId: String = "",
        val pendingModel: String = "",
        val pendingRegion: String = "",
        val previewPath: String = "",
        val confirmedSignature: String = "",
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
        val errorDetail: String = "",
        val legacyConfig: Boolean = false
    )

    private val repository = ApiConfigRepository(AppDatabase.getDatabase(application).apiConfigDao())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var existingConfig: ApiConfig? = null
    private val activeOperations = mutableSetOf<Job>()
    private var operationSignature: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        val plugin = PluginFramework.manager()?.textToSpeechPlugin()
        if (plugin == null) {
            _state.value = UiState(loading = false, pluginInstalled = false)
            return
        }
        val config = repository.getTtsConfig()
        existingConfig = config
        val presets = plugin.ttsPresets
        val voices = plugin.ttsVoices
        val providerId = config?.provider?.let(::providerOf)?.takeIf { id -> presets.any { it.id == id } }
            ?: presets.firstOrNull { it.verified }?.id
            ?: presets.firstOrNull()?.id
            .orEmpty()
        val providerPreset = presets.firstOrNull { it.id == providerId }
        val storedMode = config?.voiceMode?.takeIf { it.isNotBlank() } ?: "preset"
        val mode = ttsModeFor(providerId, storedMode, voices, providerPreset)
        val restored = restoredTtsVoice(config)
        _state.value = UiState(
            loading = false,
            pluginInstalled = true,
            presets = presets,
            voices = voices,
            providerId = providerId,
            mode = mode,
            presetVoiceId = config?.presetVoiceId.orEmpty(),
            samplePath = config?.voiceSamplePath.orEmpty(),
            designPrompt = config?.voiceDesignPrompt.orEmpty(),
            apiKey = config?.apiKey.orEmpty(),
            region = config?.voiceRegion.orEmpty(),
            workspace = config?.voiceWorkspace.orEmpty(),
            savedVoiceId = restored.savedVoiceId,
            pendingVoiceId = restored.pendingVoiceId,
            pendingModel = restored.pendingModel,
            pendingRegion = restored.pendingRegion,
            legacyConfig = config != null && providerOf(config.provider).isBlank()
        )
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun selectProvider(providerId: String) {
        val preset = _state.value.presets.firstOrNull { it.id == providerId } ?: return
        if (!preset.verified) return
        val voices = PluginFramework.manager()?.textToSpeechPlugin()?.ttsVoices.orEmpty()
        _state.value = _state.value.copy(
            providerId = providerId,
            mode = ttsModeFor(providerId, _state.value.mode, voices, preset),
            voices = voices.filter { it.providerId.isEmpty() || it.providerId == providerId },
            presetVoiceId = "",
            savedVoiceId = "",
            previewPath = "",
            samplePath = "",
            sampleLabel = "",
            designPrompt = "",
            pendingVoiceId = "",
            pendingModel = "",
            pendingRegion = "",
            confirmedSignature = "",
            region = preset.regions.firstOrNull().orEmpty(),
            status = null,
            error = null,
            errorDetail = ""
        )
    }

    fun selectMode(mode: String) {
        val state = _state.value
        val preset = state.presets.firstOrNull { it.id == state.providerId }
        if (ttsModeFor(state.providerId, mode, state.voices, preset) != mode) {
            _state.value = state.copy(error = "voice_missing", errorDetail = "", status = null)
            return
        }
        _state.value = state.copy(
            mode = mode,
            previewPath = "",
            confirmedSignature = "",
            status = null,
            error = null,
            errorDetail = ""
        )
    }

    fun selectPresetVoice(voiceId: String) {
        _state.value = _state.value.copy(
            presetVoiceId = voiceId,
            previewPath = "",
            confirmedSignature = "",
            error = null,
            errorDetail = ""
        )
    }

    fun setApiKey(value: String) {
        _state.value = _state.value.copy(
            apiKey = value,
            previewPath = "",
            confirmedSignature = "",
            error = null,
            errorDetail = ""
        )
    }

    fun setRegion(value: String) {
        _state.value = _state.value.copy(
            region = value,
            previewPath = "",
            confirmedSignature = "",
            status = null,
            error = null,
            errorDetail = ""
        )
    }

    fun setWorkspace(value: String) {
        _state.value = _state.value.copy(
            workspace = value,
            previewPath = "",
            confirmedSignature = "",
            status = null,
            error = null,
            errorDetail = ""
        )
    }

    fun setDesignPrompt(value: String) {
        val restored = _state.value.pendingVoiceId.isNotEmpty() && _state.value.pendingVoiceId == existingConfig?.voiceId
        val changed = value != existingConfig?.voiceDesignPrompt.orEmpty()
        val keepPending = !(restored && changed)
        _state.value = _state.value.copy(
            designPrompt = value,
            previewPath = "",
            confirmedSignature = "",
            pendingVoiceId = if (keepPending) _state.value.pendingVoiceId else "",
            pendingModel = if (keepPending) _state.value.pendingModel else "",
            pendingRegion = if (keepPending) _state.value.pendingRegion else "",
            error = null,
            errorDetail = ""
        )
    }

    fun setPreviewText(value: String) {
        _state.value = _state.value.copy(
            previewText = value,
            previewPath = "",
            confirmedSignature = "",
            error = null,
            errorDetail = ""
        )
    }

    fun cancelOperations() {
        activeOperations.toList().forEach { it.cancel() }
        activeOperations.clear()
        _state.value = _state.value.copy(busy = false, status = null)
    }

    private fun launchOperation(block: suspend () -> Unit) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } finally {
                activeOperations.remove(currentCoroutineContext()[Job])
            }
        }
        activeOperations.add(job)
    }

    fun importSample(uri: Uri) {
        val app = getApplication<Application>()
        val preset = _state.value.presets.firstOrNull { it.id == _state.value.providerId }
        viewModelScope.launch(Dispatchers.IO) {
            if (preset == null) {
                _state.value = _state.value.copy(error = "preset_invalid", errorDetail = "")
                return@launch
            }
            val temporary = File(app.cacheDir, "tts_sample_${System.currentTimeMillis()}")
            val failure = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > preset.sampleMaxBytes) throw IllegalStateException("too_large")
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalStateException("read")
                null
            }.getOrElse { error ->
                when (error.message) {
                    "too_large" -> "sample_size"
                    else -> "sample_read_failed"
                }
            }
            if (failure != null) {
                temporary.delete()
                val detail = if (failure == "sample_size") "${preset.sampleMaxBytes / (1024 * 1024)}MB" else ""
                _state.value = _state.value.copy(error = failure, errorDetail = detail)
                return@launch
            }
            val format = sniffFormat(temporary)
            if (format.isBlank() || format !in preset.sampleFormats) {
                temporary.delete()
                _state.value = _state.value.copy(error = "sample_format", errorDetail = preset.sampleFormats.joinToString("/"))
                return@launch
            }
            if (temporary.length() > preset.sampleMaxBytes) {
                temporary.delete()
                _state.value = _state.value.copy(error = "sample_size", errorDetail = "${preset.sampleMaxBytes / (1024 * 1024)}MB")
                return@launch
            }
            val duration = durationOf(temporary)
            if (duration < preset.sampleMinDurationMs || duration > preset.sampleMaxDurationMs) {
                temporary.delete()
                _state.value = _state.value.copy(
                    error = "sample_duration",
                    errorDetail = "${preset.sampleMinDurationMs / 1000}-${preset.sampleMaxDurationMs / 1000}s"
                )
                return@launch
            }
            val dir = File(app.filesDir, "tts_samples").apply { mkdirs() }
            val target = File(dir, "sample_${System.currentTimeMillis()}.$format")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            val previous = _state.value.samplePath
            _state.value = _state.value.copy(
                samplePath = target.absolutePath,
                sampleLabel = "${target.name} · ${duration / 1000}s",
                previewPath = "",
                confirmedSignature = "",
                pendingVoiceId = "",
                pendingModel = "",
                pendingRegion = "",
                error = null,
                errorDetail = ""
            )
            if (previous.isNotBlank() && previous != existingConfig?.voiceSamplePath && previous != target.absolutePath) {
                runCatching { File(previous).delete() }
            }
        }
    }

    fun clearSample() {
        val previous = _state.value.samplePath
        val current = existingConfig?.voiceSamplePath.orEmpty()
        _state.value = _state.value.copy(
            samplePath = "",
            sampleLabel = "",
            previewPath = "",
            confirmedSignature = "",
            pendingVoiceId = "",
            error = null
        )
        if (previous.isNotBlank() && previous != current) runCatching { File(previous).delete() }
    }

    fun audition() {
        val state = _state.value
        val preset = state.presets.firstOrNull { it.id == state.providerId }
        if (preset == null) {
            _state.value = state.copy(error = "preset_invalid", errorDetail = "")
            return
        }
        if (state.apiKey.isBlank()) {
            _state.value = state.copy(error = "api_key_missing", errorDetail = "")
            return
        }
        if (state.mode == "clone" && state.samplePath.isBlank()) {
            _state.value = state.copy(error = "sample_missing", errorDetail = "")
            return
        }
        if (state.mode == "design" && state.designPrompt.isBlank()) {
            _state.value = state.copy(error = "prompt_missing", errorDetail = "")
            return
        }
        val missingVoice = auditionVoiceIssue(state.mode, preset.id, state.pendingVoiceId)
        if (missingVoice != null) {
            _state.value = state.copy(error = missingVoice, errorDetail = "")
            return
        }
        val bindingIssue = voiceBindingIssue(state.mode, preset.id, state.pendingVoiceId, state.pendingModel)
        if (bindingIssue != null) {
            _state.value = state.copy(error = bindingIssue, errorDetail = "")
            return
        }
        val previewVoice = auditionVoice(state.mode, state.presetVoiceId, state.pendingVoiceId, preset.defaultVoice)
        if (previewVoice.isBlank()) {
            _state.value = state.copy(error = "voice_missing", errorDetail = "")
            return
        }
        val pluginId = PluginFramework.manager()?.textToSpeechPluginId() ?: return
        val version = PluginFramework.manager()?.capabilityVersion(pluginId) ?: return
        val text = state.previewText.ifBlank { "你好，这就是试听效果。" }
        val model = voiceModelFor(state.mode, preset.defaultModel, state.pendingModel)
        val signatureAtStart = signatureOf(state)
        launchOperation {
            _state.value = _state.value.copy(busy = true, error = null, errorDetail = "", status = "auditioning", previewPath = "")
            val request = TtsRequest(
                text = text,
                endpoint = preset.endpoint,
                apiKey = state.apiKey,
                model = model,
                voice = if (state.mode == "preset") previewVoice else "",
                responseFormat = preset.responseFormat,
                outputDir = previewDir(),
                providerId = preset.id,
                voiceMode = state.mode,
                voiceId = if (state.mode == "preset") "" else state.pendingVoiceId,
                voiceSamplePath = state.samplePath,
                voiceDesignPrompt = state.designPrompt,
                voiceRegion = state.pendingRegion.ifBlank { state.region },
                voiceWorkspace = state.workspace
            )
            val result = tracked(pluginId, version) { plugin -> plugin.synthesize(request) }
            if (signatureOf(_state.value) != signatureAtStart) {
                _state.value = _state.value.copy(busy = false, status = null)
                return@launchOperation
            }
            if (result == null) {
                _state.value = _state.value.copy(busy = false, status = null, error = "plugin_missing", errorDetail = "")
                return@launchOperation
            }
            _state.value = when (result) {
                is TtsResult.Success -> _state.value.copy(
                    busy = false,
                    status = "audition_ready",
                    previewPath = result.audioPath,
                    confirmedSignature = ""
                )
                is TtsResult.Failure -> _state.value.copy(
                    busy = false,
                    status = null,
                    error = result.code.ifBlank { "provider_failed" },
                    errorDetail = if (result.code.isBlank()) result.reason else ""
                )
                else -> _state.value.copy(busy = false, status = null, error = "operation_failed", errorDetail = "")
            }
        }
    }

    fun cloneVoice() {
        val state = _state.value
        val preset = state.presets.firstOrNull { it.id == state.providerId }
        if (preset == null || !preset.supportsClone) {
            _state.value = state.copy(error = "clone_unsupported", errorDetail = "")
            return
        }
        if (state.apiKey.isBlank()) {
            _state.value = state.copy(error = "api_key_missing", errorDetail = "")
            return
        }
        if (state.samplePath.isBlank()) {
            _state.value = state.copy(error = "sample_missing", errorDetail = "")
            return
        }
        val pluginId = PluginFramework.manager()?.textToSpeechPluginId() ?: return
        val version = PluginFramework.manager()?.capabilityVersion(pluginId) ?: return
        val signatureAtStart = signatureOf(state)
        launchOperation {
            _state.value = _state.value.copy(busy = true, error = null, errorDetail = "", status = "cloning", previewPath = "")
            val request = TtsCloneRequest(
                samplePath = state.samplePath,
                mimeType = mimeOf(state.samplePath),
                providerId = preset.id,
                endpoint = preset.endpoint,
                apiKey = state.apiKey,
                model = preset.defaultModel,
                displayName = "星识复刻音色",
                region = state.region,
                workspace = state.workspace,
                outputDir = previewDir()
            )
            operationSignature = signatureAtStart
            val result = tracked(pluginId, version) { plugin -> plugin.cloneVoice(request) }
            try {
                applyVoiceResult(result, successStatus = "clone_ready")
            } finally {
                operationSignature = null
            }
        }
    }

    fun designVoice() {
        val state = _state.value
        val preset = state.presets.firstOrNull { it.id == state.providerId }
        if (preset == null || !preset.supportsDesign) {
            _state.value = state.copy(error = "design_unsupported", errorDetail = "")
            return
        }
        if (state.apiKey.isBlank()) {
            _state.value = state.copy(error = "api_key_missing", errorDetail = "")
            return
        }
        if (state.designPrompt.isBlank()) {
            _state.value = state.copy(error = "prompt_missing", errorDetail = "")
            return
        }
        val pluginId = PluginFramework.manager()?.textToSpeechPluginId() ?: return
        val version = PluginFramework.manager()?.capabilityVersion(pluginId) ?: return
        val signatureAtStart = signatureOf(state)
        launchOperation {
            _state.value = _state.value.copy(busy = true, error = null, errorDetail = "", status = "designing", previewPath = "")
            val request = TtsDesignRequest(
                prompt = state.designPrompt,
                previewText = state.previewText,
                providerId = preset.id,
                endpoint = preset.endpoint,
                apiKey = state.apiKey,
                model = preset.defaultModel,
                region = state.region,
                workspace = state.workspace,
                outputDir = previewDir()
            )
            operationSignature = signatureAtStart
            val result = tracked(pluginId, version) { plugin -> plugin.designVoice(request) }
            try {
                applyVoiceResult(result, successStatus = "design_ready")
            } finally {
                operationSignature = null
            }
        }
    }

    private fun applyVoiceResult(result: TtsVoiceResult?, successStatus: String) {
        val expected = operationSignature
        if (expected == null || signatureOf(_state.value) != expected) {
            _state.value = _state.value.copy(busy = false, status = null)
            return
        }
        _state.value = when (result) {
            null -> _state.value.copy(busy = false, status = null, error = "plugin_missing", errorDetail = "")
            is TtsVoiceResult.Success -> _state.value.copy(
                busy = false,
                status = successStatus,
                error = null,
                errorDetail = "",
                pendingVoiceId = result.voiceId,
                pendingModel = result.model,
                pendingRegion = result.region.ifBlank { _state.value.region },
                previewPath = result.previewPath,
                confirmedSignature = ""
            )
            is TtsVoiceResult.Failure -> _state.value.copy(
                busy = false,
                status = null,
                error = result.code.ifBlank { "provider_failed" },
                errorDetail = if (result.code.isBlank()) result.reason else ""
            )
        }
    }

    fun confirmPreview() {
        val state = _state.value
        if (state.previewPath.isBlank()) {
            _state.value = state.copy(error = "confirm_required", errorDetail = "")
            return
        }
        _state.value = state.copy(
            confirmedSignature = signatureOf(state),
            status = "preview_confirmed",
            error = null,
            errorDetail = ""
        )
    }

    fun save() {
        val state = _state.value
        val preset = state.presets.firstOrNull { it.id == state.providerId }
        if (preset == null) {
            _state.value = state.copy(error = "preset_invalid", errorDetail = "")
            return
        }
        if (state.apiKey.isBlank()) {
            _state.value = state.copy(error = "api_key_missing", errorDetail = "")
            return
        }
        when (state.mode) {
            "preset" -> if (state.presetVoiceId.ifBlank { preset.defaultVoice }.isBlank()) {
                _state.value = state.copy(error = "preset_invalid", errorDetail = "")
                return
            }
            "clone" -> if (state.samplePath.isBlank()) {
                _state.value = state.copy(error = "sample_missing", errorDetail = "")
                return
            }
            "design" -> if (state.designPrompt.isBlank()) {
                _state.value = state.copy(error = "prompt_missing", errorDetail = "")
                return
            }
        }
        val bindingIssue = voiceBindingIssue(state.mode, preset.id, state.pendingVoiceId, state.pendingModel)
        if (bindingIssue != null) {
            _state.value = state.copy(error = bindingIssue, errorDetail = "")
            return
        }
        val model = voiceModelFor(state.mode, preset.defaultModel, state.pendingModel)
        if (state.previewPath.isBlank() || state.confirmedSignature != signatureOf(state)) {
            _state.value = state.copy(error = "confirm_required", errorDetail = "")
            return
        }
        val repositoryProvider = providerFor(state.providerId)
        if (repositoryProvider == null) {
            _state.value = state.copy(error = "preset_invalid", errorDetail = "")
            return
        }
        launchOperation {
            _state.value = _state.value.copy(busy = true, error = null, errorDetail = "", status = "saving")
            val previous = repository.getTtsConfig()
            val merged = (previous ?: ApiConfig(
                provider = repositoryProvider,
                apiKey = "",
                baseUrl = preset.endpoint,
                model = preset.defaultModel,
                kind = "TTS",
                name = "语音合成"
            )).copy(
                provider = repositoryProvider,
                apiKey = state.apiKey,
                baseUrl = preset.endpoint,
                model = preset.defaultModel,
                kind = "TTS",
                name = preset.providerName,
                voiceMode = state.mode,
                presetVoiceId = if (state.mode == "preset") state.presetVoiceId.ifBlank { preset.defaultVoice } else "",
                voiceDesignPrompt = if (state.mode == "design") state.designPrompt else "",
                voiceId = if (state.mode == "preset") "" else state.pendingVoiceId,
                voiceSamplePath = if (state.mode == "clone") state.samplePath else "",
                voiceModel = model,
                voiceRegion = if (state.mode == "preset") "" else state.pendingRegion.ifBlank { state.region },
                voiceWorkspace = state.workspace
            )
            try {
                val savedId = repository.saveConfig(merged)
                repository.deleteTtsConfigsExcept(savedId)
                cleanupFiles(previous, state)
                existingConfig = merged.copy(id = savedId)
                _state.value = _state.value.copy(
                    busy = false,
                    status = "saved",
                    error = null,
                    errorDetail = "",
                    previewPath = "",
                    confirmedSignature = "",
                    savedVoiceId = merged.voiceId,
                    legacyConfig = false
                )
            } catch (e: Exception) {
                SecureLog.e("TtsSettingsViewModel", "save failed: ${e.message}")
                _state.value = _state.value.copy(busy = false, status = null, error = "save_failed", errorDetail = "")
            }
        }
    }

    private fun cleanupFiles(previous: ApiConfig?, state: UiState) {
        val keep = setOf(previous?.voiceSamplePath.orEmpty(), state.samplePath)
        File(getApplication<Application>().filesDir, "tts_samples").listFiles()?.forEach { file ->
            if (file.absolutePath !in keep) runCatching { file.delete() }
        }
        previewDir().listFiles()?.forEach { file ->
            if (file.absolutePath != state.previewPath) runCatching { file.delete() }
        }
    }

    private suspend fun <T> tracked(pluginId: String, version: String, block: suspend (TextToSpeechPlugin) -> T): T? {
        val manager = PluginFramework.manager() ?: return null
        val plugin = manager.textToSpeechPlugin() ?: return null
        val gate = Job(currentCoroutineContext()[Job])
        if (!manager.trackOptionalExecution(pluginId, version, gate)) {
            gate.cancel()
            return null
        }
        return try {
            coroutineScope {
                val call = async(context = gate, start = CoroutineStart.LAZY) { block(plugin) }
                call.start()
                call.await()
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            manager.untrackOptionalExecution(pluginId, gate)
            gate.complete()
        }
    }

    private fun signatureOf(state: UiState): String = listOf(
        state.providerId,
        state.mode,
        state.presetVoiceId,
        state.samplePath,
        state.designPrompt,
        state.previewText,
        state.apiKey,
        state.region,
        state.workspace,
        state.pendingVoiceId,
        state.pendingModel
    ).joinToString("|")

    private fun previewDir(): File =
        File(getApplication<Application>().filesDir, "tts_preview").apply { mkdirs() }

    private fun extensionOf(mimeType: String): String = when {
        mimeType.contains("wav") -> "wav"
        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
        mimeType.contains("m4a") || mimeType.contains("mp4") -> "m4a"
        mimeType.contains("aac") -> "aac"
        mimeType.contains("ogg") -> "ogg"
        else -> ""
    }

    private fun sniffFormat(file: File): String = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            if (read >= 12) {
                when {
                    header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() -> "wav"
                    header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte() -> "mp3"
                    header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                    header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> "m4a"
                    header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> "ogg"
                    else -> ""
                }
            } else {
                ""
            }
        }
    }.getOrDefault("")

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    private fun durationOf(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    internal fun providerOf(provider: ApiProvider): String = when (provider) {
        ApiProvider.XIAOMI -> "xiaomi"
        ApiProvider.MINIMAX -> "minimax"
        ApiProvider.ELEVENLABS -> "elevenlabs"
        ApiProvider.ALIYUN -> "aliyun"
        else -> ""
    }

    private fun providerFor(presetId: String): ApiProvider? = when (presetId) {
        "xiaomi" -> ApiProvider.XIAOMI
        "minimax" -> ApiProvider.MINIMAX
        "elevenlabs" -> ApiProvider.ELEVENLABS
        "aliyun" -> ApiProvider.ALIYUN
        else -> null
    }
}

internal data class RestoredTtsVoice(
    val savedVoiceId: String,
    val pendingVoiceId: String,
    val pendingModel: String,
    val pendingRegion: String
)

internal fun restoredTtsVoice(config: ApiConfig?): RestoredTtsVoice {
    val voiceId = config?.voiceId.orEmpty()
    return RestoredTtsVoice(
        savedVoiceId = voiceId,
        pendingVoiceId = voiceId,
        pendingModel = config?.voiceModel.orEmpty(),
        pendingRegion = config?.voiceRegion.orEmpty()
    )
}

internal fun auditionVoiceIssue(mode: String, providerId: String, pendingVoiceId: String): String? =
    if (mode != "preset" && pendingVoiceId.isBlank() && providerId != "xiaomi") {
        if (mode == "clone") "clone_required" else "design_required"
    } else {
        null
    }

internal fun voiceModelFor(mode: String, presetModel: String, pendingModel: String): String =
    if (mode == "preset") presetModel else pendingModel

internal fun voiceBindingIssue(mode: String, providerId: String, voiceId: String, model: String): String? {
    if (mode == "preset" || providerId == "xiaomi") return null
    if (voiceId.isBlank()) return if (mode == "design") "design_required" else "clone_required"
    if (model.isBlank()) return "voice_missing"
    return null
}

internal fun ttsModeFor(providerId: String, requested: String, voices: List<TtsVoice>, preset: TtsPreset?): String {
    if (requested != "preset") return requested
    if (voices.any { it.providerId.isEmpty() || it.providerId == providerId }) return requested
    return when {
        preset?.supportsClone == true -> "clone"
        preset?.supportsDesign == true -> "design"
        else -> requested
    }
}

internal fun auditionVoice(mode: String, presetVoiceId: String, pendingVoiceId: String, defaultVoice: String): String =
    if (mode == "preset") presetVoiceId.ifBlank { defaultVoice } else pendingVoiceId.ifBlank { defaultVoice }
