package com.xingshi.ai

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.xingshi.ai.common.RomUtils
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.feature.notification.CompanionKeepAliveService

 
class IqooKeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        SecureLog.i("IqooKeepAliveJobService", "Job started on ${RomUtils.getRomDisplayName()}")

        
        
        Thread({
            try {
                performKeepAliveCheck()
            } catch (e: Exception) {
                SecureLog.e("IqooKeepAliveJobService", "Keep-alive check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }, "iqoo-keepalive").start()

        return true 
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        SecureLog.w("IqooKeepAliveJobService", "Job stopped prematurely by system")
        
        return true
    }

    private fun performKeepAliveCheck() {
        val context = applicationContext

        
        try {
            CompanionKeepAliveService.safeStart(context)
            SecureLog.d("IqooKeepAliveJobService", "Keep-alive service check passed")
        } catch (e: Exception) {
            SecureLog.w("IqooKeepAliveJobService", "Failed to restart keep-alive service: ${e.message}")
        }

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            SecureLog.i("IqooKeepAliveJobService", "Battery optimization ignored: $isIgnoring")
        }
    }
}
