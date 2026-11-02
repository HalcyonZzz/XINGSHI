package com.xingshi.ai.theme.framework

import java.io.File

sealed interface ThemeSwitchResult {
    data class Success(val theme: InstalledTheme) : ThemeSwitchResult
    data class Failure(val message: String) : ThemeSwitchResult
}

sealed interface ThemeInstallOutcome {
    data class Success(val theme: InstalledTheme, val updated: Boolean) : ThemeInstallOutcome
    data class Failure(val message: String) : ThemeInstallOutcome
}

sealed interface ThemeUninstallOutcome {
    data class Success(val fallbackToOfficial: Boolean) : ThemeUninstallOutcome
    data class Failure(val message: String) : ThemeUninstallOutcome
}

/**
 * 已安装主题的加载、安装、更新、卸载与选中状态。
 * 纯文件逻辑，不依赖 Android，可直接 JVM 单测。
 */
class ThemeEngine(
    val themesDir: File,
    private val prefs: ThemePreferencesAccess,
    private val officialManifest: ThemeManifest
) {

    fun installedThemes(): List<InstalledTheme> {
        val result = mutableListOf(officialTheme())
        themesDir.listFiles { f -> f.isDirectory && !f.name.contains(".staging-") && !f.name.contains(".bak-") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { dir ->
                val loaded = ThemePackages.load(dir)
                if (loaded is ThemePackageResult.Success && loaded.manifest.id == dir.name) {
                    result += InstalledTheme(loaded.manifest, dir, isOfficial = false)
                }
            }
        return result
    }

    fun officialTheme(): InstalledTheme = InstalledTheme(officialManifest, themesDir, isOfficial = true)

    fun find(id: String): InstalledTheme? =
        if (id == OFFICIAL_THEME_ID || id == officialManifest.id) officialTheme()
        else installedThemes().firstOrNull { it.manifest.id == id }

    /** 冷启动解析：选中包缺失/损坏/不兼容时回退官方，并清理选择。 */
    fun resolveActive(): InstalledTheme {
        val id = prefs.selectedThemeId
        if (id == OFFICIAL_THEME_ID || id == officialManifest.id) return officialTheme()
        val found = find(id)
        if (found == null) {
            prefs.selectedThemeId = OFFICIAL_THEME_ID
            return officialTheme()
        }
        return found
    }

    /** 校验并加载通过后才写入选中选择；失败保留当前主题。 */
    fun switchTo(id: String): ThemeSwitchResult {
        val theme = find(id) ?: return ThemeSwitchResult.Failure("主题未安装")
        if (!theme.manifest.supportsFramework) return ThemeSwitchResult.Failure("主题与当前框架版本不兼容")
        if (!theme.isOfficial) {
            val loaded = ThemePackages.load(theme.directory)
            if (loaded is ThemePackageResult.Failure) return ThemeSwitchResult.Failure(loaded.message)
        }
        prefs.selectedThemeId = theme.manifest.id
        return ThemeSwitchResult.Success(theme)
    }

    /** 已解包但尚未替换旧包的主题（代码主题需在提交前完成加载验证）。 */
    data class StagedTheme(
        val manifest: ThemeManifest,
        val staging: File,
        val target: File,
        val existing: Boolean
    )

    sealed interface ThemeStageResult {
        data class Success(val staged: StagedTheme) : ThemeStageResult
        data class Failure(val message: String) : ThemeStageResult
    }

    /** 校验并解包到 staging；不触碰旧包、不写选择偏好。 */
    fun stage(bytes: ByteArray, expectedId: String? = null): ThemeStageResult {
        val inspected = ThemePackages.inspect(bytes)
        if (inspected is ThemePackageResult.Failure) return ThemeStageResult.Failure(inspected.message)
        val manifest = (inspected as ThemePackageResult.Success).manifest
        if (manifest.id == OFFICIAL_THEME_ID) return ThemeStageResult.Failure("官方主题不可覆盖")
        if (expectedId != null && manifest.id != expectedId) return ThemeStageResult.Failure("主题 ID 不匹配")
        val target = File(themesDir, manifest.id)
        val staged = ThemePackages.stageTo(bytes, target)
        if (staged is ThemePackages.StagedResult.Failure) return ThemeStageResult.Failure(staged.message)
        return ThemeStageResult.Success(
            StagedTheme(
                manifest = manifest,
                staging = (staged as ThemePackages.StagedResult.Success).staging,
                target = target,
                existing = target.exists()
            )
        )
    }

    /** 提交 staging（原子替换）；调用方须先完成代码加载验证。 */
    fun commit(staged: StagedTheme): ThemeInstallOutcome {
        val error = ThemePackages.commitStaging(staged.staging, staged.target)
        if (error != null) {
            ThemePackages.discardStaging(staged.staging)
            return ThemeInstallOutcome.Failure(error)
        }
        val verified = ThemePackages.load(staged.target)
        if (verified is ThemePackageResult.Failure) {
            return ThemeInstallOutcome.Failure(verified.message)
        }
        return ThemeInstallOutcome.Success(
            InstalledTheme(staged.manifest, staged.target, isOfficial = false),
            updated = staged.existing
        )
    }

    fun discard(staged: StagedTheme) {
        ThemePackages.discardStaging(staged.staging)
    }

    /** 纯文件安装（无代码验证）；运行时安装走 stage + 验证 + commit。 */
    fun install(bytes: ByteArray, expectedId: String? = null): ThemeInstallOutcome {
        val staged = stage(bytes, expectedId)
        if (staged is ThemeStageResult.Failure) return ThemeInstallOutcome.Failure(staged.message)
        return commit((staged as ThemeStageResult.Success).staged)
    }

    fun uninstall(id: String): ThemeUninstallOutcome {
        if (id == OFFICIAL_THEME_ID || id == officialManifest.id) {
            return ThemeUninstallOutcome.Failure("官方主题始终保留，不能卸载")
        }
        val theme = installedThemes().firstOrNull { it.manifest.id == id }
            ?: return ThemeUninstallOutcome.Failure("主题未安装")
        val wasSelected = prefs.selectedThemeId == id
        if (wasSelected) prefs.selectedThemeId = OFFICIAL_THEME_ID
        val deleted = theme.directory.deleteRecursively()
        return if (deleted) ThemeUninstallOutcome.Success(fallbackToOfficial = wasSelected)
        else ThemeUninstallOutcome.Failure("卸载失败")
    }

    fun hasUpdate(id: String, catalog: ThemeCatalog?): Boolean {
        val entry = catalog?.themes?.firstOrNull { it.manifest.id == id } ?: return false
        val installed = installedThemes().firstOrNull { it.manifest.id == id } ?: return false
        return ThemeManifests.versionCode(entry.manifest.version) >
            ThemeManifests.versionCode(installed.manifest.version)
    }
}
