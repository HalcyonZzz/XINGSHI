package com.xingshi.ai.theme.framework

import com.xingshi.ai.common.PackageIcons
import java.io.File

object ThemeIcons {

    fun read(directory: File, manifest: ThemeManifest, isOfficial: Boolean = false): ByteArray? {
        if (isOfficial) return null
        return PackageIcons.resolve(directory, manifest.icon)
            ?.let(PackageIcons::readFile)
            ?.takeIf(PackageIcons::valid)
    }

    fun read(theme: InstalledTheme): ByteArray? =
        read(theme.directory, theme.manifest, theme.isOfficial)
}
