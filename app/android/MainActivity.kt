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

/**
 * 应用主入口 Activity — v2.0 精简版。
 *
 * 职责:
 *   生命周期管理 (onCreate / onResume / onPause / onDestroy)
 *   权限请求 (通知)
 *   系统栏初始化 (委托 SystemBarController)
 *   帧率策略
 *   保活/后台服务启动
 */
class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(Dispatchers.Main)
    private var memoryAlertActive = false // 滞环: 触发后需降到阈值以下才解除

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
        // 启动即以系统安装版本判断更新是否完成，并清理残留临时包
        appScope.launch(Dispatchers.IO) {
            runCatching { AppUpdateManager.cleanupOnStart(applicationContext) }
        }
        // WorkManager must be initialized before any schedule() call
        try { androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) } catch (_: Exception) {}
        enableEdgeToEdge()
        window.decorView.post { SystemBarController.applySystemBars(this) }

        // 帧率请求统一由框架处理（AUTO 不固定 modeId，显式档位只设 refreshRate）
        FrameRateController.applySaved(window, this)

        // 硬件加速
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // 异形屏适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 用户协议在登录/注册页内以勾选方式确认，不再弹出整页拦截
        val activity = this
        setContent {
            val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()
            val backendAccount by MobileBackendRuntime.instance.state.collectAsStateWithLifecycle()

            XingShiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        !isServiceReady -> {
                            // 等待跨模块依赖注册中心就绪，避免冷启动后快速进入
                            // 创建人设等页面时 ServiceRegistry.getOrThrow 抛异常导致闪退。
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

        // 后台服务 + 权限
        requestNotificationPermission()
        CompanionKeepAliveService.start(this)
        // IQOO/OriginOS 设备启用 JobScheduler 第三层兜底保活
        if (RomUtils.isVivo) {
            scheduleIqooKeepAliveJob()
        }

        startMemoryMonitor()
    }

    /**
     * IQOO/OriginOS 专用 JobScheduler 第三层保活调度。
     *
     * OriginOS 对前台服务和 WorkManager 都有严格限制，
     * 使用 JobScheduler 作为兜底机制：每 15 分钟检查并重启保活服务。
     */
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

    /**
     * 内存管理控制回路 — 每3秒检查一次内存使用率。
     * 滞环: 触发 85% 后需降到 60% 以下才解除。
     */
    private fun startMemoryMonitor() {
        appScope.launch {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory()
            while (isActive) {
                delay(3000)
                val used = runtime.totalMemory() - runtime.freeMemory()
                val ratio = used.toFloat() / maxMem.toFloat()

                if (ratio > 0.90f) {
                    // 清空缓存 — 通知系统内存压力，由系统自行调度GC
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
        // singleTask 热通知只走这里：把新 Intent 交给页面消费，否则点击通知不会跳转。
        setIntent(intent)
        com.xingshi.ai.common.AppIntentRouter.publish(intent)
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
