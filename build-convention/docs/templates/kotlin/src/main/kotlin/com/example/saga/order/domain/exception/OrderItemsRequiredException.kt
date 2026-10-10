package com.example.saga.order.domain.exception

class OrderItemsRequiredException : OrderDomainException("주문 항목은 1개 이상이어야 합니다.")
