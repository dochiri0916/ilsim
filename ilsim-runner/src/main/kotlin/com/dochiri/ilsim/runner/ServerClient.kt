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
    val effort: String = "medium",
    val prompt: String,
    val resumeSessionId: String? = null,
    val testCommand: String? = null,
    val executionId: String? = null,
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
    val costUsd: Double? = null,
    val model: String? = null,
    val reviewCostUsd: Double? = null,
    val reviewModel: String? = null,
    val sessionId: String? = null,
    val executionId: String? = null,
    val stage: String? = null,
    val usage: List<AiUsage> = emptyList(),
    val failureKind: String? = null,
    val decisions: List<ProcessingDecision> = emptyList(),
    val answered: Boolean = false,
)

@Serializable
data class Cleanup(val taskId: String, val projectId: String, val feature: String, val branch: String? = null, val keepBranch: Boolean = true)

@Serializable
data class AnalysisClaim(val runnerId: String, val projects: List<String>)

@Serializable
data class ClaudeAccountsRequest(val runnerId: String, val accounts: List<ClaudeAccountReport>)

@Serializable
data class ClaudeLoginJob(val id: String, val name: String)

@Serializable
data class ClaudeLoginResult(val loggedIn: Boolean, val email: String? = null, val plan: String? = null, val error: String? = null)

@Serializable
data class AnalysisJob(val projectId: String)

@Serializable
data class AnalysisResult(val passed: Boolean, val report: String, val error: String? = null, val usage: List<AiUsage> = emptyList())

interface TaskSink {
    fun event(taskId: String, stage: String, type: String, data: String)
    fun result(taskId: String, result: StageResult)

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

    fun claimUsageRefresh(runnerId: String): Boolean {
        val res = post("/runner/api/usage/claim", json.encodeToString(mapOf("runnerId" to runnerId)))
        return when (res.statusCode()) {
            200 -> true
            204 -> false
            else -> error("사용량 요청 확인 실패: HTTP ${res.statusCode()}")
        }
    }

    fun reportModels(models: Map<String, String>): Map<String, String> {
        val res = post("/runner/api/models", json.encodeToString(models))
        check(res.statusCode() == 200) { "모델 버전 보고 실패: HTTP ${res.statusCode()}" }
        return json.decodeFromString(res.body())
    }

    fun claudeEnvironment(): ClaudeEnvironmentReport {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/claude/environment")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "Claude 설정 조회 실패: HTTP ${res.statusCode()}" }
        return json.decodeFromString(res.body())
    }

    fun reportClaudeAccounts(runnerId: String, accounts: List<ClaudeAccountReport>): List<String> {
        val res = post("/runner/api/claude/accounts", json.encodeToString(ClaudeAccountsRequest(runnerId, accounts)))
        check(res.statusCode() == 200) { "Claude 계정 보고 실패: HTTP ${res.statusCode()}" }
        return json.decodeFromString(res.body())
    }

    fun claimClaudeLogin(runnerId: String): ClaudeLoginJob? {
        val res = post("/runner/api/claude/logins/claim", json.encodeToString(mapOf("runnerId" to runnerId)))
        return when (res.statusCode()) {
            200 -> json.decodeFromString<ClaudeLoginJob>(res.body())
            204 -> null
            else -> error("Claude 로그인 요청 확인 실패: HTTP ${res.statusCode()}")
        }
    }

    fun claudeLoginUrl(id: String, url: String) {
        val res = post("/runner/api/claude/logins/$id/url", json.encodeToString(mapOf("url" to url)))
        check(res.statusCode() == 200) { "로그인 URL 전송 실패: HTTP ${res.statusCode()}" }
    }

    fun claudeLoginCode(id: String): String? {
        val res = post("/runner/api/claude/logins/$id/code", "{}")
        if (res.statusCode() != 200) return null
        return json.parseToJsonElement(res.body()).jsonObject["code"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    fun claudeLoginResult(id: String, loggedIn: Boolean, email: String?, plan: String?, error: String?) {
        val res = post("/runner/api/claude/logins/$id/result", json.encodeToString(ClaudeLoginResult(loggedIn, email, plan, error)))
        check(res.statusCode() == 200) { "로그인 결과 전송 실패: HTTP ${res.statusCode()}" }
    }

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
