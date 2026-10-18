package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.AiUsage

/** 작업의 난이도와 변경 위험도를 평가하는 외부 판단기(JEV). 평가만 하고 모델 선택은 하지 않는다. */
interface ComplexityAssessor {
    /** 키가 있어 평가를 시도할 수 있는지. */
    fun isAvailable(): Boolean

    /** 평가하지 못하면(키 없음, 호출 실패, 응답 불완전) null. */
    fun assess(task: Task, state: StageState, attempt: Int): Assessment?
    fun drainUsage(): List<AiUsage> = emptyList()
}
