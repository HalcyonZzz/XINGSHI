package com.xingshi.ai.theme.framework

class ThemeTextResolver @JvmOverloads constructor(
    private val selectedLanguage: String,
    private val themeLanguageContent: (language: String, key: String) -> String?,
    private val officialText: (language: String, key: String, args: List<Any>) -> String?,
    private val isProtected: (String) -> Boolean = { false }
) {
    fun resolve(key: String, args: List<Any> = emptyList()): String {
        if (!isProtected(key)) {
            themeLanguageContent(selectedLanguage, key)?.takeIf { it.isNotBlank() }?.let { raw ->
                render(raw, args)?.let { return it }
            }
        }
        officialText(selectedLanguage, key, args)?.let { raw ->
            render(raw, args)?.let { return it }
        }
        if (selectedLanguage != DEFAULT_LANGUAGE) {
            officialText(DEFAULT_LANGUAGE, key, args)?.let { raw ->
                render(raw, args)?.let { return it }
            }
        }
        return PLACEHOLDER
    }

    private fun render(raw: String, args: List<Any>): String? {
        if (raw.isBlank()) return null
        if (!FORMAT_SPECIFIER.containsMatchIn(raw)) return raw
        return try {
            String.format(raw, *args.toTypedArray())
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val PLACEHOLDER = "…"
        private val FORMAT_SPECIFIER = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")
    }
}

val PROTECTED_TEXT_KEYS: Set<String> = setOf(
    "agreement_age",
    "agreement_title"
)

data class ThemeStrings(val languages: Map<String, Map<String, String>>) {
    fun get(language: String, key: String): String? = languages[language]?.get(key)
    val availableLanguages: List<String> get() = languages.keys.toList()

    companion object {
        val EMPTY = ThemeStrings(emptyMap())
    }
}
