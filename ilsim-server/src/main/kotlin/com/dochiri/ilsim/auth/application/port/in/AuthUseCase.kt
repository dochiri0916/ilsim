package com.dochiri.ilsim.auth.application.port.`in`

interface AuthUseCase {
    fun login(clientKey: String, username: String?, password: String?): String?

    fun changePassword(clientKey: String, username: String, currentPassword: String?, newPassword: String?): Boolean
}
