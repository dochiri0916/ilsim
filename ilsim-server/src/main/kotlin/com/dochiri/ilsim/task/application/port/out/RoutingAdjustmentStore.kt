package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.RoutingAdjustments

/** 모델 선택 조정 이력. 마지막 것이 현재 조정이고, 되돌릴 때는 이전 것을 다시 저장한다. */
interface RoutingAdjustmentStore {
    /** 오래된 것부터. */
    fun loadAll(): List<RoutingAdjustments>

    fun save(adjustments: RoutingAdjustments)
}
