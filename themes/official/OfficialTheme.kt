package com.xingshi.ai.themes.official

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.graphics.toArgb
import com.xingshi.ai.theme.framework.DEFAULT_LANGUAGE
import com.xingshi.ai.theme.framework.FRAMEWORK_API
import com.xingshi.ai.theme.framework.OFFICIAL_LANGUAGES
import com.xingshi.ai.theme.framework.OFFICIAL_THEME_ID
import com.xingshi.ai.theme.framework.OfficialThemeProvider
import com.xingshi.ai.theme.framework.ThemeCatalog
import com.xingshi.ai.theme.framework.ThemeManifest
import com.xingshi.ai.theme.framework.ThemePalette
import com.xingshi.ai.uicommon.theme.WeChatDarkBackground
import com.xingshi.ai.uicommon.theme.WeChatDarkCard
import com.xingshi.ai.uicommon.theme.WeChatDarkDivider
import com.xingshi.ai.uicommon.theme.WeChatDarkSurface
import com.xingshi.ai.uicommon.theme.WeChatDarkTextPrimary
import com.xingshi.ai.uicommon.theme.WeChatDarkTextSecondary
import com.xingshi.ai.uicommon.theme.WeChatLightBackground
import com.xingshi.ai.uicommon.theme.WeChatLightCard
import com.xingshi.ai.uicommon.theme.WeChatLightDivider
import com.xingshi.ai.uicommon.theme.WeChatLightSurface
import com.xingshi.ai.uicommon.theme.WeChatLightTextPrimary
import com.xingshi.ai.uicommon.theme.WeChatLightTextSecondary
import com.xingshi.ai.uicommon.theme.PinkDark
import com.xingshi.ai.uicommon.theme.PinkLight
import com.xingshi.ai.uicommon.theme.PinkPrimary
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

 
object OfficialTheme : OfficialThemeProvider {

    val manifest = ThemeManifest(
        id = OFFICIAL_THEME_ID,
        name = "官方主题 · 晴蓝",
        author = "XINGSHI",
        description = "内置官方主题，浅蓝配色，随应用提供全部页面。",
        version = "0.1.0",
        minFramework = FRAMEWORK_API,
        maxFramework = FRAMEWORK_API,
        languages = OFFICIAL_LANGUAGES
    )

    override fun manifest(): ThemeManifest = manifest

    override fun lightPalette(): ThemePalette = ThemePalette(
        primary = PinkPrimary.toArgb(),
        onPrimary = 0xFFFFFFFF.toInt(),
        secondary = WeChatLightTextSecondary.toArgb(),
        background = WeChatLightBackground.toArgb(),
        surface = WeChatLightSurface.toArgb(),
        surfaceVariant = WeChatLightCard.toArgb(),
        onSurface = WeChatLightTextPrimary.toArgb(),
        onSurfaceVariant = WeChatLightTextSecondary.toArgb(),
        outline = WeChatLightDivider.toArgb(),
        tertiary = PinkDark.toArgb()
    )

    override fun darkPalette(): ThemePalette = ThemePalette(
        primary = PinkPrimary.toArgb(),
        onPrimary = 0xFF0D151D.toInt(),
        secondary = PinkLight.toArgb(),
        background = WeChatDarkBackground.toArgb(),
        surface = WeChatDarkSurface.toArgb(),
        surfaceVariant = WeChatDarkCard.toArgb(),
        onSurface = WeChatDarkTextPrimary.toArgb(),
        onSurfaceVariant = WeChatDarkTextSecondary.toArgb(),
        outline = WeChatDarkDivider.toArgb(),
        tertiary = PinkDark.toArgb()
    )

    private val resourcesCache = ConcurrentHashMap<String, Resources>()

    override fun text(context: Context, language: String, key: String, args: List<Any>): String? {
        val resources = resourcesFor(context, language)
        val id = try {
            resources.getIdentifier(key, "string", context.packageName)
        } catch (e: Exception) {
            0
        }
        if (id == 0) return null
        
        
        return try {
            resources.getString(id)
        } catch (e: Exception) {
            null
        }
    }

    private fun resourcesFor(context: Context, language: String): Resources {
        resourcesCache[language]?.let { return it }
        val locale = when (language) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale.forLanguageTag(language).takeIf { it.language.isNotEmpty() }
                ?: Locale.SIMPLIFIED_CHINESE
        }
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
        }
        val localized = context.createConfigurationContext(config)
        return localized.resources.also { resourcesCache[language] = it }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun catalog(context: Context): ThemeCatalog? = try {
        val text = context.assets.open(CATALOG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString(ThemeCatalog.serializer(), text).takeIf { it.themes.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    override val marketSourceLabel: String = "assets:$CATALOG_ASSET"

    private const val CATALOG_ASSET = "themes/market.json"

     
    val defaultLanguage: String = DEFAULT_LANGUAGE
}
