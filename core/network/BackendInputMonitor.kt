package com.xingshi.ai.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

 
data class BackendChatFailure(val code: String = "", val category: String = "", val retryable: Boolean = false,
    val reason: String = "", val nextAction: String = "NONE")

data class BackendInputProgress(val humanMessageId: String? = null, val generationId: String? = null,
    val state: String = "PENDING", val error: String? = null, val nextAction: String = "WAIT",
    val failure: BackendChatFailure? = null, val stage: String = "")

 
class BackendInputMonitor(private val http: BackendHttpClient) {
    fun occurredAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    fun needsAcceptance(progress: BackendInputProgress) = progress.state == "PENDING" && progress.humanMessageId == null

    suspend fun advance(chatPath: String, original: JsonObject, progress: BackendInputProgress): BackendInputProgress {
        if (progress.state in setOf("COMPLETED", "FAILED")) return progress
        val byClient = read(chatPath, "client_message_id", original.text("client_message_id"))
        if (byClient != null) return parse(byClient)
        val byHuman = progress.humanMessageId?.takeIf { it.isNotBlank() }?.let { read(chatPath, "human_message_id", it) }
        if (byHuman != null) return parse(byHuman)
        
        check(progress.humanMessageId == null) { "已受理输入状态暂不可读取，稍后重试" }
        return confirm(chatPath, original)
    }

     
    private suspend fun read(chatPath: String, key: String, value: String): JsonObject? = try {
        http.json("GET", "$chatPath/messages/status?$key=${pathSegment(value)}")
    } catch (missing: BackendHttpException) {
        if (missing.status == 404) null else throw missing
    }

    private suspend fun confirm(chatPath: String, original: JsonObject): BackendInputProgress {
        val result = http.json("POST", "$chatPath/messages", original, mapOf("Idempotency-Key" to original.text("client_message_id")))
        return BackendInputProgress(humanMessageId = result.obj("human_message").text("message_id").ifBlank { null },
            generationId = result.obj("generation").text("generation_id").ifBlank { null }, state = "ACCEPTED", nextAction = "WAIT")
    }

    private fun parse(result: JsonObject): BackendInputProgress {
        val failure = failure(result.obj("failure"))
        val remote = result.text("state")
        return BackendInputProgress(
            humanMessageId = result.text("human_message_id").ifBlank { null },
            generationId = result.text("generation_id").ifBlank { null },
            state = when (remote) {
                "COMPLETED" -> "COMPLETED"
                "FAILED" -> "FAILED"
                
                "UNKNOWN" -> if (failure != null) "FAILED" else "UNKNOWN"
                else -> "ACCEPTED"
            },
            nextAction = result.text("next_action").ifBlank { "WAIT" },
            failure = failure, stage = remote)
    }

    private fun failure(value: JsonObject): BackendChatFailure? {
        val code = value.text("code").ifBlank { return null }
        return BackendChatFailure(code = code, category = value.text("category"),
            retryable = value.get("retryable")?.jsonPrimitive?.booleanOrNull ?: false,
            reason = value.text("reason"), nextAction = value.text("next_action").ifBlank { "NONE" })
    }
}
