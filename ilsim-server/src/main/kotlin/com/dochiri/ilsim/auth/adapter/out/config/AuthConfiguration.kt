package com.dochiri.ilsim.auth.adapter.out.config

import com.dochiri.ilsim.auth.adapter.out.persistence.AccountJpaRepository
import com.dochiri.ilsim.auth.adapter.out.persistence.JpaAccountRepository
import com.dochiri.ilsim.auth.adapter.out.persistence.JpaLoginTokenRepository
import com.dochiri.ilsim.auth.adapter.out.persistence.LoginTokenJpaRepository
import com.dochiri.ilsim.auth.adapter.out.secret.SecretTotpStore
import com.dochiri.ilsim.auth.adapter.out.security.BcryptPasswordHasher
import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.port.`in`.RememberMeUseCase
import com.dochiri.ilsim.auth.application.port.`in`.TwoFactorUseCase
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.port.out.PasswordHasher
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.application.usecase.RememberMeService
import com.dochiri.ilsim.auth.application.usecase.TwoFactorService
import com.dochiri.ilsim.secret.api.SecretReader
import com.dochiri.ilsim.secret.api.SecretWriter
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    @Bean
    fun accountRepository(jdbc: JdbcTemplate, accounts: AccountJpaRepository): AccountRepository = JpaAccountRepository(jdbc, accounts)

    @Bean
    fun passwordHasher(): PasswordHasher = BcryptPasswordHasher()

    @Bean
    fun authUseCase(repository: AccountRepository, hasher: PasswordHasher, clock: Clock): AuthUseCase =
        AuthService(repository, hasher, clock)

    @Bean
    fun rememberMeUseCase(jdbc: JdbcTemplate, tokens: LoginTokenJpaRepository, clock: Clock): RememberMeUseCase =
        RememberMeService(JpaLoginTokenRepository(jdbc, tokens), clock)

    @Bean
    fun twoFactorUseCase(reader: SecretReader, writer: SecretWriter, clock: Clock): TwoFactorUseCase =
        TwoFactorService(SecretTotpStore(reader, writer), clock)
}
