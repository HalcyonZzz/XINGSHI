package com.xingshi.ai.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.feature.settings.ui.screen.AppUpdateManager
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.themeText
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onAgreementClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by AppUpdateManager.state.collectAsState()
    LaunchedEffect(Unit) { AppUpdateManager.refreshInstalled(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AppUpdateManager.refreshInstalled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun dispatch(id: String) {
        when (id) {
            "check_update" -> scope.launch { AppUpdateManager.check(context) }
            "download_update" -> scope.launch { AppUpdateManager.download(context) }
            "install_update" -> AppUpdateManager.install(context, update.readyApkPath)
            "agreement", "open_agreement" -> onAgreementClick()
            "back" -> onNavigateBack()
        }
    }

    ThemePageSlot(
        page = ThemePages.ABOUT,
        host = {
            val items = buildList {
                add(ThemePageItem(id = "check_update", kind = "row", title = themeText("app_update_check")))
                if (update.status == AppUpdateManager.Status.AVAILABLE ||
                    update.status == AppUpdateManager.Status.DOWNLOADING ||
                    update.status == AppUpdateManager.Status.DOWNLOAD_FAILED ||
                    update.status == AppUpdateManager.Status.READY_TO_INSTALL
                ) {
                    add(
                        ThemePageItem(
                            id = "download_update",
                            kind = "row",
                            title = themeText("app_update_download")
                        )
                    )
                }
                if (update.status == AppUpdateManager.Status.READY_TO_INSTALL) {
                    add(
                        ThemePageItem(
                            id = "install_update",
                            kind = "row",
                            title = themeText("app_update_install")
                        )
                    )
                }
                add(ThemePageItem(id = "agreement", kind = "row", title = themeText("view_agreement")))
                add(ThemePageItem(id = "back", kind = "row", title = themeText("profile_back")))
            }
            ThemePageHost(
                page = ThemePages.ABOUT,
                items = items,
                state = mapOf(
                    "version" to update.currentVersionName,
                    "version_code" to update.currentVersionCode.toString(),
                    "update_status" to update.status.name,
                    "update_remote_version" to update.remoteVersionName,
                    "update_remote_code" to update.remoteVersionCode.toString(),
                    "update_notes" to update.releaseNotes,
                    "update_size" to update.sizeBytes.toString(),
                    "update_progress" to (update.progress?.toString() ?: ""),
                    "update_detail" to update.detail
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> dispatch(action.id)
                        is ThemePageAction.Open -> dispatch(action.route)
                    }
                }
            )
        },
        fallback = {
            AboutScreenContent(
                onNavigateBack = onNavigateBack,
                onAgreementClick = onAgreementClick,
                onUpdateAction = ::dispatch
            )
        }
    )
}

@Composable
private fun updateDetailText(detail: String): String = when {
    detail.isBlank() -> ""
    detail.startsWith("http_") -> themeText("app_update_err_http", detail.removePrefix("http_"))
    else -> themeText("app_update_err_$detail")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreenContent(
    onNavigateBack: () -> Unit,
    onAgreementClick: () -> Unit = {},
    onUpdateAction: (String) -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val update by AppUpdateManager.state.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = themeText("profile_back"),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = themeText("about_title"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.width(32.dp))
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = themeText("app_brand"),
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = themeText("app_brand"),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 2.sp
                ),
                color = colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = themeText("app_slogan"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            
            Text(
                text = themeText(
                    "app_update_version_fmt",
                    update.currentVersionName,
                    update.currentVersionCode
                ),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            AboutCard {
                Text(
                    text = themeText("app_update_title"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(10.dp))

                when (update.status) {
                    AppUpdateManager.Status.AVAILABLE -> {
                        Text(
                            text = themeText(
                                "app_update_available_fmt",
                                update.remoteVersionName,
                                update.remoteVersionCode
                            ),
                            color = colorScheme.primary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        if (update.sizeBytes > 0L) {
                            Text(
                                text = themeText("app_update_size_fmt", update.sizeBytes / 1024L / 1024L),
                                color = colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        if (update.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = update.releaseNotes,
                                color = colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = { onUpdateAction("download_update") }) {
                            Text(themeText("app_update_download"))
                        }
                    }
                    AppUpdateManager.Status.DOWNLOADING -> {
                        val progress = update.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = themeText("app_update_downloading"),
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    AppUpdateManager.Status.DOWNLOAD_FAILED -> {
                        Text(
                            text = themeText("app_update_download_failed"),
                            color = colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        if (update.detail.isNotBlank()) {
                            Text(updateDetailText(update.detail), color = colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onUpdateAction("check_update") }) {
                            Text(themeText("app_update_retry"))
                        }
                    }
                    AppUpdateManager.Status.READY_TO_INSTALL -> {
                        if (update.detail == "needs_permission") {
                            Text(
                                text = themeText("app_update_needs_permission"),
                                color = colorScheme.error,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = themeText("app_update_ready"),
                            color = colorScheme.primary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onUpdateAction("install_update") }) {
                            Text(themeText("app_update_install"))
                        }
                    }
                    AppUpdateManager.Status.CHECK_FAILED -> {
                        Text(
                            text = themeText("app_update_check_failed"),
                            color = colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        if (update.detail.isNotBlank()) {
                            Text(updateDetailText(update.detail), color = colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onUpdateAction("check_update") }) {
                            Text(themeText("app_update_retry"))
                        }
                    }
                    AppUpdateManager.Status.UP_TO_DATE -> {
                        Text(
                            text = themeText("app_update_up_to_date"),
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { onUpdateAction("check_update") }) {
                            Text(themeText("app_update_retry"))
                        }
                    }
                    AppUpdateManager.Status.INSTALL_FAILED -> {
                        Text(
                            text = themeText("app_update_install_failed"),
                            color = colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        if (update.detail.isNotBlank()) {
                            Text(updateDetailText(update.detail), color = colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onUpdateAction("check_update") }) {
                            Text(themeText("app_update_retry"))
                        }
                    }
                    else -> {
                        Button(
                            enabled = update.status != AppUpdateManager.Status.CHECKING,
                            onClick = { onUpdateAction("check_update") }
                        ) {
                            Text(
                                if (update.status == AppUpdateManager.Status.CHECKING) {
                                    themeText("app_update_checking")
                                } else {
                                    themeText("app_update_check")
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard() {
                Text(
                    text = themeText("about_desc_title"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = themeText("about_desc"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard() {
                Text(
                    text = themeText("developer"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = themeText("developer_douyin"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFF07C160)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = themeText("developer_hint"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = themeText("view_agreement"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                    color = Color(0xFF07C160)
                ),
                modifier = Modifier
                    .clickable { onAgreementClick() }
                    .padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun AboutCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}
