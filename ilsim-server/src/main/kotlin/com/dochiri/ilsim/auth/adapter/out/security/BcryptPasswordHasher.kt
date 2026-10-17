package com.dochiri.ilsim.auth.adapter.out.security

import com.dochiri.ilsim.auth.application.port.out.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class BcryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder()

    override fun hash(password: String): String = requireNotNull(encoder.encode(password))

    override fun matches(password: String, hash: String): Boolean = encoder.matches(password, hash)
}
