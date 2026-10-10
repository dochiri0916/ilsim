package com.example.saga.order.domain.vo

import java.util.UUID

/** inventory-service의 재고를 가리키는 참조 ID. 서비스 간 모델 공유를 피하려고 order 쪽에 따로 둔다. */
@JvmInline
value class StockReferenceId private constructor(
    val value: UUID
) {

    override fun toString(): String = value.toString()

    companion object {
        fun of(value: UUID): StockReferenceId = StockReferenceId(value)
    }
}
