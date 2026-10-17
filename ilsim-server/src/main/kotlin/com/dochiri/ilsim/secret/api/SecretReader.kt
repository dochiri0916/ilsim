package com.dochiri.ilsim.secret.api

/** 다른 모듈이 저장된 비밀 값을 읽는 공개 계약. 저장소를 쓸 수 없으면 예외를 던진다. */
interface SecretReader {
    fun get(name: String): String?
}

object SecretNames {
    const val JEV_API_KEY: String = "jev-api-key"
}
