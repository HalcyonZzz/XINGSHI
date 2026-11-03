package com.xingshi.ai.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

 
class StickerManager(private val context: Context) {

    private val stickersDir = File(context.applicationContext.filesDir, "stickers")
    private val importedDir = File(stickersDir, "imported")
    private val stickerRulesFile = File(importedDir, "custom_stickers.json")

    
    private var stickerRules: Map<String, StickerRule> = emptyMap()

    init {
        stickersDir.mkdirs()
        importedDir.mkdirs()
        loadRules()
    }

     
    data class StickerRule(
        val description: String,
        val fileName: String,
        val path: String
    )

     
    private fun loadRules() {
        try {
            Log.i("StickerManager", "Loading rules from: ${stickerRulesFile.absolutePath}")
            Log.i("StickerManager", "File exists: ${stickerRulesFile.exists()}")
            Log.i("StickerManager", "Imported dir: ${importedDir.absolutePath}")
            Log.i("StickerManager", "Imported dir exists: ${importedDir.exists()}")

            
            val files = importedDir.listFiles()
            if (files != null) {
                Log.i("StickerManager", "Files in imported dir (${files.size}):")
                files.forEach { Log.i("StickerManager", "  - ${it.name} (${it.length()} bytes)") }
            } else {
                Log.i("StickerManager", "No files in imported dir")
            }

            if (!stickerRulesFile.exists()) {
                Log.w("StickerManager", "Rules file not found!")
                stickerRules = emptyMap()
                return
            }
            val json = stickerRulesFile.readText()
            Log.i("StickerManager", "Rules file content length: ${json.length}")
            val array = JSONArray(json)
            Log.i("StickerManager", "JSON array length: ${array.length()}")
            val rules = mutableMapOf<String, StickerRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val description = obj.optString("description", "")
                val fileName = obj.optString("fileName", "")
                Log.i("StickerManager", "Rule $i: description='$description', fileName='$fileName'")
                if (description.isNotEmpty() && fileName.isNotEmpty()) {
                    val file = File(importedDir, fileName)
                    Log.i("StickerManager", "Checking file: ${file.absolutePath}, exists=${file.exists()}")
                    if (file.exists()) {
                        rules[description] = StickerRule(
                            description = description,
                            fileName = fileName,
                            path = file.absolutePath
                        )
                        Log.i("StickerManager", "Added rule: $description")
                    } else {
                        Log.w("StickerManager", "File not found: $fileName")
                    }
                }
            }
            stickerRules = rules
            Log.i("StickerManager", "Loaded ${rules.size} sticker rules")
        } catch (e: Exception) {
            Log.e("StickerManager", "Failed to load sticker rules", e)
            stickerRules = emptyMap()
        }
    }

     
    suspend fun getAllStickers(): List<StickerInfo> = withContext(Dispatchers.IO) {
        val stickers = mutableListOf<StickerInfo>()

        
        try {
            val assetStickers = context.assets.list("stickers") ?: emptyArray()
            assetStickers.forEach { filename ->
                if (isImageFile(filename)) {
                    stickers.add(StickerInfo(
                        name = filename.substringBeforeLast("."),
                        path = "asset://stickers/$filename",
                        category = "default",
                        isBuiltIn = true
                    ))
                }
            }
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "No assets/stickers found")
        }

        
        val rules = stickerRules
        importedDir.listFiles()?.forEach { file ->
            if (isImageFile(file.name)) {
                
                val rule = rules.values.find { it.fileName == file.name }
                stickers.add(StickerInfo(
                    name = rule?.description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = rule?.description,
                    fileName = file.name
                ))
            }
        }

        stickers
    }

     
    fun findStickerByDescriptionExact(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        
        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        return null
    }

     
    fun findStickerByDescription(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        
        val directFile = File(importedDir, keyword)
        if (directFile.exists()) {
            val rule = rules.values.find { it.fileName == keyword }
            return StickerInfo(
                name = rule?.description ?: keyword.substringBeforeLast("."),
                path = directFile.absolutePath,
                category = "imported",
                isBuiltIn = false,
                description = rule?.description,
                fileName = keyword
            )
        }

        
        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        
        rules.entries.find { it.key.contains(keyword) || keyword.contains(it.key) }?.let { entry ->
            return StickerInfo(
                name = entry.value.description,
                path = entry.value.path,
                category = "imported",
                isBuiltIn = false,
                description = entry.value.description,
                fileName = entry.value.fileName
            )
        }

        
        importedDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension == keyword || file.name == keyword) {
                val rule = rules.values.find { it.fileName == file.name }
                return StickerInfo(
                    name = rule?.description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = rule?.description,
                    fileName = file.name
                )
            }
        }

        return null
    }

     
    fun getAllRules(): List<StickerRule> {
        loadRules()
        return stickerRules.values.toList()
    }

     
    suspend fun importStickerZip(zipPath: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        var hasRules = false
        try {
            val zipFile = File(zipPath)
            Log.i("StickerManager", "Importing zip: $zipPath")
            Log.i("StickerManager", "Zip file exists: ${zipFile.exists()}")
            Log.i("StickerManager", "Zip file size: ${zipFile.length()} bytes")

            if (!zipFile.exists()) {
                Log.e("StickerManager", "Zip file not found: $zipPath")
                return@withContext 0
            }

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.substringAfterLast("/")
                    Log.i("StickerManager", "ZIP entry: $entryName (isDir=${entry.isDirectory})")
                    when {
                        
                        entryName == "custom_stickers.json" -> {
                            val destFile = File(importedDir, entryName)
                            Log.i("StickerManager", "Extracting rules to: ${destFile.absolutePath}")
                            destFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                            hasRules = true
                            Log.i("StickerManager", "Rules file extracted successfully, size=${destFile.length()}")
                        }
                        
                        !entry.isDirectory && isImageFile(entryName) -> {
                            val destFile = File(importedDir, entryName)
                            destFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                            count++
                            Log.i("StickerManager", "Extracted image: $entryName")
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            
            if (hasRules) {
                Log.i("StickerManager", "Has rules, reloading...")
                loadRules()
            } else {
                Log.w("StickerManager", "No rules file found in zip!")
            }

            Log.i("StickerManager", "Import complete: $count stickers, rules=$hasRules")
            count
        } catch (e: Exception) {
            Log.e("StickerManager", "Failed to import zip", e)
            count
        }
    }

     
    suspend fun deleteImportedSticker(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(importedDir, fileName)
            if (file.exists()) {
                file.delete()
                Log.i("StickerManager", "Deleted sticker: $fileName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("StickerManager", "Failed to delete sticker", e)
            false
        }
    }

     
    suspend fun deleteAllImportedStickers(): Boolean = withContext(Dispatchers.IO) {
        try {
            var deleted = 0
            importedDir.listFiles()?.forEach { file ->
                if (file.delete()) deleted++
            }
            stickerRules = emptyMap()
            Log.i("StickerManager", "Deleted all $deleted imported stickers")
            true
        } catch (e: Exception) {
            Log.e("StickerManager", "Failed to delete all stickers", e)
            false
        }
    }

     
    suspend fun loadStickerBitmap(stickerPath: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            when {
                stickerPath.startsWith("asset://") -> {
                    val assetPath = stickerPath.removePrefix("asset://")
                    context.assets.open(assetPath).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                else -> {
                    BitmapFactory.decodeFile(stickerPath)
                }
            }
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load bitmap", e)
            null
        }
    }

     
    fun getImportedDir(): String = importedDir.absolutePath

     
    fun pickStickerForMood(text: String): StickerInfo? {
        if (text.isBlank()) return null

        val lowerText = text.lowercase()
        val rules = stickerRules
        val allAvailableStickers = mutableListOf<StickerInfo>()

        rules.values.forEach { rule ->
            allAvailableStickers.add(StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            ))
        }

        importedDir.listFiles()?.filter { isImageFile(it.name) }?.forEach { file ->
            val existingRule = rules.values.find { it.fileName == file.name }
            if (existingRule == null) {
                allAvailableStickers.add(StickerInfo(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    fileName = file.name
                ))
            }
        }

        try {
            context.assets.list("stickers")?.filter { isImageFile(it) }?.forEach { filename ->
                allAvailableStickers.add(StickerInfo(
                    name = filename.substringBeforeLast("."),
                    path = "asset://stickers/$filename",
                    category = "default",
                    isBuiltIn = true
                ))
            }
        } catch (_: Exception) {}

        if (allAvailableStickers.isEmpty()) return null

        val moodKeywords = listOf(
            "开心" to listOf("开心", "高兴", "快乐", "笑", "哈哈", "嘻嘻", "嘿嘿", "好耶", "太棒了", "棒", "赞", "好", "nice"),
            "委屈" to listOf("委屈", "难过", "伤心", "哭", "呜呜", "呜", "眼泪", "可怜", "心疼", "好惨", "不想", "难过"),
            "生气" to listOf("生气", "气", "哼", "烦", "讨厌", "滚", "不理你", "不想理", "气鼓鼓", "怒"),
            "害羞" to listOf("害羞", "脸红", "不好意思", "羞", "呀", "哎呀", "啊这", "那个", "嗯..."),
            "惊讶" to listOf("惊讶", "哇", "天哪", "什么", "真的吗", "不会吧", "居然", "竟然", "啊？"),
            "撒娇" to listOf("撒娇", "嘛", "啦", "呢", "呀", "人家", "求你", "好不好", "嘛~", "拜托", "亲"),
            "可爱" to listOf("可爱", "萌", "乖", "乖巧", "听话", "小", "宝贝", "宝宝", "亲亲", "抱抱", "喜欢"),
            "无奈" to listOf("无奈", "算了", "服了", "无语", "行吧", "好吧", "随你", "随便", "额"),
            "思考" to listOf("思考", "想想", "嗯", "让我想", "不知道", "好像", "也许", "可能", "这个"),
            "困倦" to listOf("困", "累", "睡", "瞌睡", "哈欠", "晚安", "早安", " tired ")
        )

        for ((mood, keywords) in moodKeywords) {
            for (keyword in keywords) {
                if (lowerText.contains(keyword)) {
                    val matched = allAvailableStickers.find { 
                        (it.description?.contains(mood) == true) || 
                        (it.name.contains(mood)) ||
                        (it.fileName?.contains(mood) == true)
                    } ?: allAvailableStickers.find {
                        (it.description?.contains(keyword) == true) || 
                        (it.name.contains(keyword))
                    }
                    if (matched != null) {
                        Log.d("StickerManager", "Mood match: keyword='$keyword' → sticker='${matched.name}'")
                        return matched
                    }
                }
            }
        }

        val picked = allAvailableStickers.random()
        Log.d("StickerManager", "Random pick from ${allAvailableStickers.size} stickers: '${picked.name}'")
        return picked
    }

    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast(".", "").lowercase()
        return ext in listOf("png", "jpg", "jpeg", "gif", "webp")
    }

    companion object {
        @Volatile
        private var instance: StickerManager? = null

        fun getInstance(context: Context): StickerManager {
            return instance ?: synchronized(this) {
                instance ?: StickerManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

 
data class StickerInfo(
    val name: String,
    val path: String,
    val category: String = "default",
    val isBuiltIn: Boolean = true,
    val description: String? = null,
    val fileName: String? = null
)