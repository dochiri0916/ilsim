package io.github.dochiri0916.buildconvention.catalog

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TechnicalDependencyCatalogTest {
    @Test
    @DisplayName('Application에서 기술 패키지는 금지하고 허용된 Spring 타입과 일반 타입은 허용한다')
    void forbids_technical_types_except_allowed_spring_types() {
        // given
        List<String> forbidden = ['org.springframework.web.client.RestTemplate', 'jakarta.persistence.EntityManager',
                                  'java.io.File', 'java.sql.Connection', 'tools.jackson.databind.ObjectMapper',
                                  'io.micrometer.core.instrument.MeterRegistry']
        List<String> allowed = ['org.springframework.stereotype.Service',
                                'org.springframework.transaction.annotation.Transactional', 'java.util.List']

        // when
        List<Boolean> forbiddenResults = forbidden.collect { TechnicalDependencyCatalog.isForbiddenInApplication(it) }
        List<Boolean> allowedResults = allowed.collect { TechnicalDependencyCatalog.isForbiddenInApplication(it) }

        // then
        assert forbiddenResults.every()
        assert !allowedResults.any()
    }

    @Test
    @DisplayName('Domain은 프로젝트 밖에서 표준 라이브러리와 명시한 패키지만 사용할 수 있다')
    void allows_only_listed_external_packages_in_domain() {
        // given
        List<String> allowed = ['java.util.UUID', 'kotlin.jvm.internal.Intrinsics', 'org.jetbrains.annotations.NotNull',
                                'com.github.f4b6a3.uuid.UuidCreator']
        List<String> rejected = ['org.apache.commons.lang3.StringUtils', 'javax.sql.DataSource', 'kotlinx.coroutines.Job',
                                 'org.jetbrains.exposed.sql.Table']

        // when
        List<Boolean> allowedResults = allowed.collect { TechnicalDependencyCatalog.isAllowedExternalInDomain(it) }
        List<Boolean> rejectedResults = rejected.collect { TechnicalDependencyCatalog.isAllowedExternalInDomain(it) }

        // then
        assert allowedResults.every()
        assert !rejectedResults.any()
    }
}
