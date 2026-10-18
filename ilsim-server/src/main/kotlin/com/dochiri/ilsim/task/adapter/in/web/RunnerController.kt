package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.`in`.RunnerUseCase
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.application.result.Cleanup
import com.dochiri.ilsim.task.domain.Project
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Runner 전용 API. `/runner 하위` 는 토큰 필터가 보호한다. */
@RestController
@RequestMapping("/runner/api")
class RunnerController(private val runner: RunnerUseCase) {

    data class ReportedProject(val id: String, val name: String, val runnerId: String? = null)

    data class PollRequest(val runnerId: String, val projects: List<ReportedProject> = emptyList())

    data class EventRequest(val stage: String? = null, val type: String? = null, val data: String? = null, val eventId: String? = null)

    @PostMapping("/poll")
    fun poll(@RequestBody request: PollRequest): ResponseEntity<Assignment> {
        val assignment = runner.poll(request.runnerId, request.projects.map { Project(it.id, it.name, it.runnerId) })
        return if (assignment == null) ResponseEntity.noContent().build() else ResponseEntity.ok(assignment)
    }

    @GetMapping("/projects/{id}/review-policy")
    fun reviewPolicy(@PathVariable id: String) = runner.reviewPolicy(id)

    @PostMapping("/tasks/{id}/events")
    fun event(@PathVariable id: String, @RequestBody event: EventRequest) = runner.addEvent(id, event.stage, event.type, event.data)

    @PostMapping("/tasks/{id}/events/batch")
    fun events(@PathVariable id: String, @RequestBody events: List<EventRequest>) {
        require(events.size <= 100) { "이벤트는 한 번에 100건까지 전송할 수 있습니다" }
        events.forEach { runner.addIdentifiedEvent(id, it.stage, it.type, it.data, it.eventId) }
    }

    data class CleanupClaim(val projects: List<String> = emptyList())

    /** 삭제된 작업의 worktree 정리 일감. Runner 가 일감을 확인할 때마다 가져간다. */
    @PostMapping("/cleanup/claim")
    fun cleanups(@RequestBody request: CleanupClaim): List<Cleanup> = runner.claimCleanups(request.projects)

    /** 실행 중인 Runner 가 몇 초마다 묻는다. true 면 Claude·테스트 프로세스를 끝내고 결과를 보낸다. */
    @GetMapping("/tasks/{id}/stop")
    fun stop(@PathVariable id: String, @RequestParam(required = false) executionId: String? = null): Map<String, Boolean> = mapOf("stop" to runner.shouldStop(id, executionId))

    @PostMapping("/tasks/{id}/result")
    fun result(@PathVariable id: String, @RequestBody result: ReportStageCommand) = runner.report(id, result)
}
