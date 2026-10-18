package com.dochiri.ilsim.auth.application.port.`in`

interface RememberMeUseCase {
    /** 새 자동 로그인 토큰의 쿠키 값. */
    fun issue(username: String): String

    /** 쿠키가 유효하면 아이디. 아니면 null. */
    fun resolve(cookie: String?): String?

    fun revoke(cookie: String?)

    /** 비밀번호를 바꾸는 등 모든 기기의 자동 로그인을 끊는다. */
    fun revokeAll(username: String)
}
