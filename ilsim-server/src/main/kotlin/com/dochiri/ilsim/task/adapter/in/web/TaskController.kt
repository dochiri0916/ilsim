package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.application.port.`in`.ManageTaskUseCase
import com.dochiri.ilsim.task.application.port.`in`.QueryTaskUseCase
import com.dochiri.ilsim.task.domain.Stats
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class TaskController(
    private val manage: ManageTaskUseCase,
    private val query: QueryTaskUseCase,
) {
    data class CreateTaskRequest(val projectId: String, val feature: String? = null, val requirement: String? = null)

    data class RejectRequest(val feedback: String? = null)

    data class MessageRequest(val text: String? = null)

    @GetMapping("/projects")
    fun projects(): List<ProjectResponse> = query.projects().map(ProjectResponse::from)

    @GetMapping("/stats")
    fun stats(@RequestParam(required = false) projectId: String?): Stats = query.stats(projectId?.takeIf { it.isNotBlank() })

    @PostMapping("/tasks")
    fun create(@RequestBody request: CreateTaskRequest): TaskResponse =
        TaskResponse.from(manage.create(request.projectId, request.feature, request.requirement))

    @GetMapping("/tasks")
    fun list(): List<TaskResponse> = query.list().map(TaskResponse::from)

    @GetMapping("/tasks/{id}")
    fun get(@PathVariable id: String): ResponseEntity<TaskDetailResponse> {
        val task = query.find(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TaskDetailResponse(TaskResponse.from(task), query.events(id).map(EventResponse::from)))
    }

    @PostMapping("/tasks/{id}/approve")
    fun approve(@PathVariable id: String) = manage.approve(id)

    @PostMapping("/tasks/{id}/messages")
    fun message(@PathVariable id: String, @RequestBody request: MessageRequest) = manage.message(id, request.text)

    @PostMapping("/tasks/{id}/reject")
    fun reject(@PathVariable id: String, @RequestBody request: RejectRequest) = manage.reject(id, request.feedback)

    @PostMapping("/tasks/{id}/retry")
    fun retry(@PathVariable id: String) = manage.retry(id)
}
