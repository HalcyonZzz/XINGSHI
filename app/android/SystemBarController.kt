package com.xingshi.ai

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.xingshi.ai.theme.framework.ThemeRuntime

object SystemBarController {

    fun applySystemBars(activity: Activity) {
        val appearance = ThemeRuntime.appearance(activity)
        val background = Color(appearance.palette.background)
        val isDark = appearance.isDark

        val navScrim = if (isDark) {
            Color(0xFF000000).copy(alpha = 0.25f)
        } else {
            Color(0xFFFFFFFF).copy(alpha = 0.55f)
        }

        activity.window.statusBarColor = if (isDark) background.toArgb() else Color(0xFFFFFFFF).toArgb()
        activity.window.navigationBarColor = navScrim.toArgb()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    fun applyBaseContextLocale(base: android.content.Context): android.content.Context =
        ThemeRuntime.applyBaseContextLocale(base)

    @Suppress("unused")
    private fun isNightMode(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
