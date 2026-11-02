package com.xingshi.ai

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import com.xingshi.ai.common.ContentFilter
import com.xingshi.ai.common.RomUtils
import com.xingshi.ai.common.SaltStore
import com.xingshi.ai.common.SecureLog
import com.xingshi.ai.common.embedding.VectorLibrary
import com.xingshi.ai.common.safety.ContentSafetyVerifier
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.SecurityDataSeeder
import com.xingshi.ai.database.repository.ChatRepository
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.database.repository.UserRepository
import com.xingshi.ai.domain.CompanionProvider
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.UserProfileProvider

import com.xingshi.ai.feature.notification.NotificationHelper
import com.xingshi.ai.push.PushManager
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.theme.framework.SharedThemePreferences
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.themes.official.OfficialTheme
import com.xingshi.ai.uicommon.component.ChatBackgroundCache
import com.xingshi.ai.uicommon.component.getChatBackgroundKey
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class XingShiApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        super.getSharedPreferences(com.xingshi.ai.common.AccountStorage.preferenceName(name), mode)

    override fun getDatabasePath(name: String): java.io.File =
        java.io.File(super.getDatabasePath(name).parentFile, "accounts/${com.xingshi.ai.common.AccountStorage.partition}/$name").also { it.parentFile?.mkdirs() }

    override fun getFilesDir(): java.io.File = java.io.File(super.getFilesDir(), "accounts/${com.xingshi.ai.common.AccountStorage.partition}").also { it.mkdirs() }
    override fun getCacheDir(): java.io.File = java.io.File(super.getCacheDir(), "accounts/${com.xingshi.ai.common.AccountStorage.partition}").also { it.mkdirs() }

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.CriticalInit)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .build()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

        override fun onCreate() {
        // Global DNS resolution timeout (5s) — prevents DNS hang blocking all OkHttp calls
        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8")
        System.setProperty("sun.net.spi.nameservice.domain", ".")
        super.onCreate()
        instance = this
        MobileBackendRuntime.initialize(this)
        ThemeRuntime.registerOfficial(this, OfficialTheme)
        initBusiness(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                com.xingshi.ai.feature.chat.VoiceCallRecovery.recoverIfNeeded(this@XingShiApplication)
                com.xingshi.ai.feature.groupchat.GroupConversationSync.retryPending(this@XingShiApplication)
            }
        }
        registerVerificationReceiver()
        com.xingshi.ai.feature.groupchat.GroupReplyReceiver.start(this)
    }

    private fun registerVerificationReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BuildConfig.DEBUG) return
                when (intent.action) {
                    ACTION_CHAT_FRAGMENT -> {
                        val pluginId = intent.getStringExtra("requestPluginId").orEmpty()
                        val accountId = intent.getStringExtra("requestAccountId").orEmpty()
                        val conversationId = intent.getStringExtra("requestConversationId").orEmpty()
                        val request = if (pluginId.isNotBlank() && accountId.isNotBlank() && conversationId.isNotBlank()) {
                            com.xingshi.ai.plugins.api.ChannelRequestRef(
                                pluginId = pluginId,
                                replyRoute = com.xingshi.ai.plugins.api.ChannelReplyRoute(
                                    accountId = accountId,
                                    externalConversationId = conversationId,
                                    senderId = intent.getStringExtra("requestSenderId").orEmpty(),
                                    platformMessageId = intent.getStringExtra("requestMessageId").orEmpty()
                                )
                            )
                        } else {
                            null
                        }
                        // 正式通话结果必须携带原 call_id/原请求，页面终结链才能交回正确轮次。
                        com.xingshi.ai.feature.chat.ui.viewmodel.ChatVerificationBus.stream(
                            companionId = intent.getLongExtra("companionId", -1L),
                            text = intent.getStringExtra("text").orEmpty(),
                            terminal = intent.getBooleanExtra("terminal", false),
                            callId = intent.getStringExtra("callId"),
                            request = request,
                            inputMessageId = intent.getLongExtra("inputMessageId", 0L)
                        )
                    }
                    ACTION_PLUGIN_IMAGE -> {
                        val companionId = intent.getLongExtra("companionId", -1L)
                        val path = intent.getStringExtra("path").orEmpty()
                        if (companionId > 0 && path.isNotBlank()) {
                            PluginFramework.manager()?.startForSentImage(companionId, path)
                        }
                    }
                    ACTION_PLUGIN_TEXT -> {
                        val companionId = intent.getLongExtra("companionId", -1L)
                        val pluginId = intent.getStringExtra("pluginId").orEmpty()
                        val text = intent.getStringExtra("text").orEmpty()
                        if (companionId > 0 && pluginId.isNotBlank() && text.isNotBlank()) {
                            val manager = PluginFramework.manager()
                            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                            scope.launch {
                                manager?.execute(
                                    pluginId,
                                    com.xingshi.ai.plugins.api.PluginRequest(
                                        companionId = companionId,
                                        text = text
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CHAT_FRAGMENT)
            addAction(ACTION_PLUGIN_IMAGE)
            addAction(ACTION_PLUGIN_TEXT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    override fun onTerminate() {
        bgScope.launch {
            ContentFilter.destroy()
            SaltStore.shutdown()
            // EncryptedDatabaseWrapper.sealDatabase(this@XingShiApplication)
            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        private const val ACTION_CHAT_FRAGMENT = "com.xingshi.ai.debug.CHAT_FRAGMENT"
        private const val ACTION_PLUGIN_IMAGE = "com.xingshi.ai.debug.PLUGIN_IMAGE"
        private const val ACTION_PLUGIN_TEXT = "com.xingshi.ai.debug.PLUGIN_TEXT"

        lateinit var instance: XingShiApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun initBusiness(app: Application) {
            SaltStore.init(app)
            SecureLog.init(com.xingshi.ai.BuildConfig.DEBUG)
            applyStoredLanguage(app)
            registerServiceProviders(app)
            cancelLegacyGenerationWork(app)

            // 注入应用级后台作用域，供跨越 ViewModel 生命周期的任务使用
            com.xingshi.ai.common.ApplicationScopeProvider.init(bgScope)

            // 安全检查初始化有先后依赖：词表 → 种子 → 向量库 → 贝叶斯引导，
            // 同一协程顺序执行，避免 bootstrap 在向量库就绪前把特征缓成 na。
            bgScope.launch {
                runCatching { ContentFilter.initialize(app) }
                runCatching { initSecurityData(app) }
                runCatching { initVectorLibrary(app) }
                runCatching { initSafetyVerifier(app) }
            }
            bgScope.launch { preloadBackground(app) }
            bgScope.launch { autoBackupDatabase(app) }
            PluginFramework.init(
                app,
                com.xingshi.ai.feature.chat.ui.viewmodel.ChannelChatGateway(app)
            )
            // 页面未创建时插件结果仍有应用级发送口：视觉结论不依赖聊天页存在
            PluginFramework.manager()?.bindApplicationEntry(
                com.xingshi.ai.feature.chat.ui.viewmodel.ApplicationChatEntry(app)
            )
            // 入站轮次的唯一消费者固定在应用作用域：未打开聊天页也能消费并回传原联系人
            com.xingshi.ai.feature.chat.ui.viewmodel.InboundTurnDispatcher.init(app)
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {
            // 备份涉及文件 IO，已从 AppDatabase.buildDatabase 主路径移除，
            // 在此异步执行，不阻塞首屏渲染。同时顺带清理过期备份。
            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
            runCatching { AppDatabase.clearOldBackups(app.applicationContext) }
        }

        private fun initVectorLibrary(app: Application) {
            runCatching {
                app.assets.open("safety/violation_vectors.bin").use { it.readBytes() }
                    .let { VectorLibrary.Loader().load(it) }
                    ?.let { ContentFilter.setVectorLibrary(it) }
            }
        }

        private suspend fun initSafetyVerifier(app: Application) {
            runCatching {
                ContentSafetyVerifier.init(app)
                val keywords = SecurityDataSeeder.getEnabledKeywords(app)
                if (keywords.isNotEmpty()) {
                    ContentSafetyVerifier.bootstrap(keywords)
                }
            }
        }

        internal fun registerServiceProviders(app: Application) {
            // ── Repository 单例注册 ──
            // 统一 Repository 获取方式，供 QQ Bot 等跨模块消费者通过 ServiceRegistry 获取。
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(AppDatabase.getDatabase(app).companionDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(AppDatabase.getDatabase(app).chatMessageDao())
            }
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            // ── 跨 feature 服务接口注册 ──
            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {
                com.xingshi.ai.feature.profile.UserProfileProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.xingshi.ai.feature.companion.CompanionProviderImpl(app)
            }

            MobileBackendRuntime.registerServices()

            ServiceRegistry.markInitialized()
        }

        private val legacyGenerationWorkerClasses = listOf(
            "com.xingshi.ai.feature.notification.CompanionMessageWorker",
            "com.xingshi.ai.feature.notification.AiReplyWorker",
        )

        private fun cancelLegacyGenerationWork(app: Application) {
            val workManager = androidx.work.WorkManager.getInstance(app)
            legacyGenerationWorkerClasses.forEach { workerClassName ->
                workManager.cancelAllWorkByTag(workerClassName)
            }
        }

        private fun applyStoredLanguage(app: Application) {
            // 语言映射唯一实现在主题框架内，宿主不再维护另一套语言表
            Locale.setDefault(ThemeRuntime.localeOf(SharedThemePreferences(app).language))
        }
    }
}
