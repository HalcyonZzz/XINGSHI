package com.xingshi.ai.feature.backup

import com.xingshi.ai.themes.official.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xingshi.ai.theme.framework.themeText
import androidx.compose.material3.MaterialTheme
import com.xingshi.ai.theme.framework.ThemeRuntime

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

import kotlinx.coroutines.flow.first

/**
 * 数据备份与恢复界面。
 *
 * 导出流程：点击导出 → 密码弹窗 → export(password) → 收集加密数据 → SAF 保存文件
 * 导入流程：点击导入 → SAF 选择文件 → 密码弹窗 → import(uri, password)
 */
@Composable
fun BackupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: BackupViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPassword by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { dest ->
            pendingExportBytes?.let { bytes ->
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        }
                        viewModel.onExportComplete()
                    }.onFailure { viewModel.onError("保存文件失败: ${it.localizedMessage}") }
                    pendingExportBytes = null
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.import(it, pendingPassword) }
    }

    ThemePageSlot(
        page = ThemePages.DATA_BACKUP,
        host = {
            val status = when (val s = uiState) {
                is BackupViewModel.UiState.Idle -> "idle"
                is BackupViewModel.UiState.Exporting -> "exporting"
                is BackupViewModel.UiState.Importing -> "importing"
                is BackupViewModel.UiState.Success -> "success"
                is BackupViewModel.UiState.Error -> "error"
            }
            val message = when (val s = uiState) {
                is BackupViewModel.UiState.Success -> s.message
                is BackupViewModel.UiState.Error -> s.message
                else -> ""
            }
            ThemePageHost(
                page = ThemePages.DATA_BACKUP,
                items = emptyList(),
                state = mapOf(
                    "status" to status,
                    "message" to message,
                    "busy" to (uiState is BackupViewModel.UiState.Exporting || uiState is BackupViewModel.UiState.Importing).toString()
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "export" -> {
                                // 只有作者页主动导出时才订阅本次结果：官方 fallback 路径不会触发，
                                // 因而同一次导出只有一个 SAF 承接者；不依赖 host() 是否被求值。
                                pendingPassword = action.payload["password"].orEmpty()
                                scope.launch {
                                    val bytes = kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                                        viewModel.exportResult.first()
                                    } ?: return@launch
                                    pendingExportBytes = bytes
                                    saveLauncher.launch("xingshi_backup_${System.currentTimeMillis()}.lybk")
                                }
                                viewModel.export(pendingPassword)
                            }
                            "import" -> {
                                pendingPassword = action.payload["password"].orEmpty()
                                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            }
                            "reset" -> viewModel.resetState()
                            "back" -> onNavigateBack()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            "back" -> onNavigateBack()
                        }
                    }
                }
            )
        },
        fallback = { BackupScreenContent(onNavigateBack = onNavigateBack) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScreenContent(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val viewModel: BackupViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); isVisible = true }

    // 密码弹窗状态
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableStateOf(PasswordMode.EXPORT) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // SAF 导出：收到加密数据后打开保存对话框
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

    val exportSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { dest ->
            pendingExportBytes?.let { bytes ->
                scope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        }
                        viewModel.onExportComplete()
                    } catch (e: Exception) {
                        viewModel.onError("保存文件失败: ${e.localizedMessage}")
                    }
                    pendingExportBytes = null
                }
            }
        }
    }

    // 收集导出结果 → 打开 SAF
    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { encryptedBytes ->
            pendingExportBytes = encryptedBytes
            exportSaveLauncher.launch("xingshi_backup_${dateString()}.lybk")
        }
    }

    // SAF 导入：选择文件
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingImportUri = it
            passwordMode = PasswordMode.IMPORT
            showPasswordDialog = true
        }
    }

    // 收集导入请求 → 打开 SAF
    LaunchedEffect(Unit) {
        viewModel.importRequest.collect {
            importFileLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }

    // 密码弹窗
    if (showPasswordDialog) {
        PasswordDialog(
            mode = passwordMode,
            isLoading = uiState is BackupViewModel.UiState.Exporting || uiState is BackupViewModel.UiState.Importing,
            onConfirm = { password ->
                when (passwordMode) {
                    PasswordMode.EXPORT -> viewModel.export(password)
                    PasswordMode.IMPORT -> {
                        pendingImportUri?.let { viewModel.import(it, password) }
                        pendingImportUri = null
                    }
                }
                showPasswordDialog = false
            },
            onDismiss = {
                showPasswordDialog = false
                pendingImportUri = null
                if (uiState !is BackupViewModel.UiState.Exporting &&
                    uiState !is BackupViewModel.UiState.Importing) {
                    viewModel.resetState()
                }
            }
        )
    }

    // 结果 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is BackupViewModel.UiState.Success -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            is BackupViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(ThemeRuntime.resolveText("backup_title"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, ThemeRuntime.resolveText("backup_back"), tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 导出卡片
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }
            ) {
                BackupCard(
                    icon = Icons.Filled.SaveAlt,
                    title = ThemeRuntime.resolveText("backup_export_title"),
                    description = ThemeRuntime.resolveText("backup_export_desc"),
                    buttonText = ThemeRuntime.resolveText("backup_export_btn"),
                    buttonColor = Color(0xFF07C160),
                    isLoading = uiState is BackupViewModel.UiState.Exporting,
                    onClick = {
                        passwordMode = PasswordMode.EXPORT
                        showPasswordDialog = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 导入卡片
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(tween(400, delayMillis = 80)) { it / 4 }
            ) {
                BackupCard(
                    icon = Icons.Filled.FileOpen,
                    title = ThemeRuntime.resolveText("backup_import_title"),
                    description = ThemeRuntime.resolveText("backup_import_desc"),
                    buttonText = ThemeRuntime.resolveText("backup_import_btn"),
                    buttonColor = Color(0xFFFA5151),
                    isLoading = uiState is BackupViewModel.UiState.Importing,
                    onClick = { viewModel.requestImport() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 说明文字
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 160))
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            ThemeRuntime.resolveText("backup_notice"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ============================================================================
// 密码弹窗
// ============================================================================

enum class PasswordMode { EXPORT, IMPORT }

@Composable
private fun PasswordDialog(
    mode: PasswordMode,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                if (mode == PasswordMode.EXPORT) ThemeRuntime.resolveText("ui2_backup_screen_01") else ThemeRuntime.resolveText("ui2_backup_screen_02"),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (mode == PasswordMode.EXPORT) {
                    Text(ThemeRuntime.resolveText("ui_backup_screen_01"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(ThemeRuntime.resolveText("ui_backup_screen_02"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(ThemeRuntime.resolveText("ui_backup_screen_03")) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) ThemeRuntime.resolveText("ui_backup_screen_04") else ThemeRuntime.resolveText("ui_backup_screen_05")
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                if (mode == PasswordMode.EXPORT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = { Text(ThemeRuntime.resolveText("ui_backup_screen_06")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        password.length < 6 -> error = ThemeRuntime.resolveText("ui2_backup_screen_03")
                        mode == PasswordMode.EXPORT && password != confirmPassword -> error = ThemeRuntime.resolveText("ui2_backup_screen_04")
                        else -> onConfirm(password)
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) ThemeRuntime.resolveText("ui_backup_screen_07") else ThemeRuntime.resolveText("ui_backup_screen_08"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(ThemeRuntime.resolveText("ui_backup_screen_09"))
            }
        }
    )
}

// ============================================================================
// 功能卡片
// ============================================================================

@Composable
private fun BackupCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    buttonColor: Color,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, title, Modifier.size(28.dp), tint = buttonColor)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp), color = colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(description, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isLoading) ThemeRuntime.resolveText("ui_backup_screen_10") else buttonText, color = Color.White)
            }
        }
    }
}

// ============================================================================
// 工具函数
// ============================================================================

private fun dateString(): String {
    val cal = java.util.Calendar.getInstance()
    return "${cal.get(java.util.Calendar.YEAR)}-${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}-${cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
}
