package com.dochiri.ilsim.runner

import java.nio.file.Files

object ClaudeCli {
    data class Answer(val text: String, val costUsd: Double?, val usage: List<AiUsage> = emptyList())
    class Failure(val answer: Answer, message: String) : IllegalStateException(message)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(output: String): Answer = try {
        val obj = json.parseToJsonElement(output.trim()) as kotlinx.serialization.json.JsonObject
        val text = obj["structured_output"]?.toString() ?: (obj["result"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: output
        val cost = (obj["total_cost_usd"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        Answer(text, cost)
    } catch (e: Exception) {
        Answer(output, null)
    }

    fun ask(prompt: String, model: String): String = askWithCost(prompt, model).text

    internal fun command(prompt: String, model: String, effort: String, budgetUsd: Double? = null) = listOf("claude", "-p", "--model", model, "--effort", effort,
        "--output-format", "json", "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}") +
        (budgetUsd?.let { require(it.isFinite() && it > 0); listOf("--max-budget-usd", it.toString()) } ?: emptyList())

    fun askWithCost(prompt: String, model: String): Answer = askWithCost(prompt, model, ClaudeModels.EFFORT)

    fun askWithCost(prompt: String, model: String, effort: String, purpose: String = "REVIEW", review: ReviewEvidence? = null, budgetUsd: Double? = null): Answer {
        val dir = Files.createTempDirectory("ilsim-ask")
        val pendingId = java.util.UUID.randomUUID().toString()
        UsageScope.record(AiUsage(pendingId, purpose, model, effort, status = "UNKNOWN", review = review))
        try {
            val r = Shell.run(dir, *command(prompt, model, effort, budgetUsd).toTypedArray(), timeoutMinutes = 5, maxChars = 2_000_000, stdin = prompt)
            val usages = ClaudeUsage.parse(r.output, model, effort, purpose, null).map {
                it.copy(cumulative = false, status = if (it.costUsd == null) "UNKNOWN" else "CONFIRMED", review = review?.copy(response = parse(r.output).text))
            }
            UsageScope.complete(pendingId, usages)
            val answer = parse(r.output).copy(usage = usages)
            if (r.exit != 0) throw Failure(answer, "claude 실행 실패(${r.exit}): ${r.output.takeLast(500)}")
            return answer
        } catch (e: java.io.IOException) {
            if (e.message?.startsWith("Cannot run program") == true) UsageScope.complete(pendingId, emptyList())
            throw e
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

class CostTally(private val purpose: String = "REVIEW", private val asker: (String, String) -> ClaudeCli.Answer = ReviewRouter::askWithCost) {
    var usd: Double = 0.0
        private set
    private var unknown = 0
    var lastReviewer: String? = null
        internal set

    fun ask(prompt: String, model: String): String {
        val answer = try { ReviewContext.forPurpose(purpose) { asker(prompt, model) } } catch (e: ClaudeCli.Failure) { count(e.answer); throw e }
        count(answer)
        lastReviewer = answer.usage.firstOrNull()?.let { "${it.model}/${it.effort}" } ?: model
        return answer.text
    }

    private fun count(answer: ClaudeCli.Answer) {
        answer.costUsd?.let { usd += it } ?: run { unknown++ }
    }

    fun summary(): String? = when {
        usd == 0.0 && unknown == 0 -> null
        unknown == 0 -> "Claude 비용(AI 검토): $${"%.4f".format(usd)}"
        else -> "Claude 비용(AI 검토): $${"%.4f".format(usd)} 이상 (비용을 알 수 없는 호출 ${unknown}건)"
    }
}
