package com.xingshi.ai.uicommon.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.theme.framework.themeText
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.theme.framework.ThemeState
import com.xingshi.ai.theme.framework.toCompose

 
@Composable
fun XingShiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state by ThemeRuntime.state.collectAsState()
    val recovery by ThemeRuntime.recoveryMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val appearance = remember(state.revision, systemDark) { ThemeRuntime.appearance(context) }
    val colors = appearance.palette.toCompose()
    val effectiveDarkTheme = appearance.isDark

    val colorScheme = if (effectiveDarkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primary.copy(alpha = 0.25f),
            onPrimaryContainer = colors.onSurface,
            secondary = colors.secondary,
            onSecondary = Color.White,
            secondaryContainer = colors.secondary.copy(alpha = 0.12f),
            onSecondaryContainer = colors.secondary,
            tertiary = colors.tertiary,
            onTertiary = Color.White,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = ErrorRed,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primary.copy(alpha = 0.18f),
            onPrimaryContainer = colors.onSurface,
            secondary = colors.secondary,
            onSecondary = Color.White,
            secondaryContainer = colors.secondary.copy(alpha = 0.1f),
            onSecondaryContainer = colors.secondary,
            tertiary = colors.tertiary,
            onTertiary = Color.White,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = ErrorRed,
            onError = Color.White
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(effectiveDarkTheme, state.revision) {
            val window = (view.context as Activity).window
            val navScrim = if (effectiveDarkTheme) {
                Color(0xFF000000).copy(alpha = 0.25f)
            } else {
                Color(0xFFFFFFFF).copy(alpha = 0.55f)
            }.toArgb()

            window.statusBarColor = if (effectiveDarkTheme) {
                colorScheme.background.toArgb()
            } else {
                Color(0xFFFFFFFF).toArgb()
            }
            window.navigationBarColor = navScrim
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDarkTheme
            onDispose { }
        }
    }

    val codeUi = remember(state.revision) { ThemeRuntime.codeUi() }
    val currentContent by rememberUpdatedState(content)
    val hostContent = remember { movableContentOf { currentContent() } }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            if (codeUi != null && !recovery) {
                codeUi.Wrap { hostContent() }
            } else {
                hostContent()
            }
            
            if (codeUi != null && !recovery) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 12.dp)
                        .clickable { ThemeRuntime.requestRecovery() },
                    shape = RoundedCornerShape(50),
                    color = colorScheme.primary.copy(alpha = 0.92f),
                    contentColor = colorScheme.onPrimary,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = themeText("host_theme_settings"),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
