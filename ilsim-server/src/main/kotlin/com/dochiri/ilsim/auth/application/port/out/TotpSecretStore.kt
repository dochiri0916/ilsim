package com.dochiri.ilsim.auth.application.port.out

/** 계정별 2단계 인증(TOTP) 비밀. 암호화해서 보관한다. */
interface TotpSecretStore {
    fun find(username: String): String?

    fun save(username: String, secret: String)

    fun delete(username: String)
}
