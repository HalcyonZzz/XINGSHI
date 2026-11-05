package com.xingshi.ai.plugins

import com.xingshi.ai.plugins.api.XingShiPlugin
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PluginInstallResult {
    data class Success(val record: InstalledPluginRecord) : PluginInstallResult

    data class Failure(val message: String) : PluginInstallResult
}

data class PluginLimits(
    val maxPackageBytes: Int = 64 * 1024 * 1024,
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
    val maxEntries: Int = 4096
)

object PluginPackages {

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun markDexReadOnly(dir: File) {
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dex") }
            .forEach { it.setReadOnly() }
    }

    fun loadable(manifest: PluginManifest, dir: File): Boolean {
        if (manifest.entry.isBlank()) return false
        if (!onAndroid()) return containsEntryClass(dir, manifest.entry)
        return loadableByLoader(manifest, dir) { PluginLoader().load(manifest, it, PluginPackages::class.java.classLoader ?: ClassLoader.getSystemClassLoader()) }
    }

    internal fun loadableByLoader(manifest: PluginManifest, dir: File, load: (File) -> XingShiPlugin?): Boolean =
        try {
            manifest.entry.isNotBlank() && load(dir)?.id == manifest.id
        } catch (t: Throwable) {
            false
        }

    private fun onAndroid(): Boolean {
        val vm = System.getProperty("java.vm.name").orEmpty()
        val runtime = System.getProperty("java.runtime.name").orEmpty()
        return vm.contains("Dalvik", ignoreCase = true) || runtime.contains("Android", ignoreCase = true)
    }

    private fun containsEntryClass(dir: File, entry: String): Boolean {
        val descriptor = "L" + entry.replace('.', '/') + ";"
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dex") }
            .any { it.readBytes().toString(Charsets.ISO_8859_1).contains(descriptor) }
    }

    fun unpack(bytes: ByteArray, target: File, limits: PluginLimits = PluginLimits()): PluginManifest {
        if (bytes.size > limits.maxPackageBytes) throw IllegalArgumentException("插件包过大")
        target.mkdirs()
        val root = target.canonicalFile
        var count = 0
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > limits.maxEntries) throw IllegalArgumentException("插件包条目过多")
                val file = File(root, entry.name)
                if (!file.canonicalPath.startsWith(root.path + File.separator)) {
                    throw IllegalArgumentException("插件包路径非法")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var size = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            size += read
                            total += read
                            if (size > limits.maxEntryBytes) throw IllegalArgumentException("插件包条目过大")
                            if (total > limits.maxTotalBytes) throw IllegalArgumentException("插件包解压过大")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val manifestFile = File(root, "plugin.json")
        if (!manifestFile.isFile) throw IllegalArgumentException("插件包清单无效")
        return PluginManifests.parse(manifestFile.readText()) ?: throw IllegalArgumentException("插件包清单无效")
    }
}

class PluginInstaller(
    private val store: PluginStore,
    private val fetch: suspend (String) -> ByteArray,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val limits: PluginLimits = PluginLimits(),
    private val streamFetch: (suspend (String, (received: Long, total: Long) -> Unit) -> ByteArray)? = null
) {

    private val placeLock = Mutex()
    private val placementLock = Any()

    private val uninstallRevisions = mutableMapOf<String, Long>()

    fun isInstalled(id: String): Boolean = store.find(id) != null

    fun installedVersion(id: String): String? = store.find(id)?.manifest?.version

    suspend fun install(
        entry: PluginCatalogEntry,
        onProgress: ((received: Long, total: Long) -> Unit)? = null
    ): PluginInstallResult = place(entry, onProgress) { manifest ->
        if (installedVersion(entry.id) != manifest.version) null else "同版本不覆盖"
    }

    suspend fun update(
        entry: PluginCatalogEntry,
        onProgress: ((received: Long, total: Long) -> Unit)? = null
    ): PluginInstallResult {
        if (store.find(entry.id) == null) return PluginInstallResult.Failure("插件未安装")
        return place(entry, onProgress) { manifest ->
            val installed = store.find(entry.id) ?: return@place "插件未安装"
            val target = PluginManifests.versionCode(manifest.version)
            val current = PluginManifests.versionCode(installed.manifest.version)
            when {
                target > current -> null
                target == current -> "同版本不覆盖"
                else -> "当前版本已更新"
            }
        }
    }

    fun uninstall(id: String): Boolean = synchronized(placementLock) {
        uninstallRevisions[id] = (uninstallRevisions[id] ?: 0L) + 1
        if (!isInstalled(id)) return@synchronized false
        store.remove(id)
        true
    }

    private suspend fun place(
        entry: PluginCatalogEntry,
        onProgress: ((received: Long, total: Long) -> Unit)?,
        accept: (PluginManifest) -> String?
    ): PluginInstallResult = placeLock.withLock {
        val id = try {
            PluginIds.require(entry.id)
        } catch (e: IllegalArgumentException) {
            return@withLock PluginInstallResult.Failure(e.message ?: "插件标识非法")
        }
        val revision = uninstallRevisions[id] ?: 0L
        val temp = File(store.rootDir, "tmp/$id-${now()}")
        val backup = File(store.rootDir, "tmp/$id-backup-${now()}")
        return@withLock try {
            val manifest = prepare(entry, temp, onProgress)
            val rejection = accept(manifest)
            if (rejection != null) {
                PluginInstallResult.Failure(rejection)
            } else {
                PluginPackages.markDexReadOnly(temp)
                if (!PluginPackages.loadable(manifest, temp)) {
                    PluginInstallResult.Failure("插件包无法加载")
                } else {
                    val target = store.packageDir(id)
                    if (target.exists()) {
                        File(store.rootDir, "tmp")
                            .listFiles { file -> file.name.startsWith("$id-backup-") }
                            ?.forEach { it.deleteRecursively() }
                    }
                    val previous = target.exists() && target.renameTo(backup)
                    if (target.exists()) throw IllegalStateException("插件目录被占用")
                    var placed = false
                    try {
                        target.parentFile?.mkdirs()
                        if (!temp.renameTo(target)) throw IllegalStateException("插件安装失败")
                        placed = true
                        val record = InstalledPluginRecord(manifest, now())
                        synchronized(placementLock) {
                            if ((uninstallRevisions[id] ?: 0L) != revision) {
                                throw IllegalStateException("插件已被卸载")
                            }
                            store.save(record)
                        }
                        backup.deleteRecursively()
                        PluginInstallResult.Success(record)
                    } catch (e: Exception) {
                        if (placed) target.deleteRecursively()
                        synchronized(placementLock) {
                            if (previous && (uninstallRevisions[id] ?: 0L) == revision) backup.renameTo(target)
                        }
                        backup.deleteRecursively()
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PluginInstallResult.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun prepare(
        entry: PluginCatalogEntry,
        temp: File,
        onProgress: ((received: Long, total: Long) -> Unit)?
    ): PluginManifest {
        val expected = entry.sha256.trim()
        if (expected.length != 64) throw IllegalArgumentException("目录条目缺少校验值")
        val bytes = streamFetch?.invoke(entry.packageUrl) { received, total -> onProgress?.invoke(received, total) }
            ?: fetch(entry.packageUrl)
        if (!PluginPackages.sha256(bytes).equals(expected, ignoreCase = true)) {
            throw IllegalArgumentException("插件包校验失败")
        }
        temp.deleteRecursively()
        val manifest = PluginPackages.unpack(bytes, temp, limits)
        if (manifest.id != entry.id) throw IllegalArgumentException("插件包与目录条目不一致")
        if (manifest.version != entry.version) throw IllegalArgumentException("插件包与目录条目不一致")
        if (manifest.minHostApi > PluginManifests.HOST_API) throw IllegalArgumentException("插件需要更高版本的宿主")
        return manifest
    }
}
