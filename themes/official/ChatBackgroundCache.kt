package com.xingshi.ai.uicommon.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object ChatBackgroundCache {

    
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Bitmap>()

     
    private val missing = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val maxCacheSize = 3

    fun getCachedBitmap(key: String): Bitmap? = cache[key]

    fun loadBitmap(context: Context, key: String): Bitmap? {
        cache[key]?.let { return it }
        if (key in missing) return null

        val file = getCustomBackgroundFile(context, key)
        if (file == null || !file.exists()) {
            missing.add(key)
            return null
        }

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val reqWidth = 1080
            val reqHeight = 1920
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bitmap == null) {
                missing.add(key)
                return null
            }

            synchronized(cache) {
                cache[key]?.let { return it }
                while (cache.size >= maxCacheSize) {
                    val victim = cache.keys.firstOrNull { it != key } ?: break
                    cache.remove(victim)
                }
                missing.remove(key)
                cache[key] = bitmap
            }
            bitmap
        } catch (_: Exception) {
            missing.add(key)
            null
        }
    }

    fun preload(context: Context, key: String) {
        if (!isCustomBackground(key)) return
        
        preloadScope.launch { loadBitmap(context, key) }
    }

    fun clear() {
        
        cache.clear()
        missing.clear()
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

@Composable
fun rememberBackgroundBitmap(key: String): BitmapPainter? {
    val context = LocalContext.current
    
    var bitmap by remember(key) { mutableStateOf(ChatBackgroundCache.getCachedBitmap(key)) }

    
    LaunchedEffect(key) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                ChatBackgroundCache.loadBitmap(context, key)
            }
            bitmap = loaded
        }
    }

    return remember(bitmap) {
        bitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
}
