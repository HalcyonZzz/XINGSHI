package com.xingshi.ai.feature.companion.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.xingshi.ai.themes.official.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.common.CompanionRole
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.feature.companion.ui.viewmodel.CreateCompanionViewModel
import kotlinx.coroutines.delay
import com.xingshi.ai.theme.framework.themeText

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

@Composable
fun CreateCompanionScreen(
    companionId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: CreateCompanionViewModel = viewModel()
) {
    val context = LocalContext.current
    val isEditMode = companionId != null
    val existing by viewModel.existingCompanion.collectAsState()
    val saveCompleted by viewModel.saveCompleted.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    var pickedAvatar by remember { mutableStateOf<String?>(null) }
    var importedPrompt by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf("") }
    var saveBlocked by remember { mutableStateOf(false) }
    val deleteCompleted by viewModel.deleteCompleted.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    val loaded = !isEditMode || existing != null

    // 装载原角色与操作结果不依赖官方绘制：作者覆盖编辑页也能读到原数据并呈现保存状态。
    LaunchedEffect(companionId) {
        if (companionId != null) viewModel.loadCompanion(companionId)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { pickedAvatar = it.toString() }
    }
    val promptFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { fileUri ->
            runCatching {
                context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText().orEmpty() }
            }.onSuccess { content ->
                if (content.isNotBlank()) importedPrompt = content else importError = "文件内容为空"
            }.onFailure { importError = "读取文件失败" }
        }
    }

    val page = if (isEditMode) ThemePages.EDIT_COMPANION else ThemePages.CREATE_COMPANION
    ThemePageSlot(
        page = page,
        host = {
            ThemePageHost(
                page = page,
                items = emptyList(),
                state = mapOf(
                    "companion_id" to (companionId ?: 0L).toString(),
                    "is_edit" to isEditMode.toString(),
                    "name" to existing?.name.orEmpty(),
                    "age" to existing?.age?.toString().orEmpty(),
                    "personality" to existing?.personality.orEmpty(),
                    "backstory" to existing?.backstory.orEmpty(),
                    "speaking_style" to existing?.speakingStyle.orEmpty(),
                    "tags" to existing?.tags.orEmpty(),
                    "raw_prompt" to (importedPrompt ?: existing?.rawPrompt.orEmpty()),
                    "system_prompt" to existing?.systemPrompt.orEmpty(),
                    "avatar_url" to (pickedAvatar ?: existing?.avatarUrl.orEmpty()),
                    "is_saving" to isSaving.toString(),
                    "save_completed" to saveCompleted.toString(),
                    "save_error" to saveError.orEmpty(),
                    "import_error" to importError,
                    "loaded" to loaded.toString(),
                    "save_blocked" to saveBlocked.toString(),
                    "delete_completed" to deleteCompleted.toString(),
                    "delete_error" to deleteError.orEmpty()
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "pick_avatar" -> avatarPicker.launch("image/*")
                            "import_prompt" -> promptFilePicker.launch("text/*")
                            "save_companion" -> action.payload.let { payload ->
                                if (isEditMode && existing == null) {
                                    // 原对象尚未装载成功时禁止用空实体整行更新，避免清空其他字段
                                    saveBlocked = true
                                    return@let
                                }
                                saveBlocked = false
                                val base = existing ?: CompanionEntity(name = "", personality = "")
                                val entity = base.copy(
                                    id = companionId ?: 0L,
                                    name = payload["name"] ?: base.name,
                                    avatarUrl = payload["avatar_url"] ?: pickedAvatar ?: base.avatarUrl,
                                    age = payload["age"]?.toIntOrNull() ?: base.age,
                                    personality = payload["personality"] ?: base.personality,
                                    backstory = payload["backstory"] ?: base.backstory,
                                    speakingStyle = payload["speaking_style"] ?: base.speakingStyle,
                                    tags = payload["tags"] ?: base.tags,
                                    rawPrompt = payload["raw_prompt"] ?: importedPrompt ?: base.rawPrompt,
                                    systemPrompt = payload["system_prompt"] ?: base.systemPrompt,
                                    intimacy = payload["intimacy"]?.toIntOrNull() ?: base.intimacy
                                )
                                viewModel.saveCompanion(entity, isEditMode)
                            }
                            "consume_save_error" -> viewModel.consumeSaveError()
                            "reset_save_completed" -> viewModel.resetSaveCompleted()
                            "delete_companion" -> {
                                if (isEditMode && existing == null) {
                                    saveBlocked = true
                                } else {
                                    existing?.let(viewModel::deleteCompanion)
                                }
                            }
                            "reset_delete_completed" -> viewModel.resetDeleteCompleted()
                            "consume_delete_error" -> viewModel.consumeDeleteError()
                            "back" -> onNavigateBack()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            "back" -> onNavigateBack()
                        }
                    }
                }
            )
        },
        fallback = { CreateCompanionScreenContent(companionId = companionId, onNavigateBack = onNavigateBack, viewModel = viewModel) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCompanionScreenContent(
    companionId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: CreateCompanionViewModel = viewModel()
) {
    val context = LocalContext.current
    val isEditMode = companionId != null
    val existingCompanion by viewModel.existingCompanion.collectAsState()
    val globalRole by viewModel.selectedRole.collectAsState()

    var role by remember { mutableStateOf(globalRole) }
    var name by remember { mutableStateOf("") }
    var rawPrompt by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showImportErrorDialog by remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf("") }

    // 监听 saveCompleted 跳转
    val saveCompleted by viewModel.saveCompleted.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    LaunchedEffect(saveCompleted) {
        if (saveCompleted) {
            viewModel.resetSaveCompleted()
            onNavigateBack()
        }
    }

    LaunchedEffect(saveError) {
        saveError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeSaveError()
        }
    }

    fun populateExistingCompanion(companion: CompanionEntity) {
        name = companion.name
        rawPrompt = companion.rawPrompt.orEmpty()
        systemPrompt = companion.systemPrompt.orEmpty()
        avatarUri = companion.avatarUrl
    }

    fun handleImportError(message: String) {
        importErrorMessage = message
        showImportErrorDialog = true
    }

    fun updateRawPromptFromImport(content: String) {
        rawPrompt = content
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { avatarUri = it.toString() }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            try {
                context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { reader ->
                    val content = reader?.readText().orEmpty()
                    if (content.isNotBlank()) {
                        updateRawPromptFromImport(content)
                    } else {
                        handleImportError("文件内容为空")
                    }
                }
            } catch (e: Exception) {
                handleImportError("读取文件失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (isEditMode && companionId != null) {
            viewModel.loadCompanion(companionId)
        }
        delay(100)
        isVisible = true
    }

    LaunchedEffect(existingCompanion) {
        existingCompanion?.let(::populateExistingCompanion)
    }

    // 非编辑模式下，全局角色切换时跟随变化
    LaunchedEffect(globalRole) {
        if (!isEditMode && existingCompanion == null) {
            role = globalRole
        }
    }

    val isFormValid = name.isNotBlank() && rawPrompt.isNotBlank()
    val buttonScale by animateFloatAsState(
        targetValue = if (isFormValid) 1f else 0.95f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "buttonScale"
    )

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = with(density) { imeInsets.getBottom(density) > 0 }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val accentColor = when (role) {
        CompanionRole.GIRLFRIEND -> Color(0xFF7BA3C6)
        CompanionRole.BOYFRIEND -> Color(0xFF7BA3C6)
    }
    val accentGradient = when (role) {
        CompanionRole.GIRLFRIEND -> listOf(
            Color(0xFF7BA3C6).copy(alpha = 0.9f),
            Color(0xFFFF8FB3).copy(alpha = 0.8f)
        )
        CompanionRole.BOYFRIEND -> listOf(
            Color(0xFF7BA3C6).copy(alpha = 0.9f),
            Color(0xFF6BA5E7).copy(alpha = 0.8f)
        )
    }
    val roleIcon: ImageVector = when (role) {
        CompanionRole.GIRLFRIEND -> Icons.Filled.Favorite
        CompanionRole.BOYFRIEND -> Icons.Filled.Shield
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (isEditMode) {
                            themeText("edit_companion_assistant")
                        } else {
                            themeText("create_companion_assistant")
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        ),
                        color = colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = themeText("cancel"),
                            tint = colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = themeText("companion_delete"),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colorScheme.background
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(top = paddingValues.calculateTopPadding())
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.8f),
                                            accentColor.copy(alpha = 0.5f)
                                        )
                                    )
                                )
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUri != null) {
                                AsyncImage(
                                    model = avatarUri,
                                    contentDescription = themeText("add_avatar"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = roleIcon,
                                        contentDescription = themeText("add_avatar"),
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = themeText("select_avatar"),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (name.isBlank()) {
                                when (role) {
                                    CompanionRole.GIRLFRIEND -> themeText("name_hint_girlfriend")
                                    CompanionRole.BOYFRIEND -> themeText("name_hint_boyfriend")
                                }
                            } else name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = if (name.isBlank()) colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else colorScheme.onSurface
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // 角色类型选择
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 80)) +
                            slideInVertically(tween(400, delayMillis = 80)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = themeText("role_type"),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                ),
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RoleToggleChip(
                                    role = CompanionRole.BOYFRIEND,
                                    icon = Icons.Filled.Shield,
                                    label = themeText("role_boyfriend"),
                                    accentColor = Color(0xFF7BA3C6),
                                    selected = role == CompanionRole.BOYFRIEND,
                                    onClick = { role = CompanionRole.BOYFRIEND },
                                    modifier = Modifier.weight(1f)
                                )
                                RoleToggleChip(
                                    role = CompanionRole.GIRLFRIEND,
                                    icon = Icons.Filled.Favorite,
                                    label = themeText("role_girlfriend"),
                                    accentColor = Color(0xFF7BA3C6),
                                    selected = role == CompanionRole.GIRLFRIEND,
                                    onClick = { role = CompanionRole.GIRLFRIEND },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 100,
                    label = themeText("name_label"),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = when (role) {
                        CompanionRole.GIRLFRIEND -> themeText("name_placeholder_girlfriend")
                        CompanionRole.BOYFRIEND -> themeText("name_placeholder_boyfriend")
                    },
                    imeAction = ImeAction.Next,
                    accentColor = accentColor
                )

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 220)) +
                            slideInVertically(tween(400, delayMillis = 220)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = themeText("role_setting"),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    ),
                                    color = colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { filePicker.launch("*/*") }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FileOpen,
                                            contentDescription = themeText("import_from_file"),
                                            tint = accentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = themeText("import_from_file"),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp
                                            ),
                                            color = accentColor
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = rawPrompt,
                                onValueChange = { rawPrompt = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor.copy(alpha = 0.4f),
                                    unfocusedBorderColor = colorScheme.outline,
                                    focusedLabelColor = accentColor.copy(alpha = 0.6f),
                                    unfocusedLabelColor = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    focusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    focusedTextColor = colorScheme.onSurface,
                                    unfocusedTextColor = colorScheme.onSurface
                                ),
                                minLines = 6,
                                maxLines = 10,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 350)) +
                            slideInVertically(tween(500, delayMillis = 350)) { it / 2 }
                ) {
                    Button(
                        onClick = {
                            if (isFormValid) {
                                val base = existingCompanion
                                // 编辑态必须等原对象装载完成再保存，避免用空实体覆盖整行。
                                if (isEditMode && base == null) return@Button
                                val companion = if (base != null) {
                                    base.copy(
                                        name = name.trim(),
                                        avatarUrl = avatarUri ?: base.avatarUrl,
                                        personality = if (rawPrompt == base.rawPrompt.orEmpty()) base.personality else rawPrompt.trim(),
                                        rawPrompt = rawPrompt.trim(),
                                        systemPrompt = systemPrompt.trim().takeIf { it.isNotBlank() } ?: base.systemPrompt
                                    )
                                } else {
                                    CompanionEntity(
                                        id = companionId ?: 0,
                                        name = name.trim(),
                                        avatarUrl = avatarUri,
                                        personality = rawPrompt.trim(),
                                        backstory = null,
                                        speakingStyle = null,
                                        tags = null,
                                        rawPrompt = rawPrompt.trim(),
                                        systemPrompt = systemPrompt.trim().takeIf { it.isNotBlank() }
                                    )
                                }
                                viewModel.saveCompanion(companion, isEditMode)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(buttonScale),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        enabled = isFormValid && !isSaving,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isFormValid && !isSaving) {
                                        Brush.horizontalGradient(colors = accentGradient)
                                    } else {
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                            )
                                        )
                                    },
                                    RoundedCornerShape(25.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isEditMode) Icons.Filled.Edit else roleIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isSaving -> "保存中..."
                                        isEditMode -> themeText("save_changes")
                                        else -> themeText("create_companion_assistant")
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    themeText("confirm_delete"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    themeText("delete_confirm_msg", name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        existingCompanion?.let {
                            viewModel.deleteCompanion(it)
                        }
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(themeText("companion_delete"), color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(themeText("cancel"), color = colorScheme.onSurfaceVariant)
                }
            },
            containerColor = colorScheme.surfaceVariant
        )
    }

    if (showImportErrorDialog) {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = {
                Text(
                    themeText("import_failed"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    importErrorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(themeText("ok"), color = accentColor)
                }
            },
            containerColor = colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun RoleToggleChip(
    role: CompanionRole,
    icon: ImageVector,
    label: String,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = if (selected) accentColor.copy(alpha = 0.12f) else colorScheme.surface.copy(alpha = 0.5f)
    val borderColor = if (selected) accentColor else colorScheme.outline.copy(alpha = 0.3f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 14.sp
            ),
            color = if (selected) accentColor else colorScheme.onSurface
        )
    }
}

@Composable
fun AnimatedFormField(
    visible: Boolean,
    delayMillis: Int,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    minLines: Int = 1,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val colorScheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, delayMillis = delayMillis)) +
                slideInVertically(tween(400, delayMillis = delayMillis)) { it / 3 }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp
                            ),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor.copy(alpha = 0.4f),
                        unfocusedBorderColor = colorScheme.outline,
                        focusedLabelColor = accentColor.copy(alpha = 0.6f),
                        unfocusedLabelColor = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        focusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    minLines = minLines,
                    maxLines = if (minLines > 1) 5 else 1,
                    singleLine = minLines == 1,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp
                    )
                )
            }
        }
    }
}
