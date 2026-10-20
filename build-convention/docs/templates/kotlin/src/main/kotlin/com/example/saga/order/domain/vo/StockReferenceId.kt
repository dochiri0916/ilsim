package com.example.saga.order.domain.vo

import java.util.UUID

@JvmInline
value class StockReferenceId private constructor(
    val value: UUID
) {

    override fun toString(): String = value.toString()

    companion object {
        fun of(value: UUID): StockReferenceId = StockReferenceId(value)
    }
}
