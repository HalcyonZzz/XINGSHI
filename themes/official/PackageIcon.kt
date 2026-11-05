package com.xingshi.ai.theme.icons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.xingshi.ai.common.PackageIconLoader
import com.xingshi.ai.plugins.InstalledPluginRecord
import com.xingshi.ai.plugins.PluginCatalogEntry
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.PluginIcons
import com.xingshi.ai.plugins.PluginStore
import com.xingshi.ai.theme.framework.InstalledTheme
import com.xingshi.ai.theme.framework.ThemeCatalogEntry
import com.xingshi.ai.theme.framework.ThemeIcons
import com.xingshi.ai.theme.framework.ThemeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PackageIconImage(
    image: ImageBitmap?,
    fallback: ImageVector,
    size: Dp,
    contentDescription: String? = null,
    background: Color = Color.Unspecified,
    tint: Color = Color.Unspecified
) {
    val boxColor = if (background == Color.Unspecified) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        background
    }
    val iconTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onPrimary else tint
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(boxColor),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = contentDescription,
                modifier = Modifier.size(size / 2),
                tint = iconTint
            )
        }
    }
}

fun pluginBuiltinIcon(icon: String): ImageVector? = when (icon.trim().lowercase()) {
    "image" -> Icons.Filled.AccountBox
    "text" -> Icons.Filled.TextFields
    "file" -> Icons.Filled.Description
    "audio" -> Icons.Filled.MusicNote
    "video" -> Icons.Filled.Videocam
    "search" -> Icons.Filled.Search
    "star" -> Icons.Filled.Star
    else -> null
}

@Composable
fun rememberPluginCatalogIcon(
    store: PluginStore?,
    entry: PluginCatalogEntry,
    installed: InstalledPluginRecord?
): ImageBitmap? {
    val local = installed?.takeIf { it.manifest.version == entry.version }
    if (local != null && store != null) {
        return rememberPackageIcon(pluginInstalledKey(local)) {
            PluginIcons.read(store.packageDir(local.manifest.id), local.manifest)
        }
    }
    return rememberPackageIcon(pluginCatalogKey(entry)) {
        PluginFramework.fetchIcon(entry.iconUrl)
    }
}

@Composable
fun rememberInstalledPluginIcon(
    store: PluginStore?,
    record: InstalledPluginRecord?
): ImageBitmap? {
    if (store == null || record == null) return null
    return rememberPackageIcon(pluginInstalledKey(record)) {
        PluginIcons.read(store.packageDir(record.manifest.id), record.manifest)
    }
}

@Composable
fun rememberThemeMarketArtwork(entry: ThemeCatalogEntry, name: String): ImageBitmap? {
    val cacheKey = "theme-artwork:${entry.manifest.id}:${entry.manifest.version}:${entry.asset}:$name"
    val image by produceState<ImageBitmap?>(initialValue = null, cacheKey) {
        val bitmap = withContext(Dispatchers.IO) { ThemeRuntime.marketArtwork(entry, name) }
        if (bitmap != null) value = bitmap.asImageBitmap()
    }
    return image
}

@Composable
fun rememberThemeCatalogIcon(
    installed: InstalledTheme?,
    entry: ThemeCatalogEntry
): ImageBitmap? {
    val local = installed?.takeIf { !it.isOfficial && it.manifest.version == entry.manifest.version }
    if (local != null) {
        return rememberPackageIcon(themeInstalledKey(local)) { ThemeIcons.read(local) }
    }
    return rememberPackageIcon(
        "theme-market:${entry.manifest.id}:${entry.manifest.version}:${entry.asset}"
    ) { ThemeRuntime.marketIconBytes(entry) }
}

@Composable
fun rememberInstalledThemeIcon(theme: InstalledTheme?): ImageBitmap? {
    if (theme == null || theme.isOfficial) return null
    return rememberPackageIcon(themeInstalledKey(theme)) { ThemeIcons.read(theme) }
}

@Composable
fun rememberInstalledPluginHostImages(
    store: PluginStore?,
    installed: List<InstalledPluginRecord>
): Map<String, Bitmap> = installed.map { record ->
    rememberInstalledPluginIcon(store, record)?.asAndroidBitmap()
        ?.let { "plugin:${record.manifest.id}" to it }
}.filterNotNull().toMap()

@Composable
fun rememberPluginCatalogHostImages(
    store: PluginStore?,
    entries: List<PluginCatalogEntry>,
    installed: List<InstalledPluginRecord>
): Map<String, Bitmap> = entries.map { entry ->
    val record = installed.firstOrNull { it.manifest.id == entry.id }
    rememberPluginCatalogIcon(store, entry, record)?.asAndroidBitmap()
        ?.let { "plugin:${entry.id}" to it }
}.filterNotNull().toMap()

private val iconLoader = PackageIconLoader()

private fun pluginInstalledKey(record: InstalledPluginRecord): String =
    "plugin:${record.manifest.id}:${record.manifest.version}:${record.installedAt}"

private fun pluginCatalogKey(entry: PluginCatalogEntry): String =
    "plugin-catalog:${entry.id}:${entry.version}:${entry.iconUrl}"

private fun themeInstalledKey(theme: InstalledTheme): String =
    "theme:${theme.manifest.id}:${theme.manifest.version}:${theme.directory.lastModified()}"

@Composable
private fun rememberPackageIcon(
    cacheKey: String,
    fetch: suspend () -> ByteArray?
): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, cacheKey) {
        val bytes = iconLoader.get(cacheKey) ?: iconLoader.load(cacheKey) {
            withContext(Dispatchers.IO) { fetch() }
        }
        if (bytes != null) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (bitmap != null) value = bitmap.asImageBitmap()
        }
    }
    return image
}
