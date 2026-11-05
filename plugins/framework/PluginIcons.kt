package com.xingshi.ai.plugins

import com.xingshi.ai.common.PackageIcons
import java.io.File

object PluginIcons {

    fun file(directory: File, manifest: PluginManifest): File? =
        PackageIcons.resolve(directory, manifest.icon)

    fun read(directory: File, manifest: PluginManifest): ByteArray? =
        file(directory, manifest)?.let(PackageIcons::readFile)?.takeIf(PackageIcons::valid)

    fun read(store: PluginStore, record: InstalledPluginRecord): ByteArray? =
        read(store.packageDir(record.manifest.id), record.manifest)

    fun remoteUrl(entry: PluginCatalogEntry): String? =
        entry.iconUrl.trim().takeIf(PackageIcons::isHttpsUrl)
}
