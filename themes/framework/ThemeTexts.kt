package com.xingshi.ai.theme.framework

/**
 * 文案解析：主题选定语言 → 官方同语言 → 官方默认语言，逐项回退。
 * 纯 Kotlin 实现，便于 JVM 单测；官方实现与主题包只提供查表能力。
 */
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

    /**
     * 统一格式化：无占位符按原文显示（普通 100% 文本不被误伤）；
     * 有占位符则按参数格式化，缺参数／错误占位符返回 null，交给下一层文案兜底，
     * 绝不把 %1$s 直接显示给用户。
     */
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
        /** 任何缺失都不允许显示内部 key 或空按钮，最终占位符保持可读。 */
        const val PLACEHOLDER = "…"
        private val FORMAT_SPECIFIER = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")
    }
}

/** 必要同意/告知文案：只允许官方来源，主题仅能调整呈现，不得改写内容。 */
val PROTECTED_TEXT_KEYS: Set<String> = setOf(
    "agreement_age",
    "agreement_title"
)

/** 主题包内的文案表。 */
data class ThemeStrings(val languages: Map<String, Map<String, String>>) {
    fun get(language: String, key: String): String? = languages[language]?.get(key)
    val availableLanguages: List<String> get() = languages.keys.toList()

    companion object {
        val EMPTY = ThemeStrings(emptyMap())
    }
}
