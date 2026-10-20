package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.RoutingAdjustments

interface RoutingAdjustmentStore {
    fun loadAll(): List<RoutingAdjustments>

    fun save(adjustments: RoutingAdjustments)
}
