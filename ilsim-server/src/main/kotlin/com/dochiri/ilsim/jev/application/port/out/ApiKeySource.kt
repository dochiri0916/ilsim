package com.dochiri.ilsim.jev.application.port.out

interface ApiKeySource {
    fun apiKey(): String?
}
