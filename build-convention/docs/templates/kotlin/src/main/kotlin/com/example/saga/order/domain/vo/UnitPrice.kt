package com.example.saga.order.domain.vo

import com.example.saga.order.domain.exception.NegativeUnitPriceException

/** 주문 시점의 단가. 이후 재고 가격이 바뀌어도 주문에 기록된 값은 변하지 않는다. */
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
