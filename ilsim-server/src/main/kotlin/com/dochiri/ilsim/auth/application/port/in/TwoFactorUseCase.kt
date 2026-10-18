package com.dochiri.ilsim.auth.application.port.`in`

interface TwoFactorUseCase {
    data class Setup(val secret: String, val uri: String)

    fun enabled(username: String): Boolean

    /** 등록할 새 비밀. 코드를 확인해 [enable] 하기 전까지는 저장하지 않는다. */
    fun setup(username: String): Setup

    /** [secret] 으로 만든 코드가 맞으면 저장하고 true. */
    fun enable(clientKey: String, username: String, secret: String, code: String?): Boolean

    /** 현재 코드가 맞으면 끄고 true. */
    fun disable(clientKey: String, username: String, code: String?): Boolean

    /** 로그인 두 번째 단계. 연속 실패가 많으면 [com.dochiri.ilsim.auth.domain.exception.LoginLockedException]. */
    fun verify(clientKey: String, username: String, code: String?): Boolean
}
