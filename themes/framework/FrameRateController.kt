package com.xingshi.ai.theme.framework

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs

/**
 * 帧率读取/保存/应用：唯一实现在主题框架内，宿主与主题设置页都经此调用。
 */
object FrameRateController {
    private const val PREFS_NAME = "frame_rate_prefs"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_AUTO_MODE = "frame_rate_auto"

    /** 模式刷新率与档位的匹配容差（面板常见 59.94 / 119.88 这类非整数值）。 */
    private const val RATE_TOLERANCE_HZ = 2f

    enum class FrameRate(val value: Int, val label: String) {
        AUTO(-1, ""),
        RATE_60(60, "60Hz"),
        RATE_90(90, "90Hz"),
        RATE_120(120, "120Hz"),
        RATE_144(144, "144Hz"),
        RATE_165(165, "165Hz")
    }

    /**
     * 可选项按设备实际模式产生：只有存在对应刷新率模式的档位才列出。
     * 不能用「最高刷新率 ≥ N」推导，否则 60/120Hz 设备会显示并保存不受支持的 90Hz。
     */
    fun getSupportedFrameRates(context: Context): List<FrameRate> =
        supportedFrameRatesFrom(displayRefreshRates(context))

    /** 纯函数便于 JVM 验证：输入设备真实模式刷新率，输出可选项。 */
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

    /**
     * 所有系统版本都读取设备实际支持的模式（Display.getSupportedModes 自 API 23 可用）。
     * 不能用当前刷新率单项冒充完整能力集合，否则 60Hz 当前模式的旧版本设备会漏掉 120Hz。
     */
    internal fun displayRefreshRatesFrom(modeRefreshRates: List<Float>?, currentRefreshRate: Float): List<Float> =
        modeRefreshRates?.filter { it > 0f }?.distinct().orEmpty()

    /**
     * 应用帧率：始终先清理 preferredDisplayModeId，避免旧的固定 mode 覆盖
     * preferredRefreshRate 请求（平台文档：已设置 modeId 时 refreshRate 被忽略）。
     * 请求值与可选项使用同一份模式列表，选中即请求最近的受支持模式。
     */
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

    /** 读取有效选择：历史上保存但当前设备不支持的档位按既有规则回退到自动。 */
    fun getSavedFrameRate(context: Context): FrameRate {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean(KEY_AUTO_MODE, true)
        if (isAuto) return FrameRate.AUTO

        val value = prefs.getInt(KEY_FRAME_RATE, -1)
        val saved = FrameRate.entries.find { it.value == value } ?: return FrameRate.AUTO
        return if (saved in getSupportedFrameRates(context)) saved else FrameRate.AUTO
    }

    /** 冷启动时应用已保存值；不覆盖用户选择的高刷，设备不支持时回退自动。 */
    fun applySaved(window: Window, context: Context) {
        applyFrameRate(window, getSavedFrameRate(context))
    }
}
