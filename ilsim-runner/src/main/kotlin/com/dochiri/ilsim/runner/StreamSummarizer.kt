package com.dochiri.ilsim.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** claude stream-json 한 줄을 사람이 읽을 이벤트로 줄인다. (type, data)가 null이면 전송하지 않는다. */
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
                "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "text" to it.take(2000) }
                "tool_use" -> "tool" to "${b["name"]?.jsonPrimitive?.contentOrNull} ${(b["input"]?.toString() ?: "").take(200)}"
                else -> null
            }
        }
    }
}
