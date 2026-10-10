package com.example.saga.order.domain.exception

class InvalidOrderQuantityException(val quantity: Int) : OrderDomainException("주문 수량은 1 이상이어야 합니다.")
