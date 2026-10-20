package com.dochiri.ilsim.auth.application.port.`in`

interface TwoFactorUseCase {
    data class Setup(val secret: String, val uri: String)

    fun enabled(username: String): Boolean

    fun setup(username: String): Setup

    fun enable(clientKey: String, username: String, secret: String, code: String?): Boolean

    fun disable(clientKey: String, username: String, code: String?): Boolean

    fun verify(clientKey: String, username: String, code: String?): Boolean
}
