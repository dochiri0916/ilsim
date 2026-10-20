package com.example.saga.order.domain.vo

import com.example.saga.order.domain.exception.NegativeUnitPriceException

@JvmInline
value class UnitPrice private constructor(
    val value: Int
) {

    init {
        if (value < 0) throw NegativeUnitPriceException(value)
    }

    override fun toString(): String = value.toString()

    companion object {
        fun of(value: Int): UnitPrice = UnitPrice(value)
    }
}
