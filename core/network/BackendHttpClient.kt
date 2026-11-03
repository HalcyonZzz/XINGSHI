package com.xingshi.ai.network

import com.xingshi.ai.domain.BackendSession
import com.xingshi.ai.domain.BackendSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BackendRequest(val origin: String, val method: String, val path: String, val body: String?, val headers: Map<String, String>)
data class BackendResponse(val status: Int, val body: String)
fun interface BackendTransport { suspend fun execute(request: BackendRequest): BackendResponse }
class BackendHttpException(
    val status: Int,
    val code: String = "",
    val serverMessage: String = "",
    val traceId: String = "",
    val details: JsonObject = buildJsonObject {}
) : IllegalStateException(when (status) {
    401 -> "登录已失效，请重新登录"
    403 -> "没有操作权限"
    409 -> "数据已改变，请刷新后重试"
    404 -> "资源不存在或不可读取"
    400, 413, 422 -> "请求内容不符合要求，请检查后重试"
    else -> "请求失败（$status）"
}) {
     
    val definitiveRejection: Boolean get() = status in setOf(400, 403, 404, 409, 413, 422)
}
class StaleBackendSession : IllegalStateException("账号连接已切换，请在当前账号重试")

class OkHttpBackendTransport : BackendTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override suspend fun execute(request: BackendRequest): BackendResponse = withContext(Dispatchers.IO) {
        val body = request.body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(request.origin + request.path)
            .method(request.method, body ?: if (request.method in setOf("POST", "PUT", "PATCH")) "{}".toRequestBody("application/json".toMediaType()) else null)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { BackendResponse(it.code, it.body?.string().orEmpty()) }
    }
}

 
class BackendHttpClient(
    origin: String,
    private val sessions: BackendSessionStore,
    private val transport: BackendTransport = OkHttpBackendTransport(),
    allowDebugHttp: Boolean = false
) {
    val origin = origin.trim().trimEnd('/').also {
        val parsed = runCatching { URI(it) }.getOrNull()
        require(parsed?.host != null && parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null && parsed.path.orEmpty().isEmpty()) { "请配置有效的服务地址" }
        require(parsed.scheme == "https" || (allowDebugHttp && parsed.scheme == "http")) { "正式环境必须使用 HTTPS" }
    }
    private val refreshLock = Mutex()
    private fun requestIdentity(): String = "android-${UUID.randomUUID()}"

    suspend fun anonymous(method: String, path: String, body: JsonObject, headers: Map<String, String> = emptyMap()): JsonObject =
        decode(transport.execute(BackendRequest(origin, method, path, body.toString(), headers + ("x-request-id" to requestIdentity()))))

    suspend fun request(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): JsonObject {
        val original = sessions.current()?.takeIf { it.environment == origin && it.accessToken.isNotBlank() } ?: throw BackendHttpException(401)
        val requestId = requestIdentity()
        suspend fun send(session: BackendSession) = transport.execute(BackendRequest(origin, method, path, body, headers + mapOf("Authorization" to "Bearer ${session.accessToken}", "x-request-id" to requestId)))
        var response = send(original)
        ensureCurrent(original)
        if (response.status == 401) {
            val renewed = refreshLock.withLock {
                ensureCurrent(original)
                val current = sessions.current() ?: throw StaleBackendSession()
                if (current.accessToken != original.accessToken) current else {
                    try {
                        val result = anonymous("POST", "/api/auth/refresh", buildJsonObject { put("refresh_token", original.refreshToken) })
                        val next = original.copy(accessToken = result.text("access_token"), refreshToken = result.text("refresh_token"),
                            sessionId = result.text("session_id"), accessExpiresAt = result.text("access_token_expires_at"), sessionExpiresAt = result.text("session_expires_at"))
                        if (!sessions.replace(original.generation, next)) throw StaleBackendSession()
                        next
                    } catch (error: Exception) {
                        if (error is BackendHttpException && error.status in setOf(400, 401, 403)) sessions.clear(original.generation)
                        throw error
                    }
                }
            }
            response = send(renewed)
            ensureCurrent(original)
        }
        return decode(response)
    }

    suspend fun json(method: String, path: String, body: JsonObject? = null, headers: Map<String, String> = emptyMap()) = request(method, path, body?.toString(), headers)
    private fun ensureCurrent(original: BackendSession) {
        val current = sessions.current()
        if (current?.generation != original.generation || current.userId != original.userId || current.environment != origin) throw StaleBackendSession()
    }
    private fun decode(response: BackendResponse): JsonObject {
        if (response.status !in 200..299) {
            val payload = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrElse { buildJsonObject {} }
            throw BackendHttpException(response.status, payload.text("code"), payload.text("message"), payload.text("trace_id"), payload.obj("details"))
        }
        return if (response.body.isBlank()) buildJsonObject {} else runCatching {
            val value = Json.parseToJsonElement(response.body)
            if (value == JsonNull) buildJsonObject {} else value.jsonObject
        }.getOrElse { throw IllegalStateException("响应格式不完整") }
    }
}

fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
fun JsonObject.number(key: String): Long = get(key)?.jsonPrimitive?.longOrNull ?: 0L
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: buildJsonObject {}
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
