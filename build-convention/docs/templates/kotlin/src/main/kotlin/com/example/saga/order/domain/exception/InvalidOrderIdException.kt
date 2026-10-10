package com.example.saga.order.domain.exception

class InvalidOrderIdException(val orderId: String, cause: Throwable? = null) :
    OrderDomainException("유효하지 않은 주문 ID입니다.", cause)
