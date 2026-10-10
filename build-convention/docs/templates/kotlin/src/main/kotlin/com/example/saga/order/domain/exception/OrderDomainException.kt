package com.example.saga.order.domain.exception

sealed class OrderDomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
