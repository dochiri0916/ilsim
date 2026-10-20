package com.dochiri.ilsim.rules.domain

import com.dochiri.ilsim.rules.domain.exception.AnalysisInProgressException
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import java.time.Duration
import java.time.Instant

enum class AnalysisStatus { REQUESTED, RUNNING, DONE, FAILED }

data class Analysis(
    val projectId: String,
    val status: AnalysisStatus,
    val requestedAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val report: String? = null,
    val error: String? = null,
) {
    fun start(now: Instant): Analysis {
        if (status != AnalysisStatus.REQUESTED) throw InvalidRuleRequestException("요청 상태가 아닌 분석은 시작할 수 없습니다: $status")
        return copy(status = AnalysisStatus.RUNNING, startedAt = now)
    }

    fun finish(passed: Boolean, report: String?, error: String?, now: Instant): Analysis {
        if (status != AnalysisStatus.RUNNING) throw InvalidRuleRequestException("실행 중이 아닌 분석의 결과입니다: $status")
        return copy(status = if (passed) AnalysisStatus.DONE else AnalysisStatus.FAILED, finishedAt = now, report = report, error = error)
    }

    fun inProgress(now: Instant): Boolean = when (status) {
        AnalysisStatus.REQUESTED -> true
        AnalysisStatus.RUNNING -> startedAt == null || Duration.between(startedAt, now) < STALE
        else -> false
    }

    companion object {
        val STALE: Duration = Duration.ofMinutes(30)

        fun request(projectId: String, previous: Analysis?, now: Instant): Analysis {
            if (projectId.isBlank()) throw InvalidRuleRequestException("프로젝트 id 가 비어 있습니다")
            if (previous != null && previous.inProgress(now)) throw AnalysisInProgressException(projectId)
            return Analysis(projectId, AnalysisStatus.REQUESTED, now)
        }
    }
}
