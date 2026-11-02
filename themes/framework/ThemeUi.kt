package com.xingshi.ai.theme.framework

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 官方页面的文案取值入口：主题可覆盖任意资源键，缺项按
 * 「主题选定语言 → 官方同语言 → 官方默认语言」回退。
 */
@Composable
fun themeText(key: String, vararg args: Any): String {
    val state by ThemeRuntime.state.collectAsState()
    val argList = args.toList()
    return remember(state.revision, state.language, state.themeId, key, argList) {
        ThemeRuntime.resolveText(key, argList)
    }
}

/** 主题设置/市场页面需要重新渲染时使用。 */
@Composable
fun rememberThemeState(): ThemeState {
    val state by ThemeRuntime.state.collectAsState()
    return state
}

/**
 * 页面宿主入口：作者代码主题覆盖该页面时由作者绘制，未覆盖/无代码主题时回退官方页面。
 * host 只在需要覆盖时求值，官方回退路径不额外创建状态。
 */
@Composable
fun ThemePageSlot(
    page: String,
    host: @Composable () -> ThemePageHost,
    fallback: @Composable () -> Unit
) {
    val state by ThemeRuntime.state.collectAsState()
    val codeUi = remember(state.revision) { ThemeRuntime.codeUi() }
    if (codeUi == null) {
        fallback()
    } else {
        codeUi.Page(host(), fallback)
    }
}
