package com.example.saga.order.domain.exception

import com.example.saga.order.domain.vo.OrderStatus

class InvalidOrderStatusTransitionException(val currentStatus: OrderStatus, val targetStatus: OrderStatus) :
    OrderDomainException("허용되지 않는 주문 상태 변경입니다.")
