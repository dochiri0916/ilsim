package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.`in`.RunnerUseCase
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Task
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/runner/api")
class RunnerController(private val runner: RunnerUseCase) {

    data class ReportedProject(val id: String, val name: String, val runnerId: String? = null)

    data class PollRequest(val runnerId: String, val projects: List<ReportedProject> = emptyList())

    data class EventRequest(val stage: String? = null, val type: String? = null, val data: String? = null, val eventId: String? = null)

    @PostMapping("/tasks/{id}/events")
    fun event(@PathVariable id: String, @RequestBody event: EventRequest) = runner.addEvent(id, event.stage, event.type, event.data)

    @PostMapping("/tasks/{id}/events/batch")
    fun events(@PathVariable id: String, @RequestBody events: List<EventRequest>) {
        require(events.size <= 100) { "이벤트는 한 번에 100건까지 전송할 수 있습니다" }
        events.forEach { runner.addIdentifiedEvent(id, it.stage, it.type, it.data, it.eventId) }
    }

    @PostMapping("/tasks/{id}/result")
    fun result(@PathVariable id: String, @RequestBody result: ReportStageCommand) = runner.report(id, result)

    @PostMapping("/projects")
    fun register(@RequestBody request: PollRequest) = runner.register(request.runnerId, request.projects.map { Project(it.id, it.name, it.runnerId) })

    data class AgentTaskRequest(val projectId: String, val feature: String? = null, val requirement: String? = null)

    data class AgentTaskResponse(
        val taskId: String, val projectId: String, val feature: String, val status: String, val stage: String?, val stageStatus: String?,
        val attempt: Int?, val feedback: String?, val error: String?, val branch: String?,
    ) {
        companion object {
            fun of(task: Task): AgentTaskResponse = task.current().let { stage ->
                AgentTaskResponse(task.id.value, task.projectId.value, task.feature.value, task.status.name, stage?.stage?.name, stage?.status?.name,
                    stage?.attempt?.value, stage?.feedback, stage?.error, task.branch?.value)
            }
        }
    }

    data class ClaimRequest(val runnerId: String)

    @PostMapping("/tasks")
    fun create(@RequestBody request: AgentTaskRequest): AgentTaskResponse = AgentTaskResponse.of(runner.createForAgent(request.projectId, request.feature, request.requirement))

    @GetMapping("/tasks/{id}")
    fun state(@PathVariable id: String): ResponseEntity<AgentTaskResponse> =
        runner.find(id)?.let { ResponseEntity.ok(AgentTaskResponse.of(it)) } ?: ResponseEntity.notFound().build()

    @PostMapping("/tasks/{id}/claim")
    fun claim(@PathVariable id: String, @RequestBody request: ClaimRequest): ResponseEntity<Assignment> =
        runner.claim(id, request.runnerId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
}
