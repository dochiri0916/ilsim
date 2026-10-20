package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.application.port.`in`.RoutingOverview
import com.dochiri.ilsim.task.application.port.`in`.RoutingUseCase
import com.dochiri.ilsim.task.domain.RoutingAdjustment
import com.dochiri.ilsim.task.domain.RoutingAdjustments
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/routing")
class RoutingController(private val routing: RoutingUseCase) {

    data class AdjustRequest(val items: List<RoutingAdjustment> = emptyList(), val note: String? = null)

    data class AdjustmentsResponse(val version: String, val items: List<RoutingAdjustment>, val note: String?, val createdAt: java.time.Instant?) {
        companion object {
            fun from(a: RoutingAdjustments) = AdjustmentsResponse(a.version, a.items, a.note, a.createdAt)
        }
    }

    data class OverviewResponse(val report: com.dochiri.ilsim.task.domain.RoutingReport, val current: AdjustmentsResponse, val history: List<AdjustmentsResponse>,
        val models: Map<String, String>, val upgrades: List<String>, val locked: Set<String>) {
        companion object {
            fun from(o: RoutingOverview) = OverviewResponse(o.report, AdjustmentsResponse.from(o.current), o.history.map(AdjustmentsResponse::from),
                o.models, o.upgrades, com.dochiri.ilsim.task.domain.ModelCatalog.LOCKED)
        }
    }

    @GetMapping
    fun overview(@RequestParam(required = false) projectId: String?): OverviewResponse =
        OverviewResponse.from(routing.routing(projectId?.takeIf { it.isNotBlank() }))

    @PutMapping("/adjustments")
    fun adjust(@RequestBody request: AdjustRequest): AdjustmentsResponse = AdjustmentsResponse.from(routing.adjust(request.items, request.note))

    @PostMapping("/adjustments/recommended")
    fun recommended(@RequestParam(required = false) projectId: String?): AdjustmentsResponse =
        AdjustmentsResponse.from(routing.applyRecommended(projectId?.takeIf { it.isNotBlank() }))

    @PostMapping("/adjustments/{version}/restore")
    fun restore(@PathVariable version: String): AdjustmentsResponse = AdjustmentsResponse.from(routing.restore(version))
}
