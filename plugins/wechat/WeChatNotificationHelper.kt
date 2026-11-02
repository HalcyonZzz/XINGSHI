package com.xingshi.ai.feature.wechat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xingshi.ai.feature.wechat.data.model.M0
import com.xingshi.ai.theme.framework.ThemeRuntime

object WeChatNotificationHelper {

    private const val CHANNEL_ID = "wechat_incoming"
    private const val CHANNEL_NAME = "微信消息"
    private const val NOTIFICATION_ID_BASE = 30000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到微信个人号的新消息时推送通知"
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun showIncomingMessageNotification(context: Context, message: M0) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val sender = message.fromUserId ?: "微信消息"
        val text = message.itemList?.firstNotNullOfOrNull { it.textItem?.text } ?: "[新消息]"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NOTIFICATION_ID_BASE + (message.seq?.toInt() ?: 0)
        manager.notify(id, notification)
    }

    enum class ImageFailureReason(val textKey: String) {
        DOWNLOAD("ui_wechat_image_failed_reason_download"),
        SAVE("ui_wechat_image_failed_reason_save"),
        ACCOUNT("ui_wechat_image_failed_reason_account"),
        REJECTED("ui_wechat_image_failed_reason_rejected")
    }

    fun showImageDownloadFailureNotification(
        context: Context,
        sender: String,
        reason: ImageFailureReason = ImageFailureReason.DOWNLOAD,
        reasonDetail: String? = null
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val args = if (reason == ImageFailureReason.REJECTED) {
            listOf(reasonDetail?.takeIf { it.isNotBlank() }
                ?: ThemeRuntime.resolveText("ui_wechat_image_failed_reason_unknown"))
        } else {
            emptyList()
        }
        val reasonText = ThemeRuntime.resolveText(reason.textKey, args)
        val senderText = sender.ifBlank { ThemeRuntime.resolveText("ui_wechat_image_failed_sender_unknown") }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(ThemeRuntime.resolveText("ui_wechat_image_failed_title"))
            .setContentText(
                ThemeRuntime.resolveText(
                    "ui_wechat_image_failed_content",
                    listOf(senderText, reasonText)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_BASE + 1, notification)
    }
}
