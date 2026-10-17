package com.dochiri.ilsim.secret.application.port.out

/** 이름을 묶어(추가 인증 데이터) 암호화한다. 다른 이름의 행으로 옮겨 붙이면 복호화가 실패한다. */
interface SecretEncryptor {
    fun encrypt(name: String, plain: String): String

    /** 마스터 키가 바뀌었거나 데이터가 손상되었으면 [IllegalStateException]. */
    fun decrypt(name: String, stored: String): String
}
