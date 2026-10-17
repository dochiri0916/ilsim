package com.dochiri.ilsim.jev.configuration

import com.dochiri.ilsim.jev.adapter.out.secret.SecretApiKeySource
import com.dochiri.ilsim.jev.adapter.out.typesafe.TypeSafeJevGateway
import com.dochiri.ilsim.jev.api.JevGateway
import com.dochiri.ilsim.jev.application.port.out.ApiKeySource
import com.dochiri.ilsim.secret.api.SecretReader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class JevConfiguration {

    @Bean
    fun jevApiKeySource(secrets: SecretReader): ApiKeySource = SecretApiKeySource(secrets)

    @Bean(destroyMethod = "close")
    fun jevGateway(keys: ApiKeySource): JevGateway = TypeSafeJevGateway(keys)
}
