package com.xingshi.ai.plugins.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xingshi.ai.plugins.PluginFramework

 
class PluginChannelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        PluginFramework.init(context.applicationContext as android.app.Application)
        PluginChannelService.start(context)
    }
}
