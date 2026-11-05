package com.xingshi.ai.theme.framework

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs

object FrameRateController {
    private const val PREFS_NAME = "frame_rate_prefs"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_AUTO_MODE = "frame_rate_auto"

    private const val RATE_TOLERANCE_HZ = 2f

    enum class FrameRate(val value: Int, val label: String) {
        AUTO(-1, ""),
        RATE_60(60, "60Hz"),
        RATE_90(90, "90Hz"),
        RATE_120(120, "120Hz"),
        RATE_144(144, "144Hz"),
        RATE_165(165, "165Hz")
    }

    fun getSupportedFrameRates(context: Context): List<FrameRate> =
        supportedFrameRatesFrom(displayRefreshRates(context))

    internal fun supportedFrameRatesFrom(refreshRates: List<Float>): List<FrameRate> {
        val rates = refreshRates.filter { it > 0f }
        val supported = mutableListOf(FrameRate.AUTO)
        FrameRate.entries.forEach { candidate ->
            if (candidate == FrameRate.AUTO) return@forEach
            if (rates.any { abs(it - candidate.value) <= RATE_TOLERANCE_HZ }) {
                supported.add(candidate)
            }
        }
        return supported
    }

    private fun displayRefreshRates(context: Context): List<Float> {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        } ?: return emptyList()

        @Suppress("DEPRECATION")
        val modes = display.supportedModes?.map { it.refreshRate }
        @Suppress("DEPRECATION")
        val current = display.refreshRate
        return displayRefreshRatesFrom(modes, current)
    }

    internal fun displayRefreshRatesFrom(modeRefreshRates: List<Float>?, currentRefreshRate: Float): List<Float> =
        modeRefreshRates?.filter { it > 0f }?.distinct().orEmpty()

    fun applyFrameRate(window: Window, frameRate: FrameRate) {
        val attributes = window.attributes
        attributes.preferredDisplayModeId = 0
        attributes.preferredRefreshRate = when (frameRate) {
            FrameRate.AUTO -> 0f
            else -> {
                @Suppress("DEPRECATION")
                val modes = window.windowManager.defaultDisplay.supportedModes
                modes.minByOrNull { abs(it.refreshRate - frameRate.value) }?.refreshRate ?: 0f
            }
        }
        window.attributes = attributes
    }

    fun saveFrameRate(context: Context, frameRate: FrameRate) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FRAME_RATE, frameRate.value)
            .putBoolean(KEY_AUTO_MODE, frameRate == FrameRate.AUTO)
            .apply()
    }

    fun getSavedFrameRate(context: Context): FrameRate {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean(KEY_AUTO_MODE, true)
        if (isAuto) return FrameRate.AUTO

        val value = prefs.getInt(KEY_FRAME_RATE, -1)
        val saved = FrameRate.entries.find { it.value == value } ?: return FrameRate.AUTO
        return if (saved in getSupportedFrameRates(context)) saved else FrameRate.AUTO
    }

    fun applySaved(window: Window, context: Context) {
        applyFrameRate(window, getSavedFrameRate(context))
    }
}
