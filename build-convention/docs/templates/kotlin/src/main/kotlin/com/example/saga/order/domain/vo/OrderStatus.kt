package com.example.saga.order.domain.vo

enum class OrderStatus {
    PENDING,
    COMPLETED,
    CANCELLED;

    fun canTransitionTo(target: OrderStatus): Boolean = this == PENDING && target != PENDING
}
