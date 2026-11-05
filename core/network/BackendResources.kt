package com.xingshi.ai.network

import com.xingshi.ai.domain.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant

fun pathSegment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
fun epochMillis(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

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
    suspend fun createCompanion(key: String, name: String, persona: JsonObject): JsonObject = try {
        http.json("POST", "/api/instances", buildJsonObject {
            put("creation_key", key); put("display_name", name); put("persona_config_ref", persona)
        })
    } catch (failure: BackendHttpException) {
        if (failure.status == 409 && failure.code == "INSTANCE_LIMIT_REACHED") throw CompanionLimitReachedException()
        throw failure
    }
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

interface PendingCircleOperations {
    fun all(): Map<String, String>
    fun put(id: String, value: String)
    fun remove(id: String)
    fun reject(id: String, original: String)
}

private const val PUBLISH_KIND = "CIRCLE_PUBLISH"

private fun publishBody(request: CirclePublishRequest): JsonObject = buildJsonObject {
    put("operation_id", request.operationId)
    put("body", request.body)
    put("attachments", buildJsonArray {
        request.attachments.forEach { attachment ->
            add(buildJsonObject {
                put("kind", attachment.kind.name)
                put("file_name", attachment.fileName)
                put("text", attachment.text)
            })
        }
    })
}

private fun encodePublish(record: CirclePublishRecord): String = buildJsonObject {
    put("kind", PUBLISH_KIND)
    put("operation_id", record.operationId)
    put("account_id", record.accountId)
    put("body", record.body)
    put("state", record.state.name)
    put("media", buildJsonArray {
        record.media.forEach { media ->
            add(buildJsonObject {
                put("id", media.id)
                put("kind", media.kind.name)
                put("file_name", media.fileName)
                put("mime_type", media.mimeType)
                put("path", media.path)
                if (media.text.isNotBlank()) put("text", media.text)
            })
        }
    })
    record.request?.let { request ->
        put("request", buildJsonObject {
            put("operation_id", request.operationId)
            put("body", request.body)
            put("attachments", buildJsonArray {
                request.attachments.forEach { attachment -> add(publishAttachment(attachment)) }
            })
        })
    }
    if (record.failure.isNotBlank()) put("failure", record.failure)
}.toString()

private fun publishAttachment(attachment: CirclePublishAttachment): JsonObject = buildJsonObject {
    put("kind", attachment.kind.name)
    put("file_name", attachment.fileName)
    put("text", attachment.text)
}

private fun decodePublish(raw: String): CirclePublishRecord? {
    val value = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    if (value.text("kind") != PUBLISH_KIND) return null
    val state = runCatching { CirclePublishState.valueOf(value.text("state")) }.getOrNull() ?: return null
    val operationId = value.text("operation_id")
    if (operationId.isBlank()) return null
    val request = if (value.containsKey("request")) decodePublishRequest(value["request"]) ?: return null else null
    return CirclePublishRecord(
        operationId = operationId,
        accountId = value.text("account_id"),
        body = value.text("body"),
        media = value.array("media").mapNotNull(::decodePublishMedia),
        state = state,
        request = request,
        failure = value.text("failure")
    )
}

private fun decodePublishMedia(element: JsonElement): CirclePublishMedia? {
    val value = runCatching { element.jsonObject }.getOrNull() ?: return null
    val kind = runCatching { CircleAttachmentKind.valueOf(value.text("kind")) }.getOrNull() ?: return null
    return CirclePublishMedia(
        id = value.text("id"),
        kind = kind,
        fileName = value.text("file_name"),
        mimeType = value.text("mime_type"),
        path = value.text("path"),
        text = value.text("text")
    )
}

private fun decodePublishRequest(element: JsonElement?): CirclePublishRequest? {
    val value = runCatching { element?.jsonObject }.getOrNull() ?: return null
    val operationId = value.text("operation_id")
    if (operationId.isBlank()) return null
    val attachments = value.array("attachments").mapNotNull { raw ->
        val item = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
        val kind = runCatching { CircleAttachmentKind.valueOf(item.text("kind")) }.getOrNull() ?: return@mapNotNull null
        CirclePublishAttachment(kind, item.text("file_name"), item.text("text"))
    }
    return CirclePublishRequest(operationId, value.text("body"), attachments)
}

class BackendStarCircleGateway(private val http: BackendHttpClient, private val pending: PendingCircleOperations) : StarCircleGateway {
    private var accountId: String? = null

    override suspend fun readFeed(cursor: String?): CircleFeed {
        var pendingFailed = false
        for ((id, encoded) in pending.all()) {
            if (runCatching { Json.parseToJsonElement(encoded).jsonObject.text("kind") }.getOrNull() == PUBLISH_KIND) continue
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
        accountId = viewer.text("user_id")
        return CircleFeed(CirclePerson(viewer.text("user_id"), viewer.text("nickname")), page.array("items").map { post(it.jsonObject) },
            page.text("next_cursor").ifBlank { null }, pendingOperationFailed = pendingFailed)
    }

    override suspend fun pendingPublishes(): List<CirclePublishRecord> {
        val account = accountId ?: return emptyList()
        return pending.all().values.mapNotNull(::decodePublish).filter { it.accountId == account }
    }

    override suspend fun savePublish(record: CirclePublishRecord) {
        val account = record.accountId
        if (account.isBlank()) throw CirclePublishConflict("发布记录缺少账号")
        val existing = pending.all()[record.operationId]?.let(::decodePublish)
        if (existing?.state == CirclePublishState.READY) return
        if (existing?.request != null && existing.request != record.request) throw CirclePublishConflict()
        pending.put(record.operationId, encodePublish(record))
    }

    override suspend fun completePublish(operationId: String) {
        pending.remove(operationId)
    }

    override suspend fun publish(record: CirclePublishRecord): CirclePublishOutcome {
        val account = accountId ?: throw StaleBackendSession()
        if (record.accountId != account) throw StaleBackendSession()
        val request = record.request ?: throw CirclePublishConflict("发布请求尚未冻结")
        val stored = pending.all()[record.operationId]?.let(::decodePublish)
        val actual = if (stored == null) {
            pending.put(record.operationId, encodePublish(record.copy(state = CirclePublishState.READY)))
            request
        } else {
            if (stored.accountId != account) throw StaleBackendSession()
            when (stored.state) {
                CirclePublishState.PREPARING -> {
                    val prepared = stored.copy(state = CirclePublishState.READY, request = request, failure = "")
                    if (stored.body != request.body || stored.media.map { CirclePublishAttachment(it.kind, it.fileName, it.text) } != request.attachments) {
                        pending.reject(record.operationId, encodePublish(stored.copy(failure = "payload-conflict")))
                        return CirclePublishOutcome.Failed(CirclePublishFailure.CONFLICT)
                    }
                    pending.put(record.operationId, encodePublish(prepared))
                    request
                }
                CirclePublishState.READY -> {
                    val frozen = stored.request ?: return CirclePublishOutcome.Failed(CirclePublishFailure.CONFLICT)
                    if (frozen != request) {
                        pending.reject(record.operationId, encodePublish(stored.copy(failure = "payload-conflict")))
                        return CirclePublishOutcome.Failed(CirclePublishFailure.CONFLICT)
                    }
                    frozen
                }
            }
        }
        return try {
            CirclePublishOutcome.Published(post(http.json("POST", "/api/account/moments", publishBody(actual))))
        } catch (failure: BackendHttpException) {
            if (failure.status in setOf(400, 403, 404, 409, 422)) {
                pending.reject(record.operationId, encodePublish(record.copy(state = CirclePublishState.READY, request = actual, failure = "http-${failure.status}")))
                CirclePublishOutcome.Failed(if (failure.status == 409) CirclePublishFailure.CONFLICT else CirclePublishFailure.REJECTED)
            } else CirclePublishOutcome.Failed(CirclePublishFailure.UNCONFIRMED)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || failure is StaleBackendSession) throw failure
            CirclePublishOutcome.Failed(CirclePublishFailure.UNCONFIRMED)
        }
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
            }, value["liked_by_me"]?.jsonPrimitive?.booleanOrNull, attachments(value))
    }
    private fun attachments(value: JsonObject): List<CircleAttachment> = value.array("attachments").mapNotNull { raw ->
        val item = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
        val kind = runCatching { CircleAttachmentKind.valueOf(item.text("kind")) }.getOrNull() ?: return@mapNotNull null
        CircleAttachment(kind, item.text("file_name"), item.text("text"))
    }
}
