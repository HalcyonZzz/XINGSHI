package com.xingshi.ai.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val icon: String = "",
    val version: String,
    val entry: String,
    val packageUrl: String = "",
    val sha256: String = "",
    val minHostApi: Int = 1
)

object PluginManifests {

    const val HOST_API = 3

    private val format = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PluginManifest? {
        val manifest = try {
            format.decodeFromString(PluginManifest.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        return manifest.takeIf {
            it.id.isNotBlank() && it.name.isNotBlank() && it.version.isNotBlank() && it.entry.isNotBlank()
        }
    }

    fun require(text: String): PluginManifest = parse(text) ?: throw IllegalArgumentException("插件清单无效")

    fun versionCode(version: String): Long {
        val parts = version.trim().split(".").take(3)
        var code = 0L
        for (part in parts) code = code * 100 + (part.toLongOrNull() ?: return 0L)
        return code * when (parts.size) {
            1 -> 10000L
            2 -> 100L
            else -> 1L
        }
    }
}
