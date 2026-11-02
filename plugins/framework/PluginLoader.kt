package com.xingshi.ai.plugins

import com.xingshi.ai.plugins.api.XingShiPlugin
import dalvik.system.DexClassLoader
import java.io.File

class PluginLoader(
    private val classLoaderFactory: (File, ClassLoader) -> ClassLoader = ::defaultPluginClassLoader
) {

    fun load(manifest: PluginManifest, packageDir: File, parent: ClassLoader): XingShiPlugin? {
        if (manifest.entry.isBlank()) return null
        return try {
            val classLoader = classLoaderFactory(packageDir, parent)
            val instance = Class.forName(manifest.entry, true, classLoader).getDeclaredConstructor().newInstance()
            val plugin = instance as? XingShiPlugin ?: return null
            if (plugin.id == manifest.id) plugin else null
        } catch (t: Throwable) {
            null
        }
    }
}

internal fun defaultPluginClassLoader(packageDir: File, parent: ClassLoader): ClassLoader {
    val dexPath = packageDir.listFiles { file -> file.isFile && file.name.endsWith(".dex") }
        .orEmpty()
        .sortedBy { it.name }
        .joinToString(File.pathSeparator) { it.absolutePath }
    return DexClassLoader(dexPath, packageDir.absolutePath, null, parent)
}
