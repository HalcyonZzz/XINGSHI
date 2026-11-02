package com.xingshi.ai.plugins

import java.io.File
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class InstalledPluginRecord(val manifest: PluginManifest, val installedAt: Long)

@Serializable
data class PluginResultRecord(
    val pluginId: String,
    val text: String,
    val submitted: Boolean,
    val createdAt: Long,
    val originalText: String = "",
    val attachmentPaths: List<String> = emptyList()
)

@Serializable
data class PendingAttachment(val path: String, val mimeType: String, val kind: String)

@Serializable
data class PendingChannelRequest(val pluginId: String, val accountId: String, val conversationId: String,
    val senderId: String, val messageId: String, val opaqueRoute: String? = null) {
    fun restore() = com.xingshi.ai.plugins.api.ChannelRequestRef(pluginId,
        com.xingshi.ai.plugins.api.ChannelReplyRoute(accountId, conversationId, senderId, messageId, opaqueRoute))
}

@Serializable
data class PendingOutcomeRecord(
    val companionId: Long,
    val pluginId: String,
    val pluginName: String,
    val reportKind: String,
    val reportText: String = "",
    val originalText: String = "",
    val mergedText: String = "",
    val message: String? = null,
    val createdAt: Long,
    val requestText: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val localMessageId: Long? = null,
    val channelRequest: PendingChannelRequest? = null
)

object PluginIds {

    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun isValid(id: String): Boolean = pattern.matches(id)

    fun require(id: String): String =
        id.takeIf { pattern.matches(it) } ?: throw IllegalArgumentException("插件标识非法")
}

class PluginStore(root: File, private val openSource: (String) -> InputStream = { File(it).inputStream() }) {

    val rootDir: File = root

    private val format = Json { ignoreUnknownKeys = true }

    private val indexFile: File get() = File(rootDir, "plugins.json")

    fun packageDir(id: String): File = File(rootDir, "packages/${PluginIds.require(id)}")

    // 账目是「插件是否已安装」的唯一依据：读不能看到写了一半的索引，读改写之间也必须互斥
    private val indexLock = Any()

    fun installed(): List<InstalledPluginRecord> = synchronized(indexLock) {
        val text = try {
            indexFile.readText()
        } catch (e: Exception) {
            return@synchronized emptyList()
        }
        try {
            format.decodeFromString(ListSerializer(InstalledPluginRecord.serializer()), text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(id: String): InstalledPluginRecord? = installed().firstOrNull { it.manifest.id == id }

    fun save(record: InstalledPluginRecord) = synchronized(indexLock) {
        write(installed().filterNot { it.manifest.id == record.manifest.id } + record)
    }

    fun remove(id: String) = synchronized(indexLock) {
        write(installed().filterNot { it.manifest.id == id })
        packageDir(id).deleteRecursively()
        removeConfigJson(id)
        clearCache(id)
        clearPendingFor(id)
    }

    fun configJson(id: String): String? {
        val file = File(rootDir, "config/${PluginIds.require(id)}.json")
        if (!file.isFile) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun saveConfigJson(id: String, json: String) {
        val file = File(rootDir, "config/${PluginIds.require(id)}.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    fun removeConfigJson(id: String) {
        File(rootDir, "config/${PluginIds.require(id)}.json").delete()
    }

    fun clearCache(id: String) {
        File(rootDir, "cache/${PluginIds.require(id)}").deleteRecursively()
    }

    fun resultsDir(companionId: Long): File = File(rootDir, "results/$companionId")

    fun saveResult(companionId: Long, record: PluginResultRecord): File {
        val directory = resultsDir(companionId)
        directory.mkdirs()
        val file = allocate(directory, "${record.createdAt}-${record.pluginId}", ".json")
        file.writeText(format.encodeToString(PluginResultRecord.serializer(), record))
        return file
    }

    fun pendingDir(): File = File(rootDir, "pending")

    fun savePending(record: PendingOutcomeRecord): File {
        val directory = pendingDir()
        directory.mkdirs()
        val file = File(directory, "${record.companionId}-${PluginIds.require(record.pluginId)}.json")
        file.writeText(format.encodeToString(PendingOutcomeRecord.serializer(), record))
        return file
    }

    fun pending(): List<PendingOutcomeRecord> =
        pendingDir().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                try {
                    format.decodeFromString(PendingOutcomeRecord.serializer(), file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.createdAt }

    fun clearPending(companionId: Long, pluginId: String) {
        File(pendingDir(), "$companionId-${PluginIds.require(pluginId)}.json").delete()
    }

    fun removeCompanion(companionId: Long, sharedPaths: Collection<String> = emptyList()) {
        val pending = pending()
        val candidates = loadResults(companionId).flatMap { it.attachmentPaths } +
            pending.filter { it.companionId == companionId }.flatMap { it.attachments.map { it.path } }
        val retained = File(rootDir, "results").listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }
            .filter { it != companionId }.flatMap { loadResults(it).flatMap { row -> row.attachmentPaths } } +
            pending.filter { it.companionId != companionId }.flatMap { it.attachments.map { it.path } } + sharedPaths
        com.xingshi.ai.common.AccountMediaCleanup.remove(listOf(attachmentsDir()), candidates, retained)
        check(!resultsDir(companionId).exists() || resultsDir(companionId).deleteRecursively()) { "插件资料清理尚未完成" }
        pendingDir().listFiles().orEmpty().filter { it.name.startsWith("$companionId-") }.forEach {
            check(it.delete()) { "插件待交接资料清理尚未完成" }
        }
    }

    private fun clearPendingFor(pluginId: String) {
        // 文件名是「会话ID-插件ID.json」，按后缀匹配会把 my-echo 的待发材料一起删掉，所以按记录里的插件标识比对
        pending().filter { it.pluginId == pluginId }
            .forEach { clearPending(it.companionId, it.pluginId) }
    }

    fun loadResults(companionId: Long): List<PluginResultRecord> =
        resultsDir(companionId).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val text = try {
                    file.readText()
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                try {
                    format.decodeFromString(PluginResultRecord.serializer(), text)
                } catch (e: Exception) {
                    null
                }
            }

    fun attachmentsDir(): File = File(rootDir, "attachments")

    fun saveAttachment(pluginId: String, sourcePath: String, fileName: String): File {
        val directory = File(attachmentsDir(), PluginIds.require(pluginId))
        directory.mkdirs()
        val target = allocate(directory, "${System.currentTimeMillis()}-${File(fileName).name}", "")
        openSource(sourcePath).use { source -> target.outputStream().use { source.copyTo(it) } }
        return target
    }

    // 先原子占名再写入：同名（含同一毫秒内）的并发写入不会互相覆盖
    private fun allocate(directory: File, base: String, suffix: String): File {
        var index = 0
        while (index < 1000) {
            val candidate = File(directory, if (index == 0) "$base$suffix" else "$base-$index$suffix")
            val created = try {
                candidate.createNewFile()
            } catch (e: Exception) {
                return candidate
            }
            if (created) return candidate
            index += 1
        }
        return File(directory, "$base-$index$suffix")
    }

    private fun write(records: List<InstalledPluginRecord>) {
        rootDir.mkdirs()
        val payload = format.encodeToString(ListSerializer(InstalledPluginRecord.serializer()), records)
        // 先写临时文件再改名：写到一半被打断也不会留下残缺索引（残缺索引会让所有插件都看起来没装）
        val tmp = File(rootDir, "${indexFile.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(payload)
            tmp.delete()
        }
    }
}
