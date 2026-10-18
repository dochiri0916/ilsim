package com.dochiri.ilsim.runner

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AiUsage(
    val id: String,
    val purpose: String,
    val model: String,
    val effort: String = "medium",
    val sessionId: String? = null,
    val cliVersion: String? = null,
    val cumulative: Boolean = false,
    val costUsd: Double? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val status: String = "CONFIRMED",
    val chargedUsd: Double? = null,
    val toolCalls: Int = 0,
    val questions: Int = 0,
    val executionId: String? = null,
    val review: ReviewEvidence? = null,
)

/** 작업·분석과 그 안의 JEV/Claude 호출을 한 실행 기록에 모은다. */
object UsageScope {
    private val current = ThreadLocal<MutableList<AiUsage>?>()
    private val changed = ThreadLocal<(() -> Unit)?>()
    private val execution = ThreadLocal<String?>()
    fun record(usage: AiUsage) {
        val calls = current.get() ?: return
        if (calls.none { it.id == usage.id }) { calls.add(usage.copy(executionId = usage.executionId ?: execution.get())); changed.get()?.invoke() }
    }
    fun complete(pendingId: String, usage: List<AiUsage>) {
        val calls = current.get() ?: return
        calls.removeAll { it.id == pendingId }
        usage.forEach { call -> if (calls.none { it.id == call.id }) calls.add(call.copy(executionId = call.executionId ?: execution.get())) }
        changed.get()?.invoke()
    }
    fun reviewQuality(reviewId: String, quality: ReviewQuality) {
        val calls = current.get() ?: return
        calls.indices.filter { calls[it].review?.id == reviewId }.forEach { i -> calls[i] = calls[i].copy(review = calls[i].review!!.copy(quality = quality)) }
        changed.get()?.invoke()
    }
    fun <T> collect(calls: MutableList<AiUsage>, onChange: () -> Unit = {}, executionId: String? = null, action: () -> T): T {
        val previous = current.get()
        val previousChange = changed.get()
        val previousExecution = execution.get()
        current.set(calls)
        changed.set(onChange)
        execution.set(executionId)
        return try { action() } finally { current.set(previous); changed.set(previousChange); execution.set(previousExecution) }
    }
}

class UsageJournal(private val file: java.nio.file.Path) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun load(): List<AiUsage> = if (!java.nio.file.Files.exists(file)) emptyList() else json.decodeFromString(java.nio.file.Files.readString(file))
    fun save(calls: List<AiUsage>) = KnowledgeFiles.write(file, json.encodeToString(calls))
    fun clear() { java.nio.file.Files.deleteIfExists(file) }
}

object ClaudeUsage {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(output: String, model: String, effort: String, purpose: String, version: String?, tools: Int = 0): List<AiUsage> {
        val id = UUID.randomUUID().toString()
        val obj = runCatching { json.parseToJsonElement(output.trim()).jsonObject }.getOrNull()
        val crashed = obj?.get("subtype")?.jsonPrimitive?.contentOrNull == "error_during_execution"
        val session = obj?.get("session_id")?.jsonPrimitive?.contentOrNull
        val cumulative = version?.substringBefore(' ')?.split('.')?.mapNotNull { it.toIntOrNull() }?.let {
            it.size >= 3 && (it[0] > 2 || it[0] == 2 && (it[1] > 1 || it[1] == 1 && it[2] >= 277))
        } ?: false
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        fun JsonObject.cost(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
        val models = obj?.get("modelUsage") as? JsonObject
        if (!models.isNullOrEmpty()) return models.entries.mapIndexed { i, (name, raw) ->
            val u = raw.jsonObject
            val cost = u.cost("costUSD")
            AiUsage("$id:$name", purpose, name, effort, session, version, cumulative, cost,
                u.number("inputTokens"), u.number("outputTokens"), u.number("cacheReadInputTokens"), u.number("cacheCreationInputTokens"),
                if (cost == null || crashed && cost == 0.0 || version == null && session != null && purpose == "WRITE") "UNKNOWN" else "CONFIRMED", toolCalls = if (i == 0) tools else 0)
        }
        val usage = obj?.get("usage") as? JsonObject ?: JsonObject(emptyMap())
        val cost = obj?.cost("total_cost_usd")
        return listOf(AiUsage(id, purpose, model, effort, session, version, cumulative, cost,
            usage.number("input_tokens"), usage.number("output_tokens"), usage.number("cache_read_input_tokens"), usage.number("cache_creation_input_tokens"),
            if (cost == null || crashed && cost == 0.0 || version == null && session != null) "UNKNOWN" else "CONFIRMED", toolCalls = tools))
    }

    /** assistant의 출력 토큰은 placeholder라 복구치에 포함하지 않는다. message ID로 중복을 제거한다. */
    fun assistant(line: String): AiUsage? = runCatching {
        val obj = json.parseToJsonElement(line).jsonObject
        if (obj["type"]?.jsonPrimitive?.content != "assistant" || obj["parent_tool_use_id"]?.let { it !is JsonNull } == true) return null
        val message = obj["message"]?.jsonObject ?: return null
        val usage = message["usage"]?.jsonObject ?: return null
        val id = message["id"]?.jsonPrimitive?.content ?: return null
        AiUsage(id, "WRITE", message["model"]?.jsonPrimitive?.content ?: "unknown",
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
            cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
            cacheWriteTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0, status = "RECOVERED")
    }.getOrNull()
}
