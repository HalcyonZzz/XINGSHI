package com.xingshi.ai.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.xingshi.ai.feature.notification.R
import com.xingshi.ai.theme.framework.ThemeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

open class CompanionKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var timedOut = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var languageJob: Job? = null
    // 用户主动 stop 时不自重启：原 onDestroy 在非超时路径无条件 start()，
    // 导致用户无法停止服务。增加 stopRequested 标记，stop(context) 时置 true，
    // onDestroy 检查此标记决定是否自重启。
    @Volatile private var stopRequested = false
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLockRunnable = object : Runnable {
        override fun run() {
            acquireWakeLock()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        handler.postDelayed(wakeLockRunnable, 5 * 60 * 1000L)
        languageJob = serviceScope.launch {
            ThemeRuntime.state.map { it.language }.distinctUntilChanged().drop(1).collect {
                if (!stopRequested) {
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, createNotification())
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先满足前台服务合同，再处理 stop 标记；顺序反过来会触发 FGS 启动超时。
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 接收 stop 标记：stop(context) 用 startService 把标记送到 onStartCommand，
        // 服务在 onDestroy 时据此不自重启（stopService 不会投递 extra）。
        if (intent?.getBooleanExtra(EXTRA_STOP_REQUESTED, false) == true) {
            stopRequested = true
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
                // ignore cleanup errors
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout()
    }

    /** Android 15 前台服务类型超时回调（部分 ROM 走这个重载）。 */
    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout()
    }

    private fun handleTimeout() {
        timedOut = true
        // Android 15 前台服务 6 小时超时：必须先 stopForeground 再 stopSelf，
        // 否则 ColorOS / OriginOS 等激进 ROM 可能抛出异常或直接拒绝重启。
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore cleanup errors
        }
        stopSelf()
    }

    override fun onDestroy() {
        languageJob?.cancel()
        languageJob = null
        serviceScope.cancel()
        handler.removeCallbacks(wakeLockRunnable)
        releaseWakeLock()
        super.onDestroy()

        // 仅在非用户主动停止、非超时的情况下自重启。
        // 用户主动 stop（stopRequested=true）则不自重启，尊重用户意图。
        if (!timedOut && !stopRequested) {
            start(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 任务移除时若用户已主动停止，也不自重启
        if (!stopRequested) {
            start(applicationContext)
        }
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "XINGSHI::CompanionKeepAlive"
        ).apply {
            setReferenceCounted(false)
            // wakeLock 超时 10 分钟，续期间隔 5 分钟。若 Doze 延迟 postDelayed，
            // 续期可能晚于超时。改超时为 15 分钟，留足余量（续期 5 分钟 < 超时 15 分钟 / 2）。
            acquire(15 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        val channelId = "keep_alive_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "后台运行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持智能助理消息推送服务运行"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(Intent.ACTION_MAIN).apply {
            `package` = packageName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent().setClassName(packageName, SHELL_SERVICE_CLASS)
            .putExtra(EXTRA_STOP_REQUESTED, true)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("星识")
            .setContentText("智能助理正在守护你~")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, ThemeRuntime.resolveText("keep_alive_stop_action"), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 安全启动前台保活服务：捕获启动失败（如后台限制），不抛异常。
         * 供 JobScheduler 兜底保活调用。
         */
        fun safeStart(context: Context) {
            try {
                start(context)
            } catch (e: Exception) {
                // OriginOS/IQOO 等可能限制后台启动服务，忽略错误
            }
        }

        fun stop(context: Context) {
            // stopService 不会把 extra 传给 onStartCommand，必须用 startService 投递停止标记，
            // 否则服务不知道是用户主动停止，onDestroy 还会自重启。
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
                .putExtra(EXTRA_STOP_REQUESTED, true)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 后台启动受限时退回 stopService，至少先停掉当前实例。
                runCatching { context.stopService(Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)) }
            }
        }

        private const val EXTRA_STOP_REQUESTED = "stop_requested"
        private const val SHELL_SERVICE_CLASS = "com.xingshi.ai.feature.notification.CompanionKeepAliveService"
    }
}
