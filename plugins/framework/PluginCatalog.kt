package com.xingshi.ai.plugins

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val icon: String = "",
    val version: String,
    val packageUrl: String,
    val sha256: String = ""
) {
    fun toManifest(): PluginManifest = PluginManifest(
        id = id,
        name = name,
        author = author,
        description = description,
        icon = icon,
        version = version,
        entry = "",
        packageUrl = packageUrl,
        sha256 = sha256
    )
}

@Serializable
data class PluginCatalogDocument(val plugins: List<PluginCatalogEntry> = emptyList())

fun interface PluginCatalogSource {
    suspend fun load(): List<PluginCatalogEntry>
}

 
data class PluginCatalogResult(
    val entries: List<PluginCatalogEntry> = emptyList(),
    val cached: Boolean = false,
    val error: String? = null
)

 
data class PluginCatalogState(
    val entries: List<PluginCatalogEntry> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val cached: Boolean = false,
    val error: String? = null
)

 
interface RefreshableCatalogSource : PluginCatalogSource {
    suspend fun refresh(): PluginCatalogResult
}

private val catalogFormat = Json { ignoreUnknownKeys = true }

private fun parseCatalog(text: String): List<PluginCatalogEntry> = try {
    catalogFormat.decodeFromString(PluginCatalogDocument.serializer(), text).plugins
        .filter { PluginIds.isValid(it.id) }
} catch (e: Exception) {
    emptyList()
}

class RemotePluginCatalogSource(
    private val catalogUrl: String,
    private val cacheFile: File? = null,
    private val fetch: suspend (String) -> String
) : RefreshableCatalogSource {

    override suspend fun load(): List<PluginCatalogEntry> = refresh().entries

    override suspend fun refresh(): PluginCatalogResult {
        val text = try {
            fetch(catalogUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fallback(e.message ?: e.javaClass.simpleName)
        }
        if (!runCatching { catalogFormat.decodeFromString(PluginCatalogDocument.serializer(), text) }.isSuccess) {
            return fallback("插件目录格式无效")
        }
        val entries = parseCatalog(text)
        runCatching {
            cacheFile?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        }
        return PluginCatalogResult(entries)
    }

    private fun fallback(error: String): PluginCatalogResult {
        val cached = runCatching {
            cacheFile?.takeIf { it.isFile }?.readText()?.let { parseCatalog(it) }.orEmpty()
        }.getOrDefault(emptyList())
        return PluginCatalogResult(entries = cached, cached = cached.isNotEmpty(), error = error)
    }
}

class LocalPluginCatalogSource(private val directory: File) : PluginCatalogSource {

    override suspend fun load(): List<PluginCatalogEntry> {
        val file = File(directory, "catalog.json")
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        return parseCatalog(text)
    }
}

class PluginCatalog(private val source: PluginCatalogSource?) {

    val isConfigured: Boolean get() = source != null

    suspend fun entries(): List<PluginCatalogEntry> = source?.load() ?: emptyList()

    suspend fun refresh(): PluginCatalogResult = when (val current = source) {
        null -> PluginCatalogResult()
        is RefreshableCatalogSource -> current.refresh()
        else -> PluginCatalogResult(current.load())
    }

    suspend fun search(query: String): List<PluginCatalogEntry> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return entries()
        return entries().filter {
            it.name.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true)
        }
    }

    suspend fun find(id: String): PluginCatalogEntry? = entries().firstOrNull { it.id == id }
}
