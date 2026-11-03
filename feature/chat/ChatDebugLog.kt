package com.xingshi.ai.feature.chat.ui.viewmodel

import com.xingshi.ai.feature.chat.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

 
object ChatDebugLog {
    private val file get() = if (!BuildConfig.DEBUG) null else File("/data/data/com.xingshi.ai/files/accounts/${com.xingshi.ai.common.AccountStorage.partition}/chatvm_debug.log")

    fun log(msg: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val currentFile = file ?: return
            currentFile.parentFile?.mkdirs()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            currentFile.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}
