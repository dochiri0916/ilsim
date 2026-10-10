package com.example.saga.order.adapter.out.identifier

import com.example.saga.order.application.port.out.OrderIdGenerator
import com.example.saga.order.domain.vo.OrderId
import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.stereotype.Component

@Component
class UuidV7OrderIdGenerator : OrderIdGenerator {
    override fun generate(): OrderId = OrderId.of(UuidCreator.getTimeOrderedEpoch())
}
