package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.AiUsage

interface ComplexityAssessor {
    fun isAvailable(): Boolean

    fun assess(task: Task, state: StageState, attempt: Int): Assessment?
    fun drainUsage(): List<AiUsage> = emptyList()
}
