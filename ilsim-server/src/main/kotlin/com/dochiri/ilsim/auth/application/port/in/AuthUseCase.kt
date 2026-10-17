package com.dochiri.ilsim.auth.application.port.`in`

interface AuthUseCase {
    /** 계정이 하나도 없으면 true (최초 설정 화면을 보여 준다). */
    fun needsSetup(): Boolean

    /** 계정이 하나도 없을 때만 첫 계정을 만든다. */
    fun setup(username: String?, password: String?): String

    /** 맞으면 아이디를, 틀리면 null. 연속 실패가 많으면 [com.dochiri.ilsim.auth.domain.exception.LoginLockedException]. */
    fun login(clientKey: String, username: String?, password: String?): String?

    /** 로그인한 사용자가 계정을 더 만든다. */
    fun createAccount(username: String?, password: String?): String

    fun usernames(): List<String>
}
