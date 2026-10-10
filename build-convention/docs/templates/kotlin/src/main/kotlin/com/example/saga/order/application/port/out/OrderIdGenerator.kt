package com.example.saga.order.application.port.out

import com.example.saga.order.domain.vo.OrderId

interface OrderIdGenerator {
    fun generate(): OrderId
}
