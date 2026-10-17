package com.dochiri.ilsim.task;

/** 요구사항 1건이 거치는 단계. approval=true 인 단계는 사용자 승인 전까지 다음으로 넘어가지 않는다. */
public enum Stage {
    REQUIREMENTS(true),
    TEST_CASES(true),
    TEST_CODE(false),
    IMPLEMENTATION(false);

    public final boolean approval;

    Stage(boolean approval) {
        this.approval = approval;
    }
}
