package com.dochiri.ilsim.auth.application.port.out

interface PasswordHasher {
    fun hash(password: String): String

    fun matches(password: String, hash: String): Boolean
}
