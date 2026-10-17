package com.dochiri.ilsim.rules.adapter.out.memory

import com.dochiri.ilsim.rules.application.port.out.AnalysisStore
import com.dochiri.ilsim.rules.domain.Analysis
import java.util.concurrent.ConcurrentHashMap

/** 분석 결과(규칙)는 파일에 남으므로 진행 상태는 서버를 다시 띄우면 사라져도 된다. */
class InMemoryAnalysisStore : AnalysisStore {
    private val analyses = ConcurrentHashMap<String, Analysis>()

    override fun find(projectId: String): Analysis? = analyses[projectId]

    override fun findAll(): List<Analysis> = analyses.values.toList()

    override fun save(analysis: Analysis) {
        analyses[analysis.projectId] = analysis
    }
}
