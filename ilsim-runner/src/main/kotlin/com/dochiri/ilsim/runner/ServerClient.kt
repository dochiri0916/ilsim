package com.dochiri.ilsim.runner

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ProjectReport(val id: String, val name: String)

@Serializable
data class PollRequest(val runnerId: String, val projects: List<ProjectReport>)

@Serializable
data class AssignedTask(
    val taskId: String,
    val projectId: String,
    val stage: String,
    val attempt: Int,
    val feature: String,
    val model: String,
    /** 추론 강도(low, medium, high, xhigh, max). 서버가 안 보내면 medium. */
    val effort: String = "medium",
    val prompt: String,
    /** 요구사항 대화를 이어 갈 Claude 세션. 있으면 `--resume` 으로 같은 세션에서 문서를 고친다. */
    val resumeSessionId: String? = null,
)

@Serializable
data class EventRequest(val stage: String, val type: String, val data: String)

@Serializable
data class StageResult(
    val passed: Boolean,
    val retryable: Boolean = false,
    val diff: String? = null,
    val error: String? = null,
    val artifacts: Map<String, String> = emptyMap(),
    val validation: Map<String, String> = emptyMap(),
    val branch: String? = null,
    /** 이번 실행에서 쓴 Claude 비용(USD)과 모델. 서버가 단계·작업별로 합산한다. */
    val costUsd: Double? = null,
    val model: String? = null,
    /** AI 검토 호출에 쓴 비용과 모델. */
    val reviewCostUsd: Double? = null,
    val reviewModel: String? = null,
    /** 이번 실행의 Claude 세션. 서버가 요구사항 단계의 대화를 이어 가는 데 쓴다. */
    val sessionId: String? = null,
)

@Serializable
data class AnalysisClaim(val runnerId: String, val projects: List<String>)

@Serializable
data class AnalysisJob(val projectId: String)

/** 웹에서 요청한 프로젝트 분석(아키텍처·컨벤션 규칙 추출)의 결과. [report] 는 CLI 와 같은 사람이 읽는 출력이다. */
@Serializable
data class AnalysisResult(val passed: Boolean, val report: String, val error: String? = null)

/** 실행기가 서버로 보내는 두 가지: 진행 이벤트와 단계 결과. 테스트에서 가짜로 바꾼다. */
interface TaskSink {
    fun event(taskId: String, stage: String, type: String, data: String)
    fun result(taskId: String, result: StageResult)
}

class ServerClient(private val baseUrl: String, private val token: String) : TaskSink {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun post(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .header("Content-Type", "application/json")
                .header("X-Ilsim-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun poll(request: PollRequest): AssignedTask? {
        val res = post("/runner/api/poll", json.encodeToString(request))
        return when (res.statusCode()) {
            200 -> json.decodeFromString<AssignedTask>(res.body())
            204 -> null
            else -> error("poll 실패: HTTP ${res.statusCode()}")
        }
    }

    /** 웹에서 요청된 분석 중 이 Runner 프로젝트의 것 하나를 가져온다. 없으면(204) null. */
    fun claimAnalysis(claim: AnalysisClaim): String? {
        val res = post("/runner/api/analysis/claim", json.encodeToString(claim))
        return when (res.statusCode()) {
            200 -> json.decodeFromString<AnalysisJob>(res.body()).projectId
            204 -> null
            else -> error("분석 요청 확인 실패: HTTP ${res.statusCode()}")
        }
    }

    fun analysisResult(projectId: String, result: AnalysisResult) {
        val res = post("/runner/api/analysis/$projectId/result", json.encodeToString(result))
        check(res.statusCode() in 200..299) { "분석 결과 전송 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    fun reportUsage(usage: PlanUsageReport) {
        val res = post("/runner/api/usage", json.encodeToString(usage))
        check(res.statusCode() in 200..299) { "사용량 전송 실패: HTTP ${res.statusCode()}" }
    }

    /** 웹에서 사용량을 새로 재 달라고 했으면 true. */
    fun claimUsageRefresh(runnerId: String): Boolean {
        val res = post("/runner/api/usage/claim", json.encodeToString(mapOf("runnerId" to runnerId)))
        return when (res.statusCode()) {
            200 -> true
            204 -> false
            else -> error("사용량 요청 확인 실패: HTTP ${res.statusCode()}")
        }
    }

    /** 웹에서 등록한 JEV 키. 등록된 키가 없으면(204) null. 받은 키는 파일에 쓰지 않고 메모리에만 둔다. */
    fun jevKey(): String? {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev")).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() != 200) return null
        return json.parseToJsonElement(res.body()).jsonObject["key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    override fun event(taskId: String, stage: String, type: String, data: String) {
        post("/runner/api/tasks/$taskId/events", json.encodeToString(EventRequest(stage, type, data)))
    }

    override fun result(taskId: String, result: StageResult) {
        val res = post("/runner/api/tasks/$taskId/result", json.encodeToString(result))
        check(res.statusCode() in 200..299) { "결과 전송 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }
}
