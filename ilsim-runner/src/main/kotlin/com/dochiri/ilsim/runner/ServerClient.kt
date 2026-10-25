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
data class AgentTaskRequest(val projectId: String, val feature: String? = null, val requirement: String)

@Serializable
data class AgentTaskState(
    val taskId: String,
    val projectId: String,
    val feature: String,
    val status: String,
    val stage: String? = null,
    val stageStatus: String? = null,
    val attempt: Int? = null,
    val feedback: String? = null,
    val error: String? = null,
    val branch: String? = null,
)

interface AgentTaskServer : TaskSink {
    fun register(request: PollRequest)
    fun createAgentTask(request: AgentTaskRequest): AgentTaskState
    fun claim(taskId: String, runnerId: String): AssignedTask?
    fun agentTask(taskId: String): AgentTaskState?
    fun approve(taskId: String)
    fun reject(taskId: String, feedback: String)
    fun retry(taskId: String, instruction: String?)
    fun reopen(taskId: String, stage: String, feedback: String)
}

interface TaskSink {
    fun event(taskId: String, stage: String, type: String, data: String)
    fun result(taskId: String, result: StageResult)

}

class ServerClient(private val baseUrl: String, private val token: String) : AgentTaskServer {
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

    fun jevKey(): String? {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() == 204) return null
        check(res.statusCode() == 200) { "JEV 키 조회 실패: HTTP ${res.statusCode()}" }
        return json.parseToJsonElement(res.body()).jsonObject["key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    fun jevKeyStatus(): String {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev/status")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "JEV 키 상태 조회 실패: HTTP ${res.statusCode()} ${res.body()}" }
        return res.body()
    }

    fun saveJevKey(key: String) {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json.encodeToString(mapOf("key" to key)))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "JEV 키 저장 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    fun deleteJevKey() {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/secrets/jev")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "JEV 키 삭제 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun event(taskId: String, stage: String, type: String, data: String) {
        sendEvents(taskId, listOf(EventRequest(stage, type, data)))
    }

    fun sendEvents(taskId: String, events: List<EventRequest>) {
        val res = post("/runner/api/tasks/$taskId/events/batch", json.encodeToString(events))
        check(res.statusCode() in 200..299) { "이벤트 전송 실패: HTTP ${res.statusCode()}" }
    }

    override fun register(request: PollRequest) {
        val res = post("/runner/api/projects", json.encodeToString(request))
        check(res.statusCode() in 200..299) { "프로젝트 등록 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun createAgentTask(request: AgentTaskRequest): AgentTaskState {
        val res = post("/runner/api/tasks", json.encodeToString(request))
        check(res.statusCode() == 200) { "작업 생성 실패: HTTP ${res.statusCode()} ${res.body()}" }
        return json.decodeFromString(res.body())
    }

    override fun claim(taskId: String, runnerId: String): AssignedTask? {
        val res = post("/runner/api/tasks/$taskId/claim", json.encodeToString(mapOf("runnerId" to runnerId)))
        return when (res.statusCode()) {
            200 -> json.decodeFromString<AssignedTask>(res.body())
            204 -> null
            else -> error("단계 시작 실패: HTTP ${res.statusCode()} ${res.body()}")
        }
    }

    override fun agentTask(taskId: String): AgentTaskState? {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/runner/api/tasks/$taskId")).timeout(java.time.Duration.ofSeconds(5)).header("X-Ilsim-Token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() == 404) return null
        check(res.statusCode() == 200) { "작업 조회 실패: HTTP ${res.statusCode()} ${res.body()}" }
        return json.decodeFromString(res.body())
    }

    override fun approve(taskId: String) {
        val res = post("/runner/api/tasks/$taskId/approve", "{}")
        check(res.statusCode() in 200..299) { "승인 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun reject(taskId: String, feedback: String) {
        val res = post("/runner/api/tasks/$taskId/reject", json.encodeToString(mapOf("feedback" to feedback)))
        check(res.statusCode() in 200..299) { "거절 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun retry(taskId: String, instruction: String?) {
        val res = post("/runner/api/tasks/$taskId/retry", json.encodeToString(mapOf("instruction" to instruction)))
        check(res.statusCode() in 200..299) { "재시도 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun reopen(taskId: String, stage: String, feedback: String) {
        val res = post("/runner/api/tasks/$taskId/reopen", json.encodeToString(mapOf("stage" to stage, "feedback" to feedback)))
        check(res.statusCode() in 200..299) { "단계 되돌리기 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }

    override fun result(taskId: String, result: StageResult) {
        val res = post("/runner/api/tasks/$taskId/result", json.encodeToString(result))
        check(res.statusCode() in 200..299) { "결과 전송 실패: HTTP ${res.statusCode()} ${res.body()}" }
    }
}
