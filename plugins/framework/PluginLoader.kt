package com.xingshi.ai.plugins

import com.xingshi.ai.plugins.api.XingShiPlugin
import dalvik.system.DexClassLoader
import java.io.File
import java.net.URL

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
    return PluginResourceClassLoader(dexPath, packageDir.absolutePath, packageDir, parent)
}

private const val PLUGIN_RESOURCE_ROOT = "res"

internal fun pluginResourceFile(packageDir: File, name: String): File? {
    if (name.isBlank() || name.startsWith("/") || name.startsWith("\\")) return null
    return try {
        val root = File(packageDir, PLUGIN_RESOURCE_ROOT).canonicalFile
        val file = File(root, name).canonicalFile
        val inside = file.path.startsWith(root.path + File.separator)
        file.takeIf { inside && it.isFile }
    } catch (e: Exception) {
        null
    }
}

private class PluginResourceClassLoader(
    dexPath: String,
    optimizedDirectory: String,
    private val packageDir: File,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {
    override fun findResource(name: String): URL? = pluginResourceFile(packageDir, name)?.toURI()?.toURL()
}
