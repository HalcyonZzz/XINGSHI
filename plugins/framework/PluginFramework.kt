package com.xingshi.ai.plugins

import android.app.Application
import android.net.Uri
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.ApiConfigRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        // 服务/Receiver 的重复初始化只补应用上下文，不能用默认 null 抹掉 Application 注入的网关
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

    /** 升级清理历史随包副本：只删除 files/plugins/catalog/，已安装插件、记录与配置不受影响。 */
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

    private suspend fun fetch(url: String): ByteArray = fetch(url, null)

    private suspend fun fetch(url: String, onProgress: ((received: Long, total: Long) -> Unit)?): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("插件下载失败: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("插件下载失败")
                val expected = body.contentLength().takeIf { it > 0L } ?: 0L
                // 边下边限量：不把超限的插件包整体读进内存
                val source = body.source()
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
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

    // 与 PluginLimits 默认的单包上限一致：下载阶段就限量，落包阶段还会再核一次
    private const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
}
