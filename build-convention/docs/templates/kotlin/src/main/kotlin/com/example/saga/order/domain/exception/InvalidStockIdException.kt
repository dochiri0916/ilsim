package com.example.saga.order.domain.exception

class InvalidStockIdException(val stockId: String, cause: Throwable? = null) :
    OrderDomainException("유효하지 않은 재고 ID입니다.", cause)
