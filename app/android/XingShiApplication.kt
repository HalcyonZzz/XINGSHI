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

            com.xingshi.ai.common.ApplicationScopeProvider.init(bgScope)

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
            PluginFramework.manager()?.bindApplicationEntry(
                com.xingshi.ai.feature.chat.ui.viewmodel.ApplicationChatEntry(app)
            )
            com.xingshi.ai.feature.chat.ui.viewmodel.InboundTurnDispatcher.init(app)
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {
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
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(AppDatabase.getDatabase(app).companionDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(AppDatabase.getDatabase(app).chatMessageDao())
            }
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {
                com.xingshi.ai.feature.profile.UserProfileProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.xingshi.ai.feature.companion.CompanionProviderImpl(app)
            }

            MobileBackendRuntime.registerServices()

            CircleMediaPreparationRegistration.register(app)

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
            Locale.setDefault(ThemeRuntime.localeOf(SharedThemePreferences(app).language))
        }
    }
}
