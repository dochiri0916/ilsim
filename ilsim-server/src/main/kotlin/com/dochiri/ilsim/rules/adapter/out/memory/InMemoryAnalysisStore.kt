package com.dochiri.ilsim.rules.adapter.out.memory

import com.dochiri.ilsim.rules.application.port.out.AnalysisStore
import com.dochiri.ilsim.rules.domain.Analysis
import java.util.concurrent.ConcurrentHashMap

class InMemoryAnalysisStore : AnalysisStore {
    private val analyses = ConcurrentHashMap<String, Analysis>()

    override fun find(projectId: String): Analysis? = analyses[projectId]

    override fun findAll(): List<Analysis> = analyses.values.toList()

    override fun save(analysis: Analysis) {
        analyses[analysis.projectId] = analysis
    }
}
