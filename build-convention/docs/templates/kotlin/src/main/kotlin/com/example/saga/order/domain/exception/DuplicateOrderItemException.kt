package com.example.saga.order.domain.exception

class DuplicateOrderItemException(val stockId: String) : OrderDomainException("같은 재고를 중복해서 주문할 수 없습니다.")
