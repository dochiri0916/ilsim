package com.dochiri.ilsim.auth.application.port.`in`

interface AuthUseCase {
    /** 맞으면 아이디를, 틀리면 null. 연속 실패가 많으면 [com.dochiri.ilsim.auth.domain.exception.LoginLockedException]. */
    fun login(clientKey: String, username: String?, password: String?): String?

    /** 현재 비밀번호가 맞을 때만 바꾼다. 틀리면 false. */
    fun changePassword(clientKey: String, username: String, currentPassword: String?, newPassword: String?): Boolean
}
