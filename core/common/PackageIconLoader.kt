package com.xingshi.ai.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PackageIconLoader(
    private val maxEntries: Int = 24,
    private val maxBytes: Int = 4 * 1024 * 1024
) {

    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)

    private var cachedBytes = 0

    @Synchronized
    fun get(key: String): ByteArray? = cache[key]

    suspend fun load(key: String, fetch: suspend () -> ByteArray?): ByteArray? {
        get(key)?.let { return it }
        val bytes = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (!PackageIcons.valid(bytes)) return null
        currentCoroutineContext().ensureActive()
        put(key, bytes)
        return bytes
    }

    @Synchronized
    private fun put(key: String, bytes: ByteArray) {
        if (bytes.size > maxBytes) return
        cache.remove(key)?.let { cachedBytes -= it.size }
        cache[key] = bytes
        cachedBytes += bytes.size
        while (cachedBytes > maxBytes || cache.size > maxEntries) {
            val eldest = cache.entries.firstOrNull() ?: break
            cache.remove(eldest.key)
            cachedBytes -= eldest.value.size
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        cachedBytes = 0
    }
}
