package com.xingshi.ai.plugins.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xingshi.ai.plugins.PluginChannels
import com.xingshi.ai.plugins.PluginFramework
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 通用通道承载：不为任何具体平台添加 Service、类名或轮询实现。 */
class PluginChannelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        PluginFramework.init(application)
        // 通道状态驱动前台承载：无连接尝试时立即退出，避免常驻空白通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        runCatching { runBlocking { PluginChannels.restore() } }
        if (!PluginChannels.hasActiveConnection()) stopSelf()
        scope.launch {
            PluginChannels.statuses.collect {
                if (!PluginChannels.hasActiveConnection()) stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "聊天通道", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("聊天通道运行中")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "plugin_channels"
        private const val NOTIFICATION_ID = 2101

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PluginChannelService::class.java))
        }
    }
}
