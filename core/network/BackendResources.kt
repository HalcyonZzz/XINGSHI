package com.xingshi.ai.network

import com.xingshi.ai.domain.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant

fun pathSegment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
fun epochMillis(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

/** Unknown outcomes keep the original pending command; only definitive rejections end it. */
suspend fun <T> confirmConfigurationEdit(onRejected: (BackendHttpException) -> Unit, action: suspend () -> T): T =
    try { action() }
    catch (failure: BackendHttpException) {
        if (failure.definitiveRejection) onRejected(failure)
        throw failure
    }

fun chatModelConfigurationPayload(draft: ChatModelDraft): JsonObject {
    draft.configurationIssue()?.let { throw MainChatConfigurationException(it) }
    return buildJsonObject {
        put("provider", draft.provider); put("protocol", draft.protocol)
        put("base_url", draft.baseUrl); put("model", draft.model)
        put("parameters", buildJsonObject { put("temperature", draft.temperature); draft.maxTokens?.let { put("max_tokens", it) } })
        if (draft.apiKey.isNotBlank()) put("api_key", draft.apiKey)
    }
}

class BackendResources(val http: BackendHttpClient) {
    suspend fun authenticate(account: String, password: String, registrationId: String?): JsonObject = http.anonymous(
        "POST", if (registrationId == null) "/api/auth/login" else "/api/auth/register", buildJsonObject {
            put("account", account); put("password", password); put("device_name", "XINGSHI Android")
        }, registrationId?.let { mapOf("Idempotency-Key" to it) }.orEmpty())

    suspend fun profile() = http.json("GET", "/api/me/profile")
    suspend fun updateProfile(version: Long, name: String) = http.json("PATCH", "/api/me/profile", buildJsonObject { put("expected_version", version); put("nickname", name) })
    suspend fun instances(after: String? = null) = http.json("GET", "/api/instances?limit=100" + (after?.let { "&after_instance_id=${pathSegment(it)}" } ?: ""))
    suspend fun identity(instance: String) = http.json("GET", "/api/instances/${pathSegment(instance)}/chat-identity")
    suspend fun persona(payload: JsonObject) = http.json("PUT", "/api/instances/persona-configurations", buildJsonObject { put("payload", payload) })
    suspend fun createCompanion(key: String, name: String, persona: JsonObject) = http.json("POST", "/api/instances", buildJsonObject {
        put("creation_key", key); put("display_name", name); put("persona_config_ref", persona)
    })
    suspend fun rename(instance: String, name: String) = http.json("PATCH", "/api/instances/${pathSegment(instance)}", buildJsonObject { put("display_name", name) })
    suspend fun bindPersona(instance: String, version: Long, persona: JsonObject, operation: String) = http.json("PUT", "/api/instances/${pathSegment(instance)}/persona-binding", buildJsonObject {
        put("expected_instance_version", version); put("persona_config_ref", persona); put("operation_id", operation)
    })
    suspend fun chatConfigurations(after: String? = null) = http.json("GET", "/api/account/chat-model-configurations?limit=50" +
        (after?.let { "&after_configuration_id=${pathSegment(it)}" } ?: ""))
    suspend fun chatConfiguration(id: String): JsonObject? {
        var after: String? = null
        do {
            val page = chatConfigurations(after)
            page.array("items").map { it.jsonObject }.firstOrNull { it.text("configuration_id") == id }?.let { return it }
            after = page.text("next_after_configuration_id").ifBlank { null }
        } while (after != null)
        return null
    }
    suspend fun chatBinding(instance: String) = http.json("GET", "/api/instances/${pathSegment(instance)}/chat-configuration")
    suspend fun bindChat(instance: String, body: JsonObject) = http.json("PUT", "/api/instances/${pathSegment(instance)}/chat-configuration", body)
    suspend fun saveChatConfiguration(id: String, version: Long?, draft: ChatModelDraft, operation: String) = http.json("PUT", "/api/account/chat-model-configurations/${pathSegment(id)}", buildJsonObject {
        put("operation_id", operation); put("expected_version", version?.let(::JsonPrimitive) ?: JsonNull)
        chatModelConfigurationPayload(draft).forEach { (key, value) -> put(key, value) }
    })
    suspend fun saveChatConfiguration(id: String, payload: JsonObject) = http.json("PUT", "/api/account/chat-model-configurations/${pathSegment(id)}", payload)
    suspend fun defaultModel(id: String, version: Long, operation: String) = http.json("PUT", "/api/account/chat-model-default", buildJsonObject {
        put("configuration_id", id); put("configuration_version", version); put("operation_id", operation)
    })
    suspend fun deleteConfiguration(id: String, operation: String) = http.json("DELETE", "/api/account/chat-model-configurations/${pathSegment(id)}", headers = mapOf("Idempotency-Key" to operation))
    suspend fun deletion(instance: String, operation: String) = http.json("DELETE", "/api/instances/${pathSegment(instance)}", headers = mapOf("Idempotency-Key" to operation))
    suspend fun deletionStatus(operation: String) = http.json("GET", "/api/instance-deletions/${pathSegment(operation)}")
}

/** Account view only; it never selects a favorable instance to bypass audience rules. */
interface PendingCircleOperations {
    fun all(): Map<String, String>
    fun put(id: String, value: String)
    fun remove(id: String)
    fun reject(id: String, original: String)
}

class BackendStarCircleGateway(private val http: BackendHttpClient, private val pending: PendingCircleOperations) : StarCircleGateway {
    override suspend fun readFeed(cursor: String?): CircleFeed {
        var pendingFailed = false
        for ((id, encoded) in pending.all()) {
            try {
                val operation = Json.parseToJsonElement(encoded).jsonObject
                http.json(operation.text("method"), operation.text("path"), operation.obj("body"))
                pending.remove(id)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException || failure is StaleBackendSession ||
                    (failure is BackendHttpException && failure.status == 401)) throw failure
                pendingFailed = true
                if (failure is BackendHttpException && failure.status in setOf(400, 403, 404, 409, 422)) pending.reject(id, encoded)
            }
        }
        val page = http.json("GET", "/api/account/moments?limit=20" + (cursor?.let { "&cursor=${pathSegment(it)}" } ?: ""))
        val viewer = page.obj("viewer")
        return CircleFeed(CirclePerson(viewer.text("user_id"), viewer.text("nickname")), page.array("items").map { post(it.jsonObject) },
            page.text("next_cursor").ifBlank { null }, pendingOperationFailed = pendingFailed)
    }
    override suspend fun setLike(postId: String, liked: Boolean, operationId: String): CirclePost = mutate("PUT", "/api/account/moments/${pathSegment(postId)}/like", operationId, buildJsonObject { put("liked", liked); put("operation_id", operationId) })
    override suspend fun comment(postId: String, text: String, replyToCommentId: String?, operationId: String): CirclePost = mutate("POST", "/api/account/moments/${pathSegment(postId)}/comments", operationId, buildJsonObject {
        put("body", text); replyToCommentId?.let { put("reply_to_action_id", it) }; put("operation_id", operationId)
    })
    private suspend fun mutate(method: String, path: String, id: String, body: JsonObject): CirclePost {
        val unresolved = pending.all().entries.firstOrNull { Json.parseToJsonElement(it.value).jsonObject.text("path") == path }
        val actualId: String
        val actualBody: JsonObject
        if (unresolved != null) {
            val original = Json.parseToJsonElement(unresolved.value).jsonObject
            check(original.text("method") == method && original.obj("body").filterKeys { it != "operation_id" } == body.filterKeys { it != "operation_id" }) { "原星圈操作尚待确认，请先刷新" }
            actualId = unresolved.key; actualBody = original.obj("body")
        } else {
            actualId = id; actualBody = body
            pending.put(id, buildJsonObject { put("method", method); put("path", path); put("body", body) }.toString())
        }
        val result = try { post(http.json(method, path, actualBody)) }
        catch (failure: BackendHttpException) {
            if (failure.status in setOf(400, 403, 404, 409, 422)) pending.all()[actualId]?.let { pending.reject(actualId, it) }
            throw failure
        }
        pending.remove(actualId)
        return result
    }
    private fun person(value: JsonObject) = CirclePerson(
        if (value.text("instance_id").isNotBlank() && value.text("actor_kind") != "ACCOUNT") value.text("instance_id") else value.text("user_id"), value.text("nickname"))
    private fun post(value: JsonObject): CirclePost {
        val comments = value.array("comments").map { it.jsonObject }
        val authors = comments.associate { it.text("action_id") to person(it) }
        return CirclePost(value.text("post_id"), person(value.obj("author")), value.text("body"), epochMillis(value.text("published_at")),
            value.array("likes").map { person(it.jsonObject) }, comments.map { entry ->
                val target = entry.text("reply_to_action_id").ifBlank { null }
                CircleComment(entry.text("action_id"), person(entry), entry.text("body"), epochMillis(entry.text("created_at")), target, authors[target])
            }, value["liked_by_me"]?.jsonPrimitive?.booleanOrNull)
    }
}
