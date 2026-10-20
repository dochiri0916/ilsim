package com.dochiri.ilsim.auth.application.port.out

interface TotpSecretStore {
    fun find(username: String): String?

    fun save(username: String, secret: String)

    fun delete(username: String)
}
