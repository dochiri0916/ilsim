package com.dochiri.ilsim.secret.api

interface SecretReader {
    fun get(name: String): String?
}

interface SecretWriter {
    fun put(name: String, plain: String?)

    fun delete(name: String)
}

object SecretNames {
    const val JEV_API_KEY: String = "jev-api-key"

    fun totp(username: String): String = "totp:$username"
}
