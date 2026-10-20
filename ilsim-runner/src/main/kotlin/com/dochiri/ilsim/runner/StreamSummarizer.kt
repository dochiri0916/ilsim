package com.dochiri.ilsim.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object StreamSummarizer {
    private val json = Json { ignoreUnknownKeys = true }

    fun summarize(line: String): List<Pair<String, String>> {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return listOf("log" to line.take(500))
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> assistant(obj)
            "result" -> listOf("result" to line.take(4000))
            else -> emptyList()
        }
    }

    private fun assistant(obj: JsonObject): List<Pair<String, String>> {
        val content = obj["message"]?.jsonObject?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { block ->
            val b = block.jsonObject
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "text" to it.take(MAX_TEXT_CHARS) }
                "tool_use" -> "tool" to "${b["name"]?.jsonPrimitive?.contentOrNull} ${(b["input"]?.toString() ?: "").take(200)}"
                else -> null
            }
        }
    }
}

private const val MAX_TEXT_CHARS = 20_000
