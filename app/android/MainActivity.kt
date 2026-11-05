package com.xingshi.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.edit
import com.xingshi.ai.common.AppForegroundTracker
import com.xingshi.ai.common.BatteryOptimizationHelper
import com.xingshi.ai.theme.framework.FrameRateController
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.common.RomUtils
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.feature.notification.CompanionKeepAliveService
import com.xingshi.ai.feature.settings.ui.screen.AppUpdateManager
import com.xingshi.ai.uicommon.theme.XingShiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(Dispatchers.Main)
    private var memoryAlertActive = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit { putBoolean("notification_permission_denied", true) }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeRuntime.applyBaseContextLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeSharedText(intent)
        appScope.launch(Dispatchers.IO) {
            runCatching { AppUpdateManager.cleanupOnStart(applicationContext) }
        }
        try { androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) } catch (_: Exception) {}
        enableEdgeToEdge()
        window.decorView.post { SystemBarController.applySystemBars(this) }

        FrameRateController.applySaved(window, this)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val activity = this
        setContent {
            val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()
            val backendAccount by MobileBackendRuntime.instance.state.collectAsStateWithLifecycle()

            XingShiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        !isServiceReady -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        backendAccount.userId == null -> {
                            com.xingshi.ai.feature.profile.AuthScreen(onLoginSuccess = {})
                        }
                        else -> {
                            androidx.compose.runtime.key(backendAccount.generation) {
                                val accountStore = androidx.compose.runtime.remember { androidx.lifecycle.ViewModelStore() }
                                val owner = androidx.compose.runtime.remember { object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = accountStore } }
                                androidx.compose.runtime.DisposableEffect(accountStore) { onDispose { accountStore.clear() } }
                                androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) { MainScreen(activity) }
                            }
                        }
                    }
                }
            }
        }

        requestNotificationPermission()
        CompanionKeepAliveService.start(this)
        if (RomUtils.isVivo) {
            scheduleIqooKeepAliveJob()
        }

        startMemoryMonitor()
    }

    private fun scheduleIqooKeepAliveJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val jobScheduler = getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)
                        as android.app.job.JobScheduler
                jobScheduler.cancel(IQOO_KEEP_ALIVE_JOB_ID)
                val componentName = android.content.ComponentName(this, IqooKeepAliveJobService::class.java)
                val builder = android.app.job.JobInfo.Builder(IQOO_KEEP_ALIVE_JOB_ID, componentName)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setPriority(android.app.job.JobInfo.PRIORITY_HIGH)
                }
                jobScheduler.schedule(builder.build())
                android.util.Log.i("MainActivity", "IQOO keep-alive JobScheduler scheduled")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to schedule IQOO keep-alive job: ${e.message}")
            }
        }
    }

    companion object {
        private const val IQOO_KEEP_ALIVE_JOB_ID = 10001
    }

    private fun startMemoryMonitor() {
        appScope.launch {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory()
            while (isActive) {
                delay(3000)
                val used = runtime.totalMemory() - runtime.freeMemory()
                val ratio = used.toFloat() / maxMem.toFloat()

                if (ratio > 0.90f) {
                    android.util.Log.w("MemoryMonitor", "CRITICAL: ${(ratio * 100).toInt()}% — clearing caches, notifying memory pressure")
                    memoryAlertActive = true
                } else if (ratio > 0.85f && !memoryAlertActive) {
                    android.util.Log.w("MemoryMonitor", "HIGH: ${(ratio * 100).toInt()}% — clearing caches")
                    memoryAlertActive = true
                } else if (ratio < 0.60f && memoryAlertActive) {
                    android.util.Log.i("MemoryMonitor", "RECOVERED: ${(ratio * 100).toInt()}%")
                    memoryAlertActive = false
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.xingshi.ai.common.AppIntentRouter.publish(intent)
        consumeSharedText(intent)
    }

    private fun consumeSharedText(intent: android.content.Intent?) {
        if (intent == null || intent.action != android.content.Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        val title = intent.getStringExtra(android.content.Intent.EXTRA_TITLE).orEmpty()
        intent.removeExtra(android.content.Intent.EXTRA_TEXT)
        intent.removeExtra(android.content.Intent.EXTRA_TITLE)
        if (!text.isNullOrBlank()) {
            com.xingshi.ai.feature.chat.BilibiliShareIntake.offer(text, title)
        }
    }

    override fun onResume() {
        super.onResume()
        MobileBackendRuntime.instance.wake()
        AppForegroundTracker.isInForeground = true
        window.decorView.post {
            SystemBarController.applySystemBars(this)
            FrameRateController.applySaved(window, this)
        }
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.isInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    val deniedBefore = prefs.getBoolean("notification_permission_denied", false)
                    if (!deniedBefore) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

}
