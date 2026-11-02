package com.xingshi.ai.feature.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xingshi.ai.common.SecureLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                CompanionKeepAliveService.start(context)
            } catch (e: Exception) {
                SecureLog.w("BootReceiver", "Cannot start keep-alive service: ${e.message}")
            }
        }
    }
}