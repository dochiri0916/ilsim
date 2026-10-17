package com.dochiri.ilsim.secret.application.port.`in`

import com.dochiri.ilsim.secret.domain.SecretStatus

interface ManageSecretUseCase {
    fun put(name: String, plain: String?)

    fun delete(name: String)

    fun status(name: String): SecretStatus
}
