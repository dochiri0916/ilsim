package com.dochiri.ilsim.task.domain.vo

/** 요구사항 1건이 거치는 단계. approval=true 인 단계는 사용자 승인 전까지 다음으로 넘어가지 않는다. */
enum class Stage(val approval: Boolean) {
    REQUIREMENTS(true),
    TEST_CASES(true),
    TEST_CODE(false),
    IMPLEMENTATION(false),
}

enum class StageStatus { PENDING, RUNNING, WAITING_APPROVAL, PASSED, FAILED }

enum class TaskStatus { ACTIVE, COMPLETED, BLOCKED }
