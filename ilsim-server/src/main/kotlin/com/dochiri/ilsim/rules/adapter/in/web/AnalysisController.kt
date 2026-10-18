package com.dochiri.ilsim.rules.adapter.`in`.web

import com.dochiri.ilsim.rules.application.port.`in`.AnalysisUseCase
import com.dochiri.ilsim.rules.domain.Analysis
import com.dochiri.ilsim.task.api.ProjectUsageRecorder
import com.dochiri.ilsim.task.api.ProjectUsageReport
import com.dochiri.ilsim.rules.domain.exception.AnalysisInProgressException
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 웹에서 프로젝트 분석을 요청하면 Runner 가 가져가 규칙을 다시 뽑고 결과를 보고한다. */
@RestController
class AnalysisController(private val analyses: AnalysisUseCase, private val costs: ProjectUsageRecorder? = null) {

    data class ClaimRequest(val runnerId: String? = null, val projects: List<String> = emptyList())

    data class ClaimResponse(val projectId: String)

    data class ResultRequest(val passed: Boolean = false, val report: String? = null, val error: String? = null, val usage: List<ProjectUsageReport> = emptyList())

    @PostMapping("/api/projects/{projectId}/knowledge/analysis")
    fun request(@PathVariable projectId: String): ResponseEntity<Analysis> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(analyses.request(projectId))

    @GetMapping("/api/projects/{projectId}/knowledge/analysis")
    fun latest(@PathVariable projectId: String): ResponseEntity<Analysis> =
        analyses.latest(projectId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PostMapping("/runner/api/analysis/claim")
    fun claim(@RequestBody request: ClaimRequest): ResponseEntity<ClaimResponse> =
        analyses.claim(request.projects)?.let { ResponseEntity.ok(ClaimResponse(it.projectId)) } ?: ResponseEntity.noContent().build()

    @PostMapping("/runner/api/analysis/{projectId}/result")
    fun result(@PathVariable projectId: String, @RequestBody request: ResultRequest): Analysis {
        costs?.recordProjectUsage(projectId, request.usage)
        return analyses.complete(projectId, request.passed, request.report, request.error)
    }

    @ExceptionHandler(AnalysisInProgressException::class)
    fun inProgress(e: AnalysisInProgressException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))

    @ExceptionHandler(InvalidRuleRequestException::class)
    fun invalid(e: InvalidRuleRequestException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
