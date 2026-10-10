package com.example.saga.order.domain.exception

class NegativeUnitPriceException(val unitPrice: Int) : OrderDomainException("주문 단가는 음수일 수 없습니다.")
