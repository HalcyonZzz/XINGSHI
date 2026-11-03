package com.xingshi.ai.plugins

import com.xingshi.ai.plugins.api.PluginMessage
import com.xingshi.ai.plugins.api.PluginModel
import com.xingshi.ai.plugins.api.PluginModelResponse
import com.xingshi.ai.plugins.api.PluginTool
import com.xingshi.ai.plugins.api.PluginToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class HostModelConfig(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val formatHint: String
) {
    val supportsToolCalls: Boolean get() = toolCallsSupported(provider, formatHint)
}

private fun toolCallsSupported(provider: String, formatHint: String): Boolean =
    formatHint == "openai" &&
        !provider.equals("ANTHROPIC", ignoreCase = true) &&
        !provider.equals("IFLYTEK", ignoreCase = true)

data class ResolvedPluginModelConfig(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val supportsToolCalls: Boolean
)

const val VISION_MODEL_FAILURE_PREFIX = "视觉模型失效，错误码："

class VisionModelFailureException(code: String) : IllegalStateException(VISION_MODEL_FAILURE_PREFIX + code)

class PluginModelResolver(
    private val mainConfig: suspend () -> HostModelConfig?,
    private val visionConfig: suspend () -> HostModelConfig?,
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = PluginHttp::post
) {
    suspend fun resolve(vision: Boolean): PluginModel {
        val main = mainConfig()?.resolved()
        if (!vision) return modelFor(main)
        
        
        val candidates = listOfNotNull(visionConfig()?.resolved(), main)
        if (candidates.isEmpty()) return UnconfiguredPluginModel
        return FallbackPluginModel(candidates.map(::modelFor))
    }

    private fun HostModelConfig.resolved(): ResolvedPluginModelConfig? =
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            null
        } else {
            ResolvedPluginModelConfig(provider, baseUrl, model, apiKey, supportsToolCalls)
        }

    private fun modelFor(config: ResolvedPluginModelConfig?): PluginModel = when {
        config == null -> UnconfiguredPluginModel
        !config.supportsToolCalls -> UnsupportedFormatPluginModel(config.provider)
        else -> OpenAiPluginModel(config, post)
    }
}

private class FallbackPluginModel(private val models: List<PluginModel>) : PluginModel {
    override val isConfigured: Boolean get() = models.any { it.isConfigured }

    override val supportsToolCalls: Boolean get() = models.any { it.isConfigured && it.supportsToolCalls }

    override suspend fun complete(messages: List<PluginMessage>, tools: List<PluginTool>): PluginModelResponse {
        var firstFailureCode: String? = null
        for (model in models) {
            try {
                return model.complete(messages, tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstFailureCode == null) firstFailureCode = modelErrorCode(e)
            }
        }
        throw VisionModelFailureException(firstFailureCode ?: "unknown")
    }
}

private fun modelErrorCode(error: Throwable): String {
    val message = error.message.orEmpty()
    Regex("""HTTP (\d{3})""").find(message)?.let { return it.groupValues[1] }
    return when {
        message.contains("接口格式不支持") -> "unsupported"
        error is SocketTimeoutException -> "timeout"
        error is IOException -> "network"
        else -> error.javaClass.simpleName
    }
}

private class UnsupportedFormatPluginModel(private val provider: String) : PluginModel {
    override val isConfigured: Boolean get() = false

    override val supportsToolCalls: Boolean get() = false

    override suspend fun complete(messages: List<PluginMessage>, tools: List<PluginTool>): PluginModelResponse =
        throw IllegalStateException("$provider 的接口格式不支持插件调用")
}

private object UnconfiguredPluginModel : PluginModel {
    override val isConfigured: Boolean get() = false

    override val supportsToolCalls: Boolean get() = false

    override suspend fun complete(messages: List<PluginMessage>, tools: List<PluginTool>): PluginModelResponse =
        throw IllegalStateException("未配置可用的辅助模型")
}

class OpenAiPluginModel(
    private val config: ResolvedPluginModelConfig,
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String
) : PluginModel {
    override val isConfigured: Boolean get() = true

    override val supportsToolCalls: Boolean get() = config.supportsToolCalls

    override suspend fun complete(messages: List<PluginMessage>, tools: List<PluginTool>): PluginModelResponse {
        val body = OpenAiPluginChat.buildRequest(config, messages, tools)
        val response = post(
            OpenAiPluginChat.endpoint(config.baseUrl),
            mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json"
            ),
            body
        )
        return OpenAiPluginChat.parseResponse(response)
    }
}

object OpenAiPluginChat {
    fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw IllegalArgumentException("辅助模型地址为空")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    fun buildRequest(
        config: ResolvedPluginModelConfig,
        messages: List<PluginMessage>,
        tools: List<PluginTool>
    ): String = buildJsonObject {
        put("model", config.model)
        putJsonArray("messages") {
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                    message.toolCallId?.let { put("tool_call_id", it) }
                    message.name?.let { put("name", it) }
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            message.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", Json.parseToJsonElement(tool.parametersJsonSchema))
                        }
                    }
                }
            }
        }
    }.toString()

    fun parseResponse(body: String): PluginModelResponse {
        return try {
            val choice = Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull() as? JsonObject
                ?: invalid()
            val message = choice["message"] as? JsonObject ?: invalid()
            PluginModelResponse(
                text = message["content"]?.jsonPrimitive?.contentOrNull,
                toolCalls = (message["tool_calls"] as? JsonArray)?.map { toolCallOf(it) } ?: emptyList(),
                finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("模型返回格式无效")
        }
    }

    private fun invalid(): Nothing = throw IllegalStateException("模型返回格式无效")

    private fun toolCallOf(element: JsonElement): PluginToolCall {
        val call = element as? JsonObject ?: invalid()
        val function = call["function"] as? JsonObject ?: invalid()
        return PluginToolCall(
            id = call["id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = function["name"]?.jsonPrimitive?.contentOrNull ?: "",
            arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }
}

object PluginHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun post(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("辅助模型请求失败: HTTP ${response.code}")
            text
        }
    }
}
