package com.xingshi.ai.theme.framework

import android.content.Context
import com.xingshi.ai.common.PackageIcons
import com.xingshi.ai.uicommon.theme.ThemeMode
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThemeState(
    val themeId: String = OFFICIAL_THEME_ID,
    val themeName: String = "",
    val language: String = DEFAULT_LANGUAGE,
    val themeMode: String = ThemeMode.SYSTEM.name,
    val installed: List<InstalledTheme> = emptyList(),
    val availableLanguages: List<String> = OFFICIAL_LANGUAGES,
    val officialTheme: InstalledTheme? = null,
    val marketAvailable: Boolean = false,
    val revision: Long = 0L
)

object ThemeRuntime {

    private var appContext: Context? = null
    private var provider: OfficialThemeProvider? = null
    private var engine: ThemeEngine? = null
    private var prefs: ThemePreferencesAccess? = null
    private var codeUi: ThemeCodeUi? = null
    private var codeUiForTheme: String? = null
    private var activeManifestCache: ThemeManifest? = null
    private var activeStringsCache: ThemeStrings = ThemeStrings.EMPTY
    private val artworkCache = object : android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    private val _state = MutableStateFlow(ThemeState())
    val state: StateFlow<ThemeState> = _state.asStateFlow()

    private val _recoveryMode = MutableStateFlow(false)
    val recoveryMode: StateFlow<Boolean> = _recoveryMode.asStateFlow()

    fun requestRecovery() {
        _recoveryMode.value = true
    }

    fun exitRecovery() {
        _recoveryMode.value = false
    }

    fun registerOfficial(context: Context, official: OfficialThemeProvider) {
        codeUi = null
        codeUiForTheme = null
        activeManifestCache = null
        activeStringsCache = ThemeStrings.EMPTY
        artworkCache.evictAll()
        _recoveryMode.value = false
        appContext = context.applicationContext
        provider = official
        val p = SharedThemePreferences(context)
        prefs = p
        engine = ThemeEngine(File(context.applicationContext.filesDir, "themes"), p, official.manifest())
        refresh()
    }

    fun initialize(context: Context) {
        if (engine == null) {
            appContext = context.applicationContext
            val p = SharedThemePreferences(context)
            prefs = p
            val manifest = provider?.manifest() ?: return
            engine = ThemeEngine(File(context.applicationContext.filesDir, "themes"), p, manifest)
            refresh()
        }
    }

    fun refresh() {
        val eng = engine ?: return
        val p = prefs ?: return
        val active = eng.resolveActive()
        if (!refreshCodeUi(active)) {
            p.selectedThemeId = OFFICIAL_THEME_ID
            codeUi = null
            codeUiForTheme = null
            publishState(eng, p, eng.officialTheme())
            return
        }
        publishState(eng, p, active)
    }

    private fun publishState(eng: ThemeEngine, p: ThemePreferencesAccess, active: InstalledTheme) {
        val installed = eng.installedThemes()
        val available = (OFFICIAL_LANGUAGES + active.manifest.languages).distinct()
        val market = provider?.catalog(appContext ?: return)
        activeManifestCache = if (active.isOfficial) null else active.manifest
        activeStringsCache = if (active.isOfficial) ThemeStrings.EMPTY else {
            when (val loaded = ThemePackages.load(active.directory)) {
                is ThemePackageResult.Success -> ThemeStrings(loaded.strings)
                else -> ThemeStrings.EMPTY
            }
        }
        _state.value = ThemeState(
            themeId = active.manifest.id,
            themeName = active.manifest.name,
            language = p.language,
            themeMode = p.themeMode,
            installed = installed,
            availableLanguages = available,
            officialTheme = eng.officialTheme(),
            marketAvailable = market != null,
            revision = _state.value.revision + 1
        )
    }

    fun activeTheme(): InstalledTheme? = engine?.resolveActive()

    fun installedThemes(): List<InstalledTheme> = engine?.installedThemes().orEmpty()

    fun officialTheme(): InstalledTheme? = engine?.officialTheme()

    fun themeManifest(): ThemeManifest? = activeManifestCache

    fun navigation(): ThemeNavigation = engine?.resolveActive()?.manifest?.navigation ?: ThemeNavigation()

    fun inputBar(): ThemeInputBar = engine?.resolveActive()?.manifest?.inputBar ?: ThemeInputBar()

    fun switchTheme(id: String): ThemeSwitchResult {
        val eng = engine ?: return ThemeSwitchResult.Failure("主题框架未初始化")
        val target = eng.find(id) ?: return ThemeSwitchResult.Failure("主题未安装")
        if (!target.isOfficial && target.manifest.entry.isNotBlank() &&
            loadCodeUi(target.directory, target.manifest.entry) == null
        ) {
            return ThemeSwitchResult.Failure("主题代码加载失败：入口、DEX 或接口不可用")
        }
        val result = eng.switchTo(id)
        if (result is ThemeSwitchResult.Success) {
            codeUiForTheme = null
            refresh()
        }
        return result
    }

    fun restoreOfficial(): ThemeSwitchResult = switchTheme(OFFICIAL_THEME_ID)

    fun installPackage(bytes: ByteArray): ThemeInstallOutcome = installPackageInternal(bytes, expectedId = null)

    fun installFromAsset(entry: ThemeCatalogEntry): ThemeInstallOutcome {
        val ctx = appContext ?: return ThemeInstallOutcome.Failure("主题框架未初始化")
        if (entry.asset.isBlank()) return ThemeInstallOutcome.Failure("目录条目缺少主题包")
        val bytes = try {
            ctx.assets.open(entry.asset).use { it.readBytes() }
        } catch (e: Exception) {
            return ThemeInstallOutcome.Failure("主题包缺失: ${e.message}")
        }
        return installPackageInternal(bytes, expectedId = entry.manifest.id)
    }

    private fun installPackageInternal(bytes: ByteArray, expectedId: String?): ThemeInstallOutcome {
        val eng = engine ?: return ThemeInstallOutcome.Failure("主题框架未初始化")
        val stagedResult = eng.stage(bytes, expectedId)
        if (stagedResult is ThemeEngine.ThemeStageResult.Failure) {
            return ThemeInstallOutcome.Failure(stagedResult.message)
        }
        val staged = (stagedResult as ThemeEngine.ThemeStageResult.Success).staged
        if (staged.manifest.entry.isNotBlank() && loadCodeUi(staged.staging, staged.manifest.entry) == null) {
            eng.discard(staged)
            return ThemeInstallOutcome.Failure("主题代码加载失败：入口、DEX 或接口不可用")
        }
        val outcome = eng.commit(staged)
        if (outcome is ThemeInstallOutcome.Success) {
            val selected = prefs?.selectedThemeId
            if (selected == staged.manifest.id) codeUiForTheme = null
            refresh()
        }
        return outcome
    }

    fun marketArtwork(entry: ThemeCatalogEntry, name: String): android.graphics.Bitmap? {
        val ctx = appContext ?: return null
        val cacheKey = entry.asset + "#" + entry.manifest.version + "#" + name
        artworkCache.get(cacheKey)?.let { return it }
        val bitmap = try {
            if (entry.asset.isBlank()) null else {
                val bytes = ctx.assets.open(entry.asset).use {
                    PackageIcons.readBounded(it, ThemePackages.MAX_PACKAGE_BYTES)
                } ?: return null
                val image = ThemePackages.readEntry(bytes, name) ?: return null
                android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size)
            }
        } catch (e: Exception) {
            null
        }
        if (bitmap != null) artworkCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun marketIconBytes(entry: ThemeCatalogEntry): ByteArray? {
        val ctx = appContext ?: return null
        if (entry.asset.isBlank()) return null
        return try {
            val packageBytes = ctx.assets.open(entry.asset).use {
                PackageIcons.readBounded(it, ThemePackages.MAX_PACKAGE_BYTES)
            } ?: return null
            ThemePackages.readEntry(packageBytes, PackageIcons.FILE_NAME, PackageIcons.MAX_BYTES.toLong())
                ?.takeIf(PackageIcons::valid)
        } catch (e: Exception) {
            null
        }
    }

    fun uninstallTheme(id: String): ThemeUninstallOutcome {
        val outcome = engine?.uninstall(id) ?: return ThemeUninstallOutcome.Failure("主题框架未初始化")
        if (outcome is ThemeUninstallOutcome.Success) refresh()
        return outcome
    }

    fun marketCatalogOrNull(): ThemeCatalog? {
        val ctx = appContext ?: return null
        return provider?.catalog(ctx)
    }

    fun marketSourceLabel(): String = provider?.marketSourceLabel.orEmpty()

    fun hasUpdate(id: String): Boolean = engine?.hasUpdate(id, marketCatalogOrNull()) ?: false

    fun resolveText(key: String, args: List<Any> = emptyList()): String {
        val ctx = appContext ?: return ThemeTextResolver.PLACEHOLDER
        val selected = prefs?.language ?: DEFAULT_LANGUAGE
        val prov = provider
        val strings = activeStringsCache
        val resolver = ThemeTextResolver(
            selectedLanguage = selected,
            themeLanguageContent = { lang, k -> strings.get(lang, k) },
            officialText = { lang, k, a -> prov?.text(ctx, lang, k, a) },
            isProtected = { it in PROTECTED_TEXT_KEYS }
        )
        return resolver.resolve(key, args)
    }

    fun appearance(context: Context): ResolvedAppearance {
        val eng = engine
        val prov = provider
        val active = eng?.resolveActive()
        val manifest = if (active?.isOfficial == true) null else active?.manifest
        val mode = prefs?.themeMode ?: ThemeMode.SYSTEM.name
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = if (mode == ThemeMode.DARK.name) true else systemDark
        val colors = manifest?.colors
        val lightBase = prov?.lightPalette() ?: defaultPalette()
        val darkBase = prov?.darkPalette() ?: defaultPalette()
        val lightPalette = if (colors == null) lightBase else mergeColors(lightBase, colors.light)
        val darkPalette = if (colors == null) darkBase else mergeColors(darkBase, colors.dark)
        ThemePaint.update(lightPalette.primary, darkPalette.primary)
        val palette = if (isDark) darkPalette else lightPalette
        return ResolvedAppearance(
            themeId = manifest?.id ?: OFFICIAL_THEME_ID,
            themeName = manifest?.name.orEmpty(),
            isDark = isDark,
            palette = palette,
            navigation = manifest?.navigation ?: ThemeNavigation(),
            inputBar = manifest?.inputBar ?: ThemeInputBar()
        )
    }

    private fun defaultPalette(): ThemePalette = ThemePalette(
        0xFF7BA3C6.toInt(), 0xFFFFFFFF.toInt(), 0xFF4E7391.toInt(), 0xFFEEF4FA.toInt(),
        0xFFE4EDF6.toInt(), 0xFFD0DEEB.toInt(), 0xFF17242F.toInt(), 0xFF4F6B85.toInt(),
        0xFFC9DCEE.toInt(), 0xFF5E84A6.toInt()
    )

    fun availableLanguages(): List<String> {
        val active = engine?.resolveActive()?.manifest
        return (OFFICIAL_LANGUAGES + (active?.languages ?: emptyList())).distinct()
    }

    fun currentLanguage(): String = prefs?.language ?: DEFAULT_LANGUAGE

    fun setLanguage(language: String): Boolean {
        if (language !in availableLanguages()) return false
        prefs?.language = language
        applyLocaleToJvm(language)
        val s = _state.value
        _state.value = s.copy(language = language, revision = s.revision + 1)
        return true
    }

    fun applyBaseContextLocale(base: Context): Context {
        val p = SharedThemePreferences(base)
        val language = p.language
        val locale = localeOf(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        if (p.themeMode == ThemeMode.DARK.name) {
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        return base.createConfigurationContext(config)
    }

    fun localeOf(language: String): Locale = when (language) {
        "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.ENGLISH
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        else -> Locale.forLanguageTag(language).takeIf { it.language.isNotEmpty() }
            ?: Locale.SIMPLIFIED_CHINESE
    }

    private fun applyLocaleToJvm(language: String) {
        try {
            Locale.setDefault(localeOf(language))
        } catch (_: Exception) {
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs?.themeMode = mode.name
        val s = _state.value
        _state.value = s.copy(themeMode = mode.name, revision = s.revision + 1)
    }

    fun themeMode(): ThemeMode = try {
        ThemeMode.valueOf(prefs?.themeMode ?: ThemeMode.SYSTEM.name)
    } catch (e: Exception) {
        ThemeMode.SYSTEM
    }

    fun codeUi(): ThemeCodeUi? = codeUi

    private fun refreshCodeUi(active: InstalledTheme): Boolean {
        if (active.isOfficial || active.manifest.entry.isBlank()) {
            codeUi = null
            codeUiForTheme = null
            return true
        }
        if (codeUi != null && codeUiForTheme == active.manifest.id) return true
        val loaded = loadCodeUi(active.directory, active.manifest.entry)
        if (loaded == null) {
            codeUi = null
            codeUiForTheme = null
            return false
        }
        codeUi = loaded
        codeUiForTheme = active.manifest.id
        return true
    }

    private fun loadCodeUi(directory: File, entry: String): ThemeCodeUi? = try {
        val dexFiles = directory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dex") }
            .sortedBy { it.absolutePath }
            .toList()
        if (dexFiles.isEmpty()) null
        else {
            dexFiles.forEach { dex -> runCatching { dex.setReadOnly() } }
            val optimized = File(appContext?.codeCacheDir ?: directory, "theme-code").apply { mkdirs() }
            val dexPaths = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val parent = ThemeRuntime::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val loader = DexClassLoader(dexPaths, optimized.absolutePath, null, parent)
            val cls = Class.forName(entry, true, loader)
            val instance = cls.getDeclaredConstructor().newInstance()
            instance as? ThemeCodeUi
        }
    } catch (t: Throwable) {
        null
    }
}
