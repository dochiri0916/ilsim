package com.dochiri.ilsim.runner

import java.nio.file.Files

/** 도구 없이 한 번 질문하고 답을 받는다. 프로젝트 파일에 접근하지 않도록 빈 임시 디렉터리에서 실행한다. */
object ClaudeCli {
    /** 답과, 알 수 있으면 그 호출의 비용(USD). */
    data class Answer(val text: String, val costUsd: Double?)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** `--output-format json` 의 단일 결과 객체에서 답과 비용을 읽는다. 형식이 다르면 출력 전체를 답으로 본다. */
    fun parse(output: String): Answer = try {
        val obj = json.parseToJsonElement(output.trim()) as kotlinx.serialization.json.JsonObject
        val text = (obj["result"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: output
        val cost = (obj["total_cost_usd"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        Answer(text, cost)
    } catch (e: Exception) {
        Answer(output, null)
    }

    fun ask(prompt: String, model: String): String = askWithCost(prompt, model).text

    fun askWithCost(prompt: String, model: String): Answer {
        val dir = Files.createTempDirectory("ilsim-ask")
        try {
            val r = Shell.run(dir, "claude", "-p", prompt, "--model", model, "--effort", ClaudeModels.EFFORT, "--output-format", "json", timeoutMinutes = 5, maxChars = 200_000)
            check(r.exit == 0) { "claude 실행 실패(${r.exit}): ${r.output.takeLast(500)}" }
            return parse(r.output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** 수동 실행 명령(규칙 만들기, 위험 검토)에서 AI 검토 호출에 쓴 비용을 모아 마지막에 보여 준다. 서버 작업이 없어 서버로는 보내지 않는다. */
class CostTally(private val asker: (String, String) -> ClaudeCli.Answer = ClaudeCli::askWithCost) {
    var usd: Double = 0.0
        private set
    private var unknown = 0

    fun ask(prompt: String, model: String): String {
        val answer = asker(prompt, model)
        answer.costUsd?.let { usd += it } ?: run { unknown++ }
        return answer.text
    }

    fun summary(): String? = when {
        usd == 0.0 && unknown == 0 -> null
        unknown == 0 -> "Claude 비용(AI 검토): $${"%.4f".format(usd)}"
        else -> "Claude 비용(AI 검토): $${"%.4f".format(usd)} 이상 (비용을 알 수 없는 호출 ${unknown}건)"
    }
}
