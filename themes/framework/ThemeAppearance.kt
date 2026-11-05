package com.xingshi.ai.theme.framework

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class ThemePalette(
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val tertiary: Int
) {
    companion object {
        fun fromArgb(
            primary: Int, onPrimary: Int, secondary: Int, background: Int, surface: Int,
            surfaceVariant: Int, onSurface: Int, onSurfaceVariant: Int, outline: Int, tertiary: Int
        ) = ThemePalette(primary, onPrimary, secondary, background, surface, surfaceVariant, onSurface, onSurfaceVariant, outline, tertiary)
    }
}

data class ResolvedAppearance(
    val themeId: String,
    val themeName: String,
    val isDark: Boolean,
    val palette: ThemePalette,
    val navigation: ThemeNavigation,
    val inputBar: ThemeInputBar
)

interface OfficialThemeProvider {
    fun manifest(): ThemeManifest
    fun lightPalette(): ThemePalette
    fun darkPalette(): ThemePalette

    fun text(context: Context, language: String, key: String, args: List<Any>): String?

    fun catalog(context: Context): ThemeCatalog?

    val marketSourceLabel: String
        get() = ""
}

object ThemePages {
    const val HOME = "home"
    const val CONTACTS = "contacts"
    const val STAR_CIRCLE = "star_circle"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val GENERAL_SETTINGS = "general_settings"
    const val CHAT = "chat"
    const val GROUP_CHAT = "group_chat"
    const val VIDEO_CALL = "video_call"
    const val VOICE_CALL = "voice_call"
    const val THEME_MARKET = "theme_market"
    const val THEME_MANAGE = "theme_manage"
    const val CREATE_COMPANION = "create_companion"
    const val EDIT_COMPANION = "edit_companion"
    const val COMPANION_DETAIL = "companion_detail"
    const val CREATE_GROUP = "create_group"
    const val GROUP_DETAIL = "group_detail"
    const val WECHAT_SETTINGS = "wechat_settings"
    const val WECHAT_BIND = "wechat_bind"
    const val QQBOT_SETTINGS = "qqbot_settings"
    const val DATA_BACKUP = "data_backup"
    const val ABOUT = "about"
    const val AGREEMENT = "agreement"
    const val ORIGINOS_ADAPTION = "originos_adaption"
    const val PLUGIN_MARKET = "plugin_market"
    const val PLUGIN_MANAGE = "plugin_manage"
    const val PLUGIN_DETAIL = "plugin_detail"
    const val PLUGIN_PAGE = "plugin_page"
    const val PLUGIN_SETTINGS = "plugin_settings"
    val ALL: List<String> = listOf(
        HOME, CONTACTS, STAR_CIRCLE, PROFILE, SETTINGS, GENERAL_SETTINGS, CHAT, GROUP_CHAT, VIDEO_CALL, VOICE_CALL,
        CREATE_COMPANION, EDIT_COMPANION, COMPANION_DETAIL, CREATE_GROUP, GROUP_DETAIL,
        WECHAT_SETTINGS, WECHAT_BIND, QQBOT_SETTINGS, DATA_BACKUP, ABOUT, AGREEMENT,
        ORIGINOS_ADAPTION, PLUGIN_MARKET, PLUGIN_MANAGE, PLUGIN_DETAIL, PLUGIN_PAGE, PLUGIN_SETTINGS,
        THEME_MARKET, THEME_MANAGE
    )
}

data class ThemePageItem(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val avatar: String = "",
    val messageType: String = "",
    val mediaPath: String = "",
    val timestamp: Long = 0L
) {
    val key: String get() = "$kind:$id"
}

class ThemePageHost(
    val page: String,
    val items: List<ThemePageItem>,
    val state: Map<String, String>,
    val images: Map<String, android.graphics.Bitmap> = emptyMap(),
    val content: (@Composable () -> Unit)? = null,
    val dispatch: (ThemePageAction) -> Unit
)

sealed interface ThemePageAction {
    data class Invoke(val id: String, val payload: Map<String, String> = emptyMap()) : ThemePageAction
    data class Open(val route: String) : ThemePageAction
}

object ThemePaint {
    @Volatile
    private var lightPrimary: Int = 0xFF7BA3C6.toInt()

    @Volatile
    private var darkPrimary: Int = 0xFFA6C4DD.toInt()

    fun update(light: Int, dark: Int) {
        lightPrimary = light
        darkPrimary = dark
    }

    fun tint(isDark: Boolean): Color = Color(if (isDark) darkPrimary else lightPrimary)
}

abstract class ThemeCodeUi {
    @Composable
    open fun Wrap(content: @Composable () -> Unit) {
        content()
    }

    @Composable
    open fun Page(host: ThemePageHost, fallback: @Composable () -> Unit) {
        fallback()
    }
}

internal fun parseThemeColor(raw: String): Int? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val hex = when {
        s.startsWith("0x") || s.startsWith("0X") -> s.substring(2)
        s.startsWith("#") -> s.substring(1)
        else -> s
    }
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> value.toInt()
        else -> null
    }
}

internal fun mergeColors(base: ThemePalette, override: ThemeColorSet): ThemePalette {
    fun pick(raw: String, fallback: Int): Int = parseThemeColor(raw) ?: fallback
    return ThemePalette(
        primary = pick(override.primary, base.primary),
        onPrimary = pick(override.onPrimary, base.onPrimary),
        secondary = pick(override.secondary, base.secondary),
        background = pick(override.background, base.background),
        surface = pick(override.surface, base.surface),
        surfaceVariant = pick(override.surfaceVariant, base.surfaceVariant),
        onSurface = pick(override.onSurface, base.onSurface),
        onSurfaceVariant = pick(override.onSurfaceVariant, base.onSurfaceVariant),
        outline = pick(override.outline, base.outline),
        tertiary = pick(override.tertiary, base.tertiary)
    )
}

fun ThemePalette.toCompose(): PaletteColors = PaletteColors(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    secondary = Color(secondary),
    background = Color(background),
    surface = Color(surface),
    surfaceVariant = Color(surfaceVariant),
    onSurface = Color(onSurface),
    onSurfaceVariant = Color(onSurfaceVariant),
    outline = Color(outline),
    tertiary = Color(tertiary)
)

data class PaletteColors(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val tertiary: Color
)
