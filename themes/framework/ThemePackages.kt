package com.xingshi.ai.theme.framework

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed interface ThemePackageResult {
    data class Success(val manifest: ThemeManifest, val strings: Map<String, Map<String, String>>) : ThemePackageResult
    data class Failure(val message: String) : ThemePackageResult
}

object ThemePackages {

    const val MANIFEST_NAME = "theme.json"
    const val MAX_PACKAGE_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    fun validateManifest(manifest: ThemeManifest): String? = when {
        !ThemeManifests.valid(manifest) -> "主题清单无效"
        !manifest.supportsFramework -> "主题与当前框架版本不兼容（需要 ${manifest.minFramework}-${manifest.maxFramework}，当前 $FRAMEWORK_API）"
        manifest.languages.any { !LANGUAGE_KEY.matches(it) } -> "主题语言标识非法"
        else -> null
    }

    private const val MAX_TEXT_ENTRY_BYTES = 4L * 1024 * 1024
    private const val DEX_ENTRY = "dex/classes.dex"
    private val ENTRY_CLASS = Regex("^[A-Za-z_$][A-Za-z0-9_$.]{1,255}$")

    fun inspect(bytes: ByteArray): ThemePackageResult {
        if (bytes.size > MAX_PACKAGE_BYTES) return ThemePackageResult.Failure("主题包过大")
        val texts = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        var hasClassesDex = false
        var dexMagicOk = false
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) return ThemePackageResult.Failure("主题包条目过多")
                    val name = entry.name
                    if (!safePath(name)) return ThemePackageResult.Failure("主题包路径非法: $name")
                    if (entry.isDirectory) continue
                    val isText = name == MANIFEST_NAME || name.startsWith("strings/")
                    val isDex = name.endsWith(".dex")
                    val sink = if (isText) ByteArrayOutputStream() else null
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > MAX_TOTAL_BYTES || total > MAX_TOTAL_BYTES) {
                            return ThemePackageResult.Failure("主题包解包后过大")
                        }
                        if (sink != null) {
                            if (entryBytes > MAX_TEXT_ENTRY_BYTES) return ThemePackageResult.Failure("主题包文本文件过大: $name")
                            sink.write(chunk, 0, read)
                        } else if (isDex && name == DEX_ENTRY && !dexMagicOk) {
                            if (entryBytes == read.toLong() && read >= 4) {
                                val header = chunk.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                                if (header == "dex\n") dexMagicOk = true
                            }
                        }
                    }
                    if (name == DEX_ENTRY) hasClassesDex = true
                    if (sink != null) texts[name] = sink.toByteArray()
                }
            }
        } catch (e: Exception) {
            return ThemePackageResult.Failure("主题包损坏: ${e.message}")
        }
        val manifestBytes = texts[MANIFEST_NAME] ?: return ThemePackageResult.Failure("缺少 $MANIFEST_NAME")
        val manifest = ThemeManifests.parse(manifestBytes.toString(Charsets.UTF_8))
            ?: return ThemePackageResult.Failure("主题清单无法解析")
        validateManifest(manifest)?.let { return ThemePackageResult.Failure(it) }
        if (manifest.languages.isEmpty()) return ThemePackageResult.Failure("主题未声明语言")
        if (manifest.entry.isNotBlank()) {
            if (!ENTRY_CLASS.matches(manifest.entry)) return ThemePackageResult.Failure("主题入口类名非法")
            if (!hasClassesDex) return ThemePackageResult.Failure("声明代码入口但缺少 $DEX_ENTRY")
            if (!dexMagicOk) return ThemePackageResult.Failure("主题 DEX 文件损坏")
        }
        val strings = LinkedHashMap<String, Map<String, String>>()
        for (language in manifest.languages) {
            val data = texts["strings/$language.json"]
                ?: return ThemePackageResult.Failure("缺少语言文件 strings/$language.json")
            val parsed = parseStrings(data)
                ?: return ThemePackageResult.Failure("语言文件损坏: strings/$language.json")
            strings[language] = parsed
        }
        return ThemePackageResult.Success(manifest, strings)
    }

    sealed interface StagedResult {
        data class Success(val staging: File) : StagedResult
        data class Failure(val message: String) : StagedResult
    }

    fun stageTo(bytes: ByteArray, target: File): StagedResult {
        val inspected = inspect(bytes)
        if (inspected is ThemePackageResult.Failure) return StagedResult.Failure(inspected.message)
        val parent = target.absoluteFile.parentFile ?: return StagedResult.Failure("主题目录不可用")
        val staging = File(parent, "${target.name}.staging-${System.nanoTime()}")
        return try {
            staging.deleteRecursively()
            staging.mkdirs()
            var total = 0L
            ZipInputStream(bytes.inputStream()).use { zip ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (!safePath(entry.name)) throw IllegalArgumentException("路径非法")
                    val out = File(staging, entry.name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { sink ->
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(chunk)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > MAX_TOTAL_BYTES || total > MAX_TOTAL_BYTES) {
                                throw IllegalArgumentException("主题包解包后过大")
                            }
                            sink.write(chunk, 0, read)
                        }
                    }
                }
            }
            StagedResult.Success(staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            StagedResult.Failure("安装失败: ${e.message}")
        }
    }

    fun commitStaging(staging: File, target: File): String? {
        val backup = File(target.absoluteFile.parentFile, "${target.name}.bak-${System.nanoTime()}")
        return try {
            if (target.exists() && !target.renameTo(backup)) return "无法替换旧主题"
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                return "无法写入主题目录"
            }
            backup.deleteRecursively()
            null
        } catch (e: Exception) {
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            "安装失败: ${e.message}"
        }
    }

    fun discardStaging(staging: File) {
        staging.deleteRecursively()
    }

    fun unpackTo(bytes: ByteArray, target: File): ThemePackageResult {
        val inspected = inspect(bytes)
        if (inspected is ThemePackageResult.Failure) return inspected
        val staged = stageTo(bytes, target)
        if (staged is StagedResult.Failure) return ThemePackageResult.Failure(staged.message)
        val staging = (staged as StagedResult.Success).staging
        val error = commitStaging(staging, target)
        if (error != null) {
            discardStaging(staging)
            return ThemePackageResult.Failure(error)
        }
        return inspected
    }

    fun readEntry(bytes: ByteArray, name: String, maxBytes: Long = 2L * 1024 * 1024): ByteArray? {
        if (!safePath(name)) return null
        return try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name != name) {
                        while (zip.read(chunk) >= 0) Unit
                        continue
                    }
                    val sink = ByteArrayOutputStream()
                    var size = 0L
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        size += read
                        if (size > maxBytes) return null
                        sink.write(chunk, 0, read)
                    }
                    return sink.toByteArray()
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun load(directory: File): ThemePackageResult {
        val manifestFile = File(directory, MANIFEST_NAME)
        if (!manifestFile.isFile) return ThemePackageResult.Failure("主题文件缺失")
        val catalogFile = File(directory, "strings")
        if (!catalogFile.isDirectory) return ThemePackageResult.Failure("主题语言文件缺失")
        return try {
            val manifest = ThemeManifests.parse(manifestFile.readText())
                ?: return ThemePackageResult.Failure("主题清单无法解析")
            validateManifest(manifest)?.let { return ThemePackageResult.Failure(it) }
            if (manifest.entry.isNotBlank() && !File(directory, DEX_ENTRY).isFile) {
                return ThemePackageResult.Failure("声明代码入口但缺少 $DEX_ENTRY")
            }
            val strings = LinkedHashMap<String, Map<String, String>>()
            for (language in manifest.languages) {
                val f = File(catalogFile, "$language.json")
                if (!f.isFile) return ThemePackageResult.Failure("缺少语言文件 $language.json")
                strings[language] = parseStrings(f.readBytes())
                    ?: return ThemePackageResult.Failure("语言文件损坏 $language.json")
            }
            ThemePackageResult.Success(manifest, strings)
        } catch (e: Exception) {
            ThemePackageResult.Failure("主题加载失败: ${e.message}")
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun safePath(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        if (name.contains(':')) return false
        return true
    }

    internal fun parseStrings(bytes: ByteArray): Map<String, String>? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        val map = obj as? kotlinx.serialization.json.JsonObject ?: return null
        map.mapNotNull { (k, v) ->
            val text = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            if (text.isBlank()) null else k to text
        }.toMap()
    } catch (e: Exception) {
        null
    }

    private val LANGUAGE_KEY = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*$")
}
