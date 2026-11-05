package com.xingshi.ai.theme.framework

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
fun themeText(key: String, vararg args: Any): String {
    val state by ThemeRuntime.state.collectAsState()
    val argList = args.toList()
    return remember(state.revision, state.language, state.themeId, key, argList) {
        ThemeRuntime.resolveText(key, argList)
    }
}

@Composable
fun rememberThemeState(): ThemeState {
    val state by ThemeRuntime.state.collectAsState()
    return state
}

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
