package com.dochiri.ilsim.rules.application.port.out

import com.dochiri.ilsim.rules.domain.Analysis

/** 프로젝트별 최근 분석 하나. */
interface AnalysisStore {
    fun find(projectId: String): Analysis?

    fun findAll(): List<Analysis>

    fun save(analysis: Analysis)
}
