package com.dochiri.ilsim.usage.adapter.`in`.web

import com.dochiri.ilsim.usage.application.port.`in`.PlanUsageUseCase
import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow
import com.dochiri.ilsim.usage.domain.exception.InvalidUsageRequestException
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class UsageController(private val usages: PlanUsageUseCase) {

    data class WindowRequest(val utilization: Double = 0.0, val resetsAt: Long? = null)

    data class ReportRequest(
        val runnerId: String = "",
        val provider: String = PlanUsage.CLAUDE,
        val status: String? = null,
        val fiveHour: WindowRequest? = null,
        val sevenDay: WindowRequest? = null,
    )

    data class ClaimRequest(val runnerId: String = "")

    @GetMapping("/api/usage")
    fun all(): List<PlanUsage> = usages.all()

    @PostMapping("/api/usage/{runnerId}/refresh")
    fun refresh(@PathVariable runnerId: String): ResponseEntity<PlanUsage> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(usages.requestRefresh(runnerId))

    @PostMapping("/runner/api/usage")
    fun report(@RequestBody request: ReportRequest): PlanUsage =
        usages.report(request.runnerId, request.provider, request.status, request.fiveHour?.toWindow(), request.sevenDay?.toWindow())

    @PostMapping("/runner/api/usage/claim")
    fun claim(@RequestBody request: ClaimRequest): ResponseEntity<Void> =
        if (usages.claimRefresh(request.runnerId)) ResponseEntity.ok().build() else ResponseEntity.noContent().build()

    @ExceptionHandler(InvalidUsageRequestException::class)
    fun invalid(e: InvalidUsageRequestException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    private fun WindowRequest.toWindow() = UsageWindow(utilization.coerceIn(0.0, 1.0), resetsAt?.let(Instant::ofEpochSecond))
}
