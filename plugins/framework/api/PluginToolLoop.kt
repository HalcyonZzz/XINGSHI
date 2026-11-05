package com.xingshi.ai.plugins.api

import kotlinx.coroutines.CancellationException

class PluginToolLoop(private val model: PluginModel) {

    suspend fun run(
        prompt: String,
        input: String,
        tools: List<PluginTool>,
        maxRounds: Int = 4,
        isActive: () -> Boolean = { true }
    ): PluginReport {
        if (tools.isNotEmpty() && !model.supportsToolCalls) {
            return PluginReport.Failure("当前辅助模型不支持工具调用")
        }
        val messages = mutableListOf(
            PluginMessage(role = "system", content = prompt),
            PluginMessage(role = "user", content = input)
        )
        var round = 0
        while (true) {
            if (!isActive()) return PluginReport.Cancelled
            val response = try {
                model.complete(messages.toList(), tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return PluginReport.Failure(e.message ?: e.javaClass.simpleName)
            }
            if (!isActive()) return PluginReport.Cancelled
            if (response.finishReason == "length") return PluginReport.Failure("辅助模型输出被截断")
            val calls = response.toolCalls
            if (calls.isEmpty()) {
                val text = response.text
                return if (text.isNullOrBlank()) PluginReport.Failure("辅助模型未返回内容")
                else PluginReport.Success(text)
            }
            if (round >= maxRounds) return PluginReport.Failure("工具调用轮次超过上限")
            round++
            messages.add(
                PluginMessage(
                    role = "assistant",
                    content = response.text ?: "",
                    toolCalls = calls
                )
            )
            for (call in calls) {
                if (!isActive()) return PluginReport.Cancelled
                messages.add(
                    PluginMessage(
                        role = "tool",
                        content = executeTool(call, tools),
                        toolCallId = call.id,
                        name = call.name
                    )
                )
            }
        }
    }

    private suspend fun executeTool(call: PluginToolCall, tools: List<PluginTool>): String {
        val tool = tools.firstOrNull { it.name == call.name } ?: return "工具 ${call.name} 不存在"
        return try {
            tool.execute(call.arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "工具执行失败: ${e.message}"
        }
    }
}
