package com.xingshi.ai.feature.qqbot.data

import android.content.Context
import android.util.Log
import com.xingshi.ai.feature.qqbot.data.model.QQBotAccount
import com.xingshi.ai.feature.qqbot.data.model.QQGatewayPayload
import com.xingshi.ai.feature.qqbot.data.model.QQInboundEvent
import com.xingshi.ai.feature.qqbot.data.model.QQMessageEvent
import com.xingshi.ai.feature.qqbot.data.model.QQMessageType
import com.xingshi.ai.feature.qqbot.data.model.SendMessageResponse
import com.xingshi.ai.feature.qqbot.data.model.SendTextRequest
import com.xingshi.ai.feature.qqbot.data.network.QQBotApiClient
import com.xingshi.ai.feature.qqbot.data.network.QQBotRestApi
import com.xingshi.ai.feature.qqbot.data.network.QQBotWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.atomic.AtomicInteger

class QQBotMessageRepository(
    context: Context,
    private val tokenStore: QQBotTokenStore,
    private val apiClient: QQBotApiClient
) {
    private val appContext = context.applicationContext
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingEvents = MutableSharedFlow<QQInboundEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingEvents: Flow<QQInboundEvent> = _incomingEvents.asSharedFlow()

    val accountFlow = tokenStore.accountFlow
    val isLoggedInFlow = accountFlow.map { it != null }
    suspend fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    
    private val _connectionState = MutableStateFlow(QQBotWebSocketClient.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<QQBotWebSocketClient.ConnectionState> = _connectionState.asStateFlow()

    private var webSocketClient: QQBotWebSocketClient? = null
    private val sendMutex = Mutex()
    private val msgSeqCounter = AtomicInteger(1)
    
    private val seenMessageIds = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 1000
        }
    }

    suspend fun saveAccount(appId: String, clientSecret: String, customName: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (appId.isBlank() || clientSecret.isBlank()) {
                throw IllegalArgumentException("AppID 和 ClientSecret 不能为空")
            }
            tokenStore.saveAccount(QQBotAccount(appId, clientSecret, customName))
            
            apiClient.createAuthenticatedRestApi()
            Unit
        }
    }

    suspend fun logout() {
        disconnect()
        tokenStore.clearAccount()
        apiClient.clearApiCache()
        synchronized(seenMessageIds) { seenMessageIds.clear() }
    }

    fun destroy() {
        disconnect()
        processScope.cancel()
    }

    suspend fun connect() {
        if (webSocketClient != null) return
        val client = QQBotWebSocketClient(tokenStore, apiClient, onEvent = { payload ->
            handleGatewayPayload(payload)
        }, onConnectionStateChange = { state ->
            _connectionState.value = state
        })
        webSocketClient = client
        client.connect()
    }

    fun disconnect() {
        webSocketClient?.disconnect()
        webSocketClient?.destroy()
        webSocketClient = null
        _connectionState.value = QQBotWebSocketClient.ConnectionState.DISCONNECTED
    }

    suspend fun sendTextMessage(event: QQInboundEvent, text: String): Result<SendMessageResponse?> = withContext(Dispatchers.IO) {
        runCatching {
            val api = apiClient.createAuthenticatedRestApi()
            val request = SendTextRequest(
                content = text,
                msgType = QQMessageType.TEXT.value,
                msgId = event.raw.id,
                msgSeq = msgSeqCounter.getAndIncrement()
            )
            when (event) {
                is QQInboundEvent.C2CMessage -> {
                    val response = api.sendC2CMessage(event.userOpenid, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.GroupAtMessage -> {
                    val response = api.sendGroupMessage(event.groupOpenid, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.GuildMessage -> {
                    val response = api.sendChannelMessage(event.channelId, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.DirectMessage -> {
                    val response = api.sendDirectMessage(event.guildId, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
            }
        }
    }

    private suspend fun handleGatewayPayload(payload: QQGatewayPayload) {
        if (payload.op != 0) return
        val eventType = payload.t ?: return
        if (eventType !in setOf("C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "GUILD_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE")) {
            return
        }
        val event = payload.d?.let { json.decodeFromJsonElement<QQMessageEvent>(it) } ?: return

        
        synchronized(seenMessageIds) {
            val now = System.currentTimeMillis()
            
            seenMessageIds.entries.removeIf { now - it.value > 5 * 60 * 1000L }
            if (seenMessageIds.put(event.id, now) != null) return
        }

        val inbound = when (eventType) {
            "C2C_MESSAGE_CREATE" -> {
                val userOpenid = event.author?.userOpenid ?: return
                QQInboundEvent.C2CMessage(userOpenid, event)
            }
            "GROUP_AT_MESSAGE_CREATE" -> {
                val groupOpenid = event.groupOpenid ?: return
                val memberOpenid = event.author?.memberOpenid ?: return
                QQInboundEvent.GroupAtMessage(groupOpenid, memberOpenid, event)
            }
            "GUILD_MESSAGE_CREATE" -> {
                val channelId = event.channelId ?: return
                val guildId = event.guildId
                val authorId = event.author?.id ?: return
                QQInboundEvent.GuildMessage(channelId, guildId, authorId, event)
            }
            "DIRECT_MESSAGE_CREATE" -> {
                val guildId = event.guildId ?: return
                val authorId = event.author?.id ?: return
                QQInboundEvent.DirectMessage(guildId, authorId, event)
            }
            else -> return
        }

        _incomingEvents.tryEmit(inbound)
    }

    fun extractText(event: QQInboundEvent): String {
        var text = event.raw.content.orEmpty()
        
        if (event is QQInboundEvent.GroupAtMessage) {
            text = text.replace(Regex("^<@!\\d+>\\s*"), "")
            text = text.replace(Regex("^@\\S+\\s*"), "")
        }
        return text.trim()
    }

     
    suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val client = okhttp3.OkHttpClient()
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        }.getOrNull()
    }

    suspend fun extractImageUrl(event: QQInboundEvent): String? {
        return event.raw.attachments?.firstOrNull { it.contentType?.startsWith("image/") == true }?.url
    }

    fun getReplyKey(event: QQInboundEvent): String {
        return when (event) {
            is QQInboundEvent.C2CMessage -> "c2c:${event.userOpenid}"
            is QQInboundEvent.GroupAtMessage -> "group:${event.groupOpenid}:${event.memberOpenid}"
            is QQInboundEvent.GuildMessage -> "guild:${event.channelId}:${event.authorId}"
            is QQInboundEvent.DirectMessage -> "dm:${event.guildId}:${event.authorId}"
        }
    }

    companion object {
        private const val TAG = "QQBotMsgRepo"
    }
}
