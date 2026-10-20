package com.dochiri.ilsim.secret.application.port.out

interface SecretEncryptor {
    fun encrypt(name: String, plain: String): String

    fun decrypt(name: String, stored: String): String
}
