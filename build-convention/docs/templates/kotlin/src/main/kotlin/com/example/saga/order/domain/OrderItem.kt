package com.example.saga.order.domain

import com.example.saga.order.domain.vo.OrderQuantity
import com.example.saga.order.domain.vo.StockReferenceId
import com.example.saga.order.domain.vo.UnitPrice

class OrderItem private constructor(
    val stockId: StockReferenceId,
    val quantity: OrderQuantity,
    val unitPrice: UnitPrice,
) {

    val amount: Long
        get() = unitPrice.value.toLong() * quantity.value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrderItem) return false
        return stockId == other.stockId && quantity == other.quantity && unitPrice == other.unitPrice
    }

    override fun hashCode(): Int = 31 * (31 * stockId.hashCode() + quantity.hashCode()) + unitPrice.hashCode()

    override fun toString(): String = "OrderItem(stockId=$stockId, quantity=$quantity, unitPrice=$unitPrice)"

    companion object {
        fun of(stockId: StockReferenceId, quantity: OrderQuantity, unitPrice: UnitPrice): OrderItem =
            OrderItem(stockId, quantity, unitPrice)
    }
}
