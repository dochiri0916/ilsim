package com.dochiri.ilsim.secret.adapter.out.config

import com.dochiri.ilsim.secret.adapter.out.crypto.AesGcmSecretEncryptor
import com.dochiri.ilsim.secret.adapter.out.persistence.JpaSecretRepository
import com.dochiri.ilsim.secret.adapter.out.persistence.SecretJpaRepository
import com.dochiri.ilsim.secret.application.port.out.SecretEncryptor
import com.dochiri.ilsim.secret.application.port.out.SecretRepository
import com.dochiri.ilsim.secret.application.usecase.SecretService
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class SecretConfiguration {

    @Bean
    fun secretEncryptor(): SecretEncryptor = AesGcmSecretEncryptor.fromEnvironment()

    @Bean
    fun secretRepository(jdbc: JdbcTemplate, secrets: SecretJpaRepository): SecretRepository = JpaSecretRepository(jdbc, secrets)

    @Bean
    fun secretService(repository: SecretRepository, encryptor: SecretEncryptor, clock: Clock): SecretService =
        SecretService(repository, encryptor, clock)
}
