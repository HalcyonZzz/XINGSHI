package com.xingshi.ai.feature.chat.data

import android.content.Context
import android.util.Log
import com.xingshi.ai.common.ContentFilter
import com.xingshi.ai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KeywordBridge {

    private const val TAG = "KeywordBridge"

    /**
     * 上次注入的词表签名（条数 + 内容 hash）。
     *
     * 词表会在运行期被 SecurityDataSeeder 补种，因此不能只在首次注入后永久跳过：
     * 内容签名变化时重新注入，未变化则跳过，避免重复编译全部正则。
     */
    @Volatile
    private var injectedSignature: String? = null

    suspend fun initialize(context: Context) {
        try {
            val allKeywords = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                db.keywordDao().getAllEnabled()
            }

            if (allKeywords.isEmpty()) {
                Log.w(TAG, "数据库无关键词数据，使用内置默认值")
                return
            }

            val signature = "${allKeywords.size}:${allKeywords.hashCode()}"
            if (signature == injectedSignature) return

            val grouped = allKeywords.groupBy { it.level }.mapValues { (_, entities) ->
                entities.map { entity ->
                    ContentFilter.KeywordData(
                        value = entity.pattern ?: entity.keyword,
                        isPattern = entity.pattern != null
                    )
                }
            }

            ContentFilter.injectKeywords(grouped)
            injectedSignature = signature
            Log.i(TAG, "从加密数据库加载 ${allKeywords.size} 条关键词成功")
        } catch (e: Exception) {
            Log.e(TAG, "从DB加载关键词失败，使用内置默认值", e)
        }
    }

    fun reset() {
        ContentFilter.clearInjectedKeywords()
        injectedSignature = null
        Log.i(TAG, "已重置，回退到内置默认值")
    }

    fun isInitialized(): Boolean = injectedSignature != null
}
