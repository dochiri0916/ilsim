package com.example.saga.order.adapter.`in`

import java.util.UUID

private const val UUID_V7 = 7
private const val RFC_VARIANT = 2

class InvalidUuidV7Exception(val field: String, val value: UUID) :
    IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")

fun UUID.requireV7(field: String): UUID {
    if (version() != UUID_V7 || variant() != RFC_VARIANT) throw InvalidUuidV7Exception(field, this)
    return this
}
