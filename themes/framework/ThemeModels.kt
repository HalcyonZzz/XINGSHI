package com.xingshi.ai.theme.framework

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

 
const val FRAMEWORK_API = 1

 
const val OFFICIAL_THEME_ID = "official"

 
const val DEFAULT_LANGUAGE = "zh-CN"

 
val OFFICIAL_LANGUAGES: List<String> = listOf("zh-CN", "zh-TW", "en", "ja", "ko")

val LANGUAGE_LABELS: Map<String, String> = mapOf(
    "zh-CN" to "简体中文",
    "zh-TW" to "繁體中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어"
)

@Serializable
data class ThemeNavItem(
    val route: String,
    val label: String = "",
    val icon: String = ""
)

@Serializable
data class ThemeNavigation(
     
    val position: String = "bottom",
     
    val items: List<ThemeNavItem> = emptyList()
)

@Serializable
data class ThemeInputBar(
     
    val sendOnLeft: Boolean = false
)

@Serializable
data class ThemeColorSet(
    val primary: String = "",
    val onPrimary: String = "",
    val secondary: String = "",
    val background: String = "",
    val surface: String = "",
    val surfaceVariant: String = "",
    val onSurface: String = "",
    val onSurfaceVariant: String = "",
    val outline: String = "",
    val tertiary: String = ""
)

@Serializable
data class ThemeColors(
    val light: ThemeColorSet = ThemeColorSet(),
    val dark: ThemeColorSet = ThemeColorSet()
)

@Serializable
data class ThemeManifest(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val icon: String = "",
    val preview: String = "",
    val version: String = "1.0.0",
    val minFramework: Int = FRAMEWORK_API,
    val maxFramework: Int = FRAMEWORK_API,
     
    val entry: String = "",
    val languages: List<String> = emptyList(),
    val navigation: ThemeNavigation = ThemeNavigation(),
    val inputBar: ThemeInputBar = ThemeInputBar(),
    val colors: ThemeColors = ThemeColors()
) {
    val supportsFramework: Boolean
        get() = minFramework <= FRAMEWORK_API && FRAMEWORK_API <= maxFramework
}

object ThemeManifests {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): ThemeManifest? = try {
        json.decodeFromString(ThemeManifest.serializer(), text).takeIf { valid(it) }
    } catch (e: Exception) {
        null
    }

    fun parseOrThrow(text: String): ThemeManifest = parse(text) ?: throw IllegalArgumentException("主题清单无效")

    fun encode(manifest: ThemeManifest): String = json.encodeToString(ThemeManifest.serializer(), manifest)

    fun valid(manifest: ThemeManifest): Boolean {
        if (manifest.id.isBlank() || manifest.name.isBlank()) return false
        if (!ID_PATTERN.matches(manifest.id)) return false
        if (SCHEDULE_PATTERN.matches(manifest.id)) return false
        if (manifest.version.isBlank()) return false
        if (manifest.minFramework <= 0 || manifest.maxFramework < manifest.minFramework) return false
        if (manifest.versionCode() <= 0L) return false
        return true
    }

    fun versionCode(version: String): Long {
        val parts = version.trim().split(".").take(3)
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return 0L
        var code = 0L
        for (part in parts) code = code * 100 + part.toInt()
        return code * when (parts.size) {
            1 -> 10000L
            2 -> 100L
            else -> 1L
        }
    }

    private fun ThemeManifest.versionCode(): Long = versionCode(version)

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val SCHEDULE_PATTERN = Regex("^(official|android|app)$")
}

@Serializable
data class ThemeCatalogEntry(
    val manifest: ThemeManifest,
     
    val asset: String = "",
    val changelog: String = "",
)

@Serializable
data class ThemeCatalog(
    val name: String = "",
    val description: String = "",
    val themes: List<ThemeCatalogEntry> = emptyList()
) {
    fun search(query: String): List<ThemeCatalogEntry> {
        val q = query.trim()
        if (q.isEmpty()) return themes
        return themes.filter {
            it.manifest.name.contains(q, ignoreCase = true) ||
                it.manifest.description.contains(q, ignoreCase = true) ||
                it.manifest.author.contains(q, ignoreCase = true)
        }
    }
}

data class InstalledTheme(
    val manifest: ThemeManifest,
    val directory: java.io.File,
    val isOfficial: Boolean = false
)

enum class ThemeSource { OFFICIAL, MARKET, LOCAL }
