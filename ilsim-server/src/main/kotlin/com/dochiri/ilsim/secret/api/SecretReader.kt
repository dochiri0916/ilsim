package com.dochiri.ilsim.secret.api

/** 다른 모듈이 저장된 비밀 값을 읽는 공개 계약. 저장소를 쓸 수 없으면 예외를 던진다. */
interface SecretReader {
    fun get(name: String): String?
}

/** 다른 모듈이 비밀 값을 저장·삭제하는 공개 계약. 값은 암호화해 보관한다. */
interface SecretWriter {
    fun put(name: String, plain: String?)

    fun delete(name: String)
}

object SecretNames {
    const val JEV_API_KEY: String = "jev-api-key"

    fun totp(username: String): String = "totp:$username"
}
