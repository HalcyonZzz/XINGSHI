package com.xingshi.ai.plugins

import android.app.Application
import android.net.Uri
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.ApiConfigRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object PluginFramework {

    private val client = OkHttpClient()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resetAccount() {
        managerRef?.closeAccount()
        PluginChannels.stopAll()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        managerRef = null
        storeRef = null
    }

    @Volatile
    private var storeRef: PluginStore? = null

    @Volatile
    private var managerRef: PluginManager? = null

    fun init(app: Application, channelGateway: com.xingshi.ai.plugins.api.HostChannelGateway? = null) {
        
        if (channelGateway != null) PluginChannels.bind(app, channelGateway) else PluginChannels.bindApp(app)
        if (managerRef != null) return
        synchronized(this) {
            if (managerRef != null) return
            val root = File(app.filesDir, "plugins")
            clearLegacyBundledCatalog(root)
            val store = PluginStore(
                root,
                openSource = { path ->
                    if (path.startsWith("content://")) {
                        app.contentResolver.openInputStream(Uri.parse(path))
                            ?: throw IllegalStateException("附件无法读取：$path")
                    } else {
                        File(path).inputStream()
                    }
                }
            )
            val catalog = PluginCatalog(
                RemotePluginCatalogSource(
                    DownloadSources.PLUGIN_CATALOG_URL,
                    fetch = { url -> fetch(url).decodeToString() },
                    cacheFile = File(root, "catalog-cache.json")
                )
            )
            val resolver = PluginModelResolver(
                mainConfig = { hostModelConfig(app) },
                visionConfig = { hostVisionModelConfig(app) }
            )
            val manager = PluginManager(
                store = store,
                installer = PluginInstaller(
                    store,
                    fetch = { url -> fetch(url) },
                    streamFetch = { url, onProgress -> fetch(url, onProgress) }
                ),
                loader = PluginLoader(),
                catalog = catalog,
                modelProvider = { _, vision -> resolver.resolve(vision) },
                visionConfigProvider = { hostVisionModelConfig(app) },
                parentClassLoader = { PluginFramework::class.java.classLoader ?: app.classLoader }
            )
            storeRef = store
            managerRef = manager
            scope.launch {
                manager.reload()
                PluginChannels.restore()
            }
        }
    }

     
    private fun clearLegacyBundledCatalog(root: File) {
        val legacy = File(root, "catalog")
        if (!legacy.exists()) return
        runCatching { legacy.deleteRecursively() }
    }

    fun manager(): PluginManager? = managerRef

    fun store(): PluginStore? = storeRef

    private suspend fun hostModelConfig(app: Application): HostModelConfig? = withContext(Dispatchers.IO) {
        val repository = ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao())
        val config = repository.getActiveEnabledConfig() ?: return@withContext null
        HostModelConfig(
            provider = config.provider.name,
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = config.apiKey,
            formatHint = config.formatHint
        )
    }

    private suspend fun hostVisionModelConfig(app: Application): HostModelConfig? = withContext(Dispatchers.IO) {
        val repository = ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao())
        val config = repository.getVisionConfig() ?: return@withContext null
        HostModelConfig(
            provider = config.provider.name,
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = config.apiKey,
            formatHint = config.formatHint
        )
    }

    internal suspend fun fetch(url: String): ByteArray = fetch(url, null)

    internal suspend fun fetch(url: String, onProgress: ((received: Long, total: Long) -> Unit)?): ByteArray =
        withContext(Dispatchers.IO) {
            val call = client.newCall(Request.Builder().url(url).build())
            val cancelled = AtomicBoolean(false)
            try {
                abortsOnCancel(call, cancelled) {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("插件下载失败: HTTP ${response.code}")
                        val body = response.body ?: throw IllegalStateException("插件下载失败")
                        val expected = body.contentLength().takeIf { it > 0L } ?: 0L
                        
                        val source = body.source()
                        val out = ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(chunk)
                            if (read == -1) break
                            total += read
                            if (total > MAX_PACKAGE_BYTES) throw IllegalArgumentException("插件包过大")
                            out.write(chunk, 0, read)
                            onProgress?.invoke(total, expected)
                        }
                        out.toByteArray()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelled.get()) throw CancellationException()
                throw e
            }
        }

    private suspend fun <T> abortsOnCancel(
        call: okhttp3.Call,
        cancelled: AtomicBoolean,
        block: suspend () -> T
    ): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) {
                    cancelled.set(true)
                    runCatching { call.cancel() }
                }
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            guard.cancel()
        }
    }

    
    private const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
}
