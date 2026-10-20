package com.dochiri.ilsim.rules.application.port.`in`

import com.dochiri.ilsim.rules.domain.Analysis

interface AnalysisUseCase {
    fun request(projectId: String): Analysis

    fun latest(projectId: String): Analysis?

    fun claim(projectIds: Collection<String>): Analysis?

    fun complete(projectId: String, passed: Boolean, report: String?, error: String?): Analysis
}
