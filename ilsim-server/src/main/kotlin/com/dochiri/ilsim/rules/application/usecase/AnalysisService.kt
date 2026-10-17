package com.dochiri.ilsim.rules.application.usecase

import com.dochiri.ilsim.rules.application.port.`in`.AnalysisUseCase
import com.dochiri.ilsim.rules.application.port.out.AnalysisStore
import com.dochiri.ilsim.rules.domain.Analysis
import com.dochiri.ilsim.rules.domain.AnalysisStatus
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import java.time.Clock

/** 요청·가져가기·완료가 한 프로젝트에서 엇갈리지 않도록 한 번에 하나씩 처리한다. */
class AnalysisService(private val store: AnalysisStore, private val clock: Clock = Clock.systemUTC()) : AnalysisUseCase {

    @Synchronized
    override fun request(projectId: String): Analysis {
        val id = projectId.trim()
        return Analysis.request(id, store.find(id), clock.instant()).also(store::save)
    }

    override fun latest(projectId: String): Analysis? = store.find(projectId)

    @Synchronized
    override fun claim(projectIds: Collection<String>): Analysis? {
        val wanted = projectIds.toSet()
        val next = store.findAll()
            .filter { it.projectId in wanted && it.status == AnalysisStatus.REQUESTED }
            .minByOrNull { it.requestedAt } ?: return null
        return next.start(clock.instant()).also(store::save)
    }

    @Synchronized
    override fun complete(projectId: String, passed: Boolean, report: String?, error: String?): Analysis {
        val current = store.find(projectId) ?: throw InvalidRuleRequestException("요청된 분석이 없습니다: $projectId")
        return current.finish(passed, report, error, clock.instant()).also(store::save)
    }
}
