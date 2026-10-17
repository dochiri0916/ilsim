package com.dochiri.ilsim.rules.application.port.`in`

import com.dochiri.ilsim.rules.domain.Analysis

interface AnalysisUseCase {
    /** 진행 중인 분석이 있으면 거절한다. */
    fun request(projectId: String): Analysis

    fun latest(projectId: String): Analysis?

    /** [projectIds] 중 요청된 분석 하나를 실행 중으로 바꿔 돌려준다. 없으면 null. */
    fun claim(projectIds: Collection<String>): Analysis?

    fun complete(projectId: String, passed: Boolean, report: String?, error: String?): Analysis
}
