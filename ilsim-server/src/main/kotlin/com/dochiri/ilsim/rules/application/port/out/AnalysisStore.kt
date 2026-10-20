package com.dochiri.ilsim.rules.application.port.out

import com.dochiri.ilsim.rules.domain.Analysis

interface AnalysisStore {
    fun find(projectId: String): Analysis?

    fun findAll(): List<Analysis>

    fun save(analysis: Analysis)
}
