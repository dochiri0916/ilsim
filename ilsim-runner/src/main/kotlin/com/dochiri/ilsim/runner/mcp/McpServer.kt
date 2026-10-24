package com.dochiri.ilsim.runner.mcp

import java.io.BufferedReader
import java.io.PrintStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpTool(val name: String, val description: String, val inputSchema: JsonObject, val call: (JsonObject) -> String)

class McpServer(private val name: String, private val version: String, private val instructions: String, private val tools: List<McpTool>) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun serve(input: BufferedReader, output: PrintStream) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            handle(line)?.let {
                output.println(it)
                output.flush()
            }
        }
    }

    fun handle(line: String): String? {
        val request = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "JSON 을 해석하지 못했습니다")
        }
        val id = request["id"] ?: return null
        val method = request["method"]?.jsonPrimitive?.content ?: return error(id, INVALID_REQUEST, "method 가 없습니다")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.content ?: PROTOCOL_VERSION)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", name); put("version", version) }
                put("instructions", instructions)
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                put("tools", buildJsonArray {
                    tools.forEach { tool -> add(buildJsonObject { put("name", tool.name); put("description", tool.description); put("inputSchema", tool.inputSchema) }) }
                })
            })
            "tools/call" -> {
                val toolName = params["name"]?.jsonPrimitive?.content
                val tool = tools.firstOrNull { it.name == toolName } ?: return error(id, INVALID_PARAMS, "알 수 없는 도구: $toolName")
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val (text, failed) = try {
                    tool.call(arguments) to false
                } catch (e: Exception) {
                    (e.message ?: e.toString()) to true
                }
                result(id, buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("isError", failed)
                })
            }
            else -> error(id, METHOD_NOT_FOUND, "지원하지 않는 메서드: $method")
        }
    }

    private fun result(id: JsonElement, value: JsonElement): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to value)).toString()

    private fun error(id: JsonElement, code: Int, message: String): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "error" to buildJsonObject { put("code", code); put("message", message) })).toString()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
    }
}
