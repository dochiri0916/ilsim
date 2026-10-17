package com.dochiri.ilsim.jev.application.port.out

/** JEV API 키를 어디서 가져오는지. 키가 바뀌면 다음 호출부터 새 값을 돌려준다. */
interface ApiKeySource {
    fun apiKey(): String?
}
