package com.xingshi.ai.feature.settings.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingshi.ai.feature.chat.VoiceCallPlayer
import com.xingshi.ai.feature.settings.ui.viewmodel.TtsSettingsViewModel
import com.xingshi.ai.plugins.api.TtsPreset
import com.xingshi.ai.theme.framework.themeText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    viewModel: TtsSettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val player = remember { VoiceCallPlayer() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelOperations()
            player.release()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importSample(uri)
    }

    fun play(path: String) {
        if (path.isBlank() || playing) return
        playing = true
        scope.launch {
            runCatching { player.play(path) }
            playing = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(themeText("ui_settings_tts_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = themeText("profile_back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.loading) {
                Text(themeText("plugin_loading"), color = colorScheme.onSurfaceVariant)
                return@Column
            }
            if (!state.pluginInstalled) {
                Text(themeText("plugin_unavailable"), color = colorScheme.onSurfaceVariant)
                return@Column
            }
            if (state.legacyConfig) {
                Text(
                    themeText("ui_tts_legacy_hint"),
                    color = colorScheme.error,
                    fontSize = 13.sp
                )
            }

            Text(themeText("ui_tts_provider"), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            state.presets.forEach { preset ->
                ProviderRow(
                    preset = preset,
                    selected = preset.id == state.providerId,
                    onSelect = { viewModel.selectProvider(preset.id) }
                )
            }

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::setApiKey,
                label = { Text(themeText("ui_tts_api_key")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val preset = state.presets.firstOrNull { it.id == state.providerId }
            if (preset != null && preset.regions.isNotEmpty()) {
                OutlinedTextField(
                    value = state.region,
                    onValueChange = viewModel::setRegion,
                    label = { Text(themeText("ui_tts_region")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.workspace,
                    onValueChange = viewModel::setWorkspace,
                    label = { Text(themeText("ui_tts_workspace")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))
            Text(themeText("ui_tts_mode"), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("preset", themeText("ui_tts_mode_preset"), state.mode, viewModel::selectMode)
                if (preset?.supportsClone == true) {
                    ModeChip("clone", themeText("ui_tts_mode_clone"), state.mode, viewModel::selectMode)
                }
                if (preset?.supportsDesign == true) {
                    ModeChip("design", themeText("ui_tts_mode_design"), state.mode, viewModel::selectMode)
                }
            }

            when (state.mode) {
                "clone" -> {
                    Text(
                        text = if (state.samplePath.isBlank()) {
                            themeText("ui_tts_sample_none")
                        } else {
                            state.sampleLabel.ifBlank { state.samplePath.substringAfterLast('/') }
                        },
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picker.launch("audio/*") }) {
                            Text(themeText("ui_tts_choose_sample"))
                        }
                        if (state.samplePath.isNotBlank()) {
                            TextButton(onClick = viewModel::clearSample) {
                                Text(themeText("ui_tts_clear_sample"))
                            }
                        }
                    }
                    Button(
                        enabled = !state.busy && state.samplePath.isNotBlank(),
                        onClick = viewModel::cloneVoice
                    ) { Text(themeText("ui_tts_clone_action")) }
                }
                "design" -> {
                    OutlinedTextField(
                        value = state.designPrompt,
                        onValueChange = viewModel::setDesignPrompt,
                        label = { Text(themeText("ui_tts_design_prompt")) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !state.busy && state.designPrompt.isNotBlank(),
                        onClick = viewModel::designVoice
                    ) { Text(themeText("ui_tts_design_action")) }
                }
                else -> {
                    val voices = state.voices.filter { it.providerId == state.providerId }
                    Text(themeText("ui_tts_voice"), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
                    voices.forEach { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { viewModel.selectPresetVoice(voice.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = voice.label,
                                color = if (voice.id == state.presetVoiceId) colorScheme.primary else colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (voice.id == state.presetVoiceId) {
                                Text(themeText("ui_tts_voice_selected"), color = colorScheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.previewText,
                onValueChange = viewModel::setPreviewText,
                label = { Text(themeText("ui_tts_preview_text")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, onClick = viewModel::audition) {
                    Text(themeText("ui_tts_audition"))
                }
                if (state.previewPath.isNotBlank()) {
                    TextButton(onClick = { play(state.previewPath) }, enabled = !playing) {
                        Text(if (playing) themeText("ui_tts_playing") else themeText("ui_tts_play_preview"))
                    }
                }
            }

            state.status?.let { Text(themeText("ui_tts_status_$it"), color = colorScheme.primary, fontSize = 13.sp) }
            state.error?.let { Text(themeText("ui_tts_error_$it"), color = colorScheme.error, fontSize = 13.sp) }
            if (state.errorDetail.isNotBlank()) {
                Text(state.errorDetail, color = colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (state.previewPath.isNotBlank()) {
                Text(
                    text = themeText("ui_tts_preview_ready"),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                if (state.confirmedSignature.isNotBlank()) {
                    Text(themeText("ui_tts_confirmed"), color = colorScheme.primary, fontSize = 12.sp)
                } else {
                    Button(
                        enabled = !state.busy,
                        onClick = viewModel::confirmPreview,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(themeText("ui_tts_confirm_use")) }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                enabled = !state.busy,
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) { Text(themeText("ui_tts_save")) }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderRow(preset: TtsPreset, selected: Boolean, onSelect: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = preset.verified, onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = preset.providerName,
            color = when {
                !preset.verified -> colorScheme.onSurfaceVariant
                selected -> colorScheme.primary
                else -> colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        when {
            !preset.verified -> Text(themeText("ui_tts_pending_verify"), color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
            selected -> Text(themeText("ui_tts_voice_selected"), color = colorScheme.primary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ModeChip(mode: String, label: String, current: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = mode == current,
        onClick = { onSelect(mode) },
        label = { Text(label, fontSize = 12.sp) }
    )
}
