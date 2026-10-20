package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.readText

class DurableTaskSink(
    private val server: TaskSink,
    private val directory: Path,
    private val sendEvents: (String, List<EventRequest>) -> Unit = { id, events -> events.forEach { server.event(id, it.stage, it.type, it.data) } },
    startWorker: Boolean = true,
) : TaskSink, AutoCloseable {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Serializable private data class PendingResult(val taskId: String, val result: StageResult)
    @Serializable private data class PendingEvent(val taskId: String, val event: EventRequest)
    @Volatile private var closed = false
    private val worker = Thread {
        while (!closed) {
            runCatching { flushEvents() }.onFailure { System.err.println("진행 로그 재전송 대기: ${it.message}") }
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
        }
    }.apply { isDaemon = true; name = "ilsim-events"; if (startWorker) start() }

    override fun event(taskId: String, stage: String, type: String, data: String) {
        val id = UUID.randomUUID().toString()
        val file = directory.resolve("events/${System.currentTimeMillis().toString().padStart(16, '0')}-${System.nanoTime()}-$id.json")
        KnowledgeFiles.write(file, json.encodeToString(PendingEvent(taskId, EventRequest(stage, type, data, id))))
    }

    override fun result(taskId: String, result: StageResult) {
        require(result.executionId != null) { "결과 재전송에는 서버 실행 ID가 필요합니다" }
        val file = directory.resolve("results/${result.executionId}.json")
        KnowledgeFiles.write(file, json.encodeToString(PendingResult(taskId, result)))
        flushResults()
    }

    @Synchronized fun flushResults() {
        files("results").forEach { file ->
            val pending = json.decodeFromString<PendingResult>(file.readText())
            server.result(pending.taskId, pending.result)
            Files.deleteIfExists(file)
        }
    }

    @Synchronized internal fun flushEvents() {
        files("events").take(100).groupBy { json.decodeFromString<PendingEvent>(it.readText()).taskId }.forEach { (taskId, batch) ->
            sendEvents(taskId, batch.map { json.decodeFromString<PendingEvent>(it.readText()).event })
            batch.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun files(subdir: String): List<Path> = directory.resolve(subdir).let { dir ->
        if (!dir.exists()) emptyList() else Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".json") }.sorted().toList() }
    }

    override fun shouldStop(taskId: String) = server.shouldStop(taskId)
    override fun shouldStop(taskId: String, executionId: String?) = server.shouldStop(taskId, executionId)
    override fun close() { closed = true; worker.interrupt() }
}
