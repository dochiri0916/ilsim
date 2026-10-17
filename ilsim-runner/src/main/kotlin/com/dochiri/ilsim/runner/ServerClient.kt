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
)

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
