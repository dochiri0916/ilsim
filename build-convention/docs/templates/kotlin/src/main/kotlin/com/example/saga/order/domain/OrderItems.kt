package com.example.saga.order.domain

import com.example.saga.order.domain.exception.DuplicateOrderItemException
import com.example.saga.order.domain.exception.OrderAmountOverflowException
import com.example.saga.order.domain.exception.OrderItemsRequiredException

class OrderItems private constructor(
    private val values: List<OrderItem>
) {

    val totalAmount: Long

    init {
        if (values.isEmpty()) throw OrderItemsRequiredException()
        val seen = HashSet<Any>()
        values.forEach { if (!seen.add(it.stockId)) throw DuplicateOrderItemException(it.stockId.toString()) }
        totalAmount = values.fold(0L) { total, item ->
            try {
                Math.addExact(total, item.amount)
            } catch (e: ArithmeticException) {
                throw OrderAmountOverflowException()
            }
        }
    }

    fun asList(): List<OrderItem> = values

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrderItems) return false
        return values == other.values
    }

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "OrderItems($values)"

    companion object {
        fun of(values: List<OrderItem>): OrderItems = OrderItems(java.util.List.copyOf(values))
    }
}
