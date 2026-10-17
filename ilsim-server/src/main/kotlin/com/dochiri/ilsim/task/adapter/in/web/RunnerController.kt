package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.`in`.RunnerUseCase
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Runner 전용 API. `/runner 하위` 는 토큰 필터가 보호한다. */
@RestController
@RequestMapping("/runner/api")
class RunnerController(private val runner: RunnerUseCase) {

    data class ReportedProject(val id: String, val name: String, val runnerId: String? = null)

    data class PollRequest(val runnerId: String, val projects: List<ReportedProject> = emptyList())

    data class EventRequest(val stage: String? = null, val type: String? = null, val data: String? = null)

    @PostMapping("/poll")
    fun poll(@RequestBody request: PollRequest): ResponseEntity<Assignment> {
        val assignment = runner.poll(request.runnerId, request.projects.map { Project(it.id, it.name, it.runnerId) })
        return if (assignment == null) ResponseEntity.noContent().build() else ResponseEntity.ok(assignment)
    }

    @PostMapping("/tasks/{id}/events")
    fun event(@PathVariable id: String, @RequestBody event: EventRequest) = runner.addEvent(id, event.stage, event.type, event.data)

    @PostMapping("/tasks/{id}/result")
    fun result(@PathVariable id: String, @RequestBody result: ReportStageCommand) = runner.report(id, result)
}
