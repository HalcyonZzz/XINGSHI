package com.xingshi.ai.plugins.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xingshi.ai.plugins.PluginFramework

/** 冷启动恢复由宿主通用承载完成，不按平台类名硬编码。 */
class PluginChannelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        PluginFramework.init(context.applicationContext as android.app.Application)
        PluginChannelService.start(context)
    }
}
