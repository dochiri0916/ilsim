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
    /** 이 PC 에 등록된 프로젝트 테스트 명령. 서버가 아니라 Runner 가 채워 Claude 에 허용할 명령으로 쓴다. */
    val testCommand: String? = null,
    val executionId: String? = null,
    /** 이어서 지시한 작업이면 worktree 를 만들 기준 브랜치(직전 작업의 브랜치). 없으면 HEAD. */
    val baseBranch: String? = null,
    val reviewPolicy: ReviewPolicySnapshot = ReviewPolicySnapshot(),
)

@Serializable
data class EventRequest(val stage: String, val type: String, val data: String, val eventId: String? = null)

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
    val executionId: String? = null,
    val stage: String? = null,
    val usage: List<AiUsage> = emptyList(),
    val failureKind: String? = null,
    val decisions: List<ProcessingDecision> = emptyList(),
    /** 요구사항 단계에서 질문에 답만 하고 끝냈으면 true. */
    val answered: Boolean = false,
)

/** 웹에서 삭제한 작업의 worktree 정리 일감. [keepBranch] 면 브랜치(끝난 작업의 결과)는 남긴다. */
@Serializable
data class Cleanup(val taskId: String, val projectId: String, val feature: String, val branch: String? = null, val keepBranch: Boolean = true)

@Serializable
data class AnalysisClaim(val runnerId: String, val projects: List<String>)

@Serializable
data class AnalysisJob(val projectId: String)

/** 웹에서 요청한 프로젝트 분석(아키텍처·컨벤션 규칙 추출)의 결과. [report] 는 CLI 와 같은 사람이 읽는 출력이다. */
@Serializable
data class AnalysisResult(val passed: Boolean, val report: String, val error: String? = null, val usage: List<AiUsage> = emptyList())

/** 실행기가 서버로 보내는 두 가지: 진행 이벤트와 단계 결과. 테스트에서 가짜로 바꾼다. */
interface TaskSink {
    fun event(taskId: String, stage: String, type: String, data: String)
    fun result(taskId: String, result: StageResult)

    /** 실행 중인 작업을 그만둬야 하는지. 기본은 계속(false). */
    fun shouldStop(taskId: String): Boolean = false
    fun shouldStop(taskId: String, executionId: String?): Boolean = shouldStop(taskId)
}

class ServerClient(private val baseUrl: String, private val token: String) : TaskSink {
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun post(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(java.time.Duration.ofSeconds(30))
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
    fun claimCleanups(projects: List<String>): List<Cleanup> {
        val res = post("/runner/api/cleanup/claim", json.encodeToString(mapOf("projects" to projects)))
        check(res.statusCode() == 200) { "정리 요청 확인 실패: HTTP ${res.statusCode()}" }
        return json.decodeFromString(res.body())
    }

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

    fun reviewPolicy(projectId: String): ReviewPolicySnapshot {
        val response = http.send(HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/projects/$projectId/review-policy"))
            .timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "검토 정책 조회 실패: HTTP ${response.statusCode()}" }
        return json.decodeFromString(response.body())
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
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() == 204) return null
        check(res.statusCode() == 200) { "JEV 키 조회 실패: HTTP ${res.statusCode()}" }
        return json.parseToJsonElement(res.body()).jsonObject["key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    override fun event(taskId: String, stage: String, type: String, data: String) {
        sendEvents(taskId, listOf(EventRequest(stage, type, data)))
    }

    fun sendEvents(taskId: String, events: List<EventRequest>) {
        val res = post("/runner/api/tasks/$taskId/events/batch", json.encodeToString(events))
        check(res.statusCode() in 200..299) { "이벤트 전송 실패: HTTP ${res.statusCode()}" }
    }

    override fun shouldStop(taskId: String): Boolean = shouldStop(taskId, null)

    override fun shouldStop(taskId: String, executionId: String?): Boolean {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/tasks/$taskId/stop" + (executionId?.let { "?executionId=$it" } ?: ""))).timeout(java.time.Duration.ofSeconds(3)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() != 200) return false
        return json.parseToJsonElement(res.body()).jsonObject["stop"]?.jsonPrimitive?.content == "true"
    }

    override fun result(taskId: String, result: StageResult) {
        val res = post("/runner/api/tasks/$taskId/result", json.encodeToString(result))
        check(res.statusCode() in 200..299) { "결과 전송 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }
}
