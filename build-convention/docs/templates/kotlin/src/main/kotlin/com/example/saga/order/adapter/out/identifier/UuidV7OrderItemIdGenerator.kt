package com.example.saga.order.adapter.out.identifier

import com.example.saga.order.application.port.out.OrderItemIdGenerator
import com.example.saga.order.domain.vo.OrderItemId
import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.stereotype.Component

@Component
class UuidV7OrderItemIdGenerator : OrderItemIdGenerator {
    override fun generate(): OrderItemId = OrderItemId.of(UuidCreator.getTimeOrderedEpoch())
}
