package com.xingshi.ai.theme.framework

import android.content.Context
import com.xingshi.ai.uicommon.theme.ThemeMode

interface ThemePreferencesAccess {
    var selectedThemeId: String
    var language: String
    var themeMode: String
}

class SharedThemePreferences(context: Context) : ThemePreferencesAccess {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val languagePrefs = context.applicationContext.getSharedPreferences("language_prefs", Context.MODE_PRIVATE)

    override var selectedThemeId: String
        get() = prefs.getString(KEY_THEME_ID, OFFICIAL_THEME_ID) ?: OFFICIAL_THEME_ID
        set(value) { prefs.edit().putString(KEY_THEME_ID, value).commit() }

    override var language: String
        get() = languagePrefs.getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) { languagePrefs.edit().putString("language", value).commit() }

    override var themeMode: String
        get() = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        set(value) { prefs.edit().putString("theme_mode", value).commit() }

    companion object {
        const val PREFS_NAME = "theme_prefs"
        const val KEY_THEME_ID = "selected_theme_id"
    }
}

class MemoryThemePreferences(
    override var selectedThemeId: String = OFFICIAL_THEME_ID,
    override var language: String = DEFAULT_LANGUAGE,
    override var themeMode: String = ThemeMode.SYSTEM.name
) : ThemePreferencesAccess
