package com.example.saga.order.domain

import com.example.saga.order.domain.exception.InvalidOrderStatusTransitionException
import com.example.saga.order.domain.vo.OrderId
import com.example.saga.order.domain.vo.OrderStatus

class Order private constructor(
    val id: OrderId,
    val items: OrderItems,
    val status: OrderStatus,
) {

    val totalAmount: Long
        get() = items.totalAmount

    fun complete(): Order = transitionTo(OrderStatus.COMPLETED)

    fun cancel(): Order = transitionTo(OrderStatus.CANCELLED)

    private fun transitionTo(target: OrderStatus): Order {
        if (!status.canTransitionTo(target)) throw InvalidOrderStatusTransitionException(status, target)
        return Order(id, items, target)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Order(id=$id, items=$items, status=$status)"

    companion object {
        fun register(id: OrderId, items: OrderItems): Order = Order(id, items, OrderStatus.PENDING)

        fun restore(id: OrderId, items: OrderItems, status: OrderStatus): Order = Order(id, items, status)
    }
}
