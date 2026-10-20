package com.dochiri.ilsim.rules.adapter.`in`.web

import com.dochiri.ilsim.rules.application.port.`in`.KnowledgeUseCase
import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.Knowledge
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class KnowledgeController(private val knowledge: KnowledgeUseCase) {

    data class Ids(val ids: List<String>? = null)

    @GetMapping("/api/projects/{projectId}/knowledge")
    fun overview(@PathVariable projectId: String): Knowledge = knowledge.overview(projectId)

    @PostMapping("/api/projects/{projectId}/knowledge/rules/{set}/approve")
    fun approveRules(@PathVariable projectId: String, @PathVariable set: String, @RequestBody body: Ids): ResponseEntity<Void> =
        decideRules(projectId, set, body, Decision.APPROVE)

    @PostMapping("/api/projects/{projectId}/knowledge/rules/{set}/reject")
    fun rejectRules(@PathVariable projectId: String, @PathVariable set: String, @RequestBody body: Ids): ResponseEntity<Void> =
        decideRules(projectId, set, body, Decision.REJECT)

    @PostMapping("/api/projects/{projectId}/knowledge/memory/approve")
    fun approveMemory(@PathVariable projectId: String, @RequestBody body: Ids): ResponseEntity<Void> =
        decideMemory(projectId, body, Decision.APPROVE)

    @PostMapping("/api/projects/{projectId}/knowledge/memory/reject")
    fun rejectMemory(@PathVariable projectId: String, @RequestBody body: Ids): ResponseEntity<Void> =
        decideMemory(projectId, body, Decision.REJECT)

    private fun decideRules(projectId: String, set: String, body: Ids, decision: Decision): ResponseEntity<Void> {
        knowledge.decideRules(projectId, RuleSet.parse(set), body.ids, decision)
        return ResponseEntity.noContent().build()
    }

    private fun decideMemory(projectId: String, body: Ids, decision: Decision): ResponseEntity<Void> {
        knowledge.decideMemory(projectId, body.ids, decision)
        return ResponseEntity.noContent().build()
    }

    @ExceptionHandler(InvalidRuleRequestException::class)
    fun invalid(e: InvalidRuleRequestException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
