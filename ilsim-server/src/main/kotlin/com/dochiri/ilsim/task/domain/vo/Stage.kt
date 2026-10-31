package com.dochiri.ilsim.task.domain.vo

enum class Stage(val approval: Boolean) {
    REQUIREMENTS(true),
    TEST_CASES(true),
    TEST_CODE(false),
    IMPLEMENTATION(false),
}

enum class StageStatus { PENDING, RUNNING, WAITING_APPROVAL, PASSED, FAILED }

enum class TaskStatus { ACTIVE, COMPLETED, BLOCKED, CANCELLED }
