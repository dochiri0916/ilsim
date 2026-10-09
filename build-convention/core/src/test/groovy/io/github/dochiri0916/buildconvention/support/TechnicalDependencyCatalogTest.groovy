package io.github.dochiri0916.buildconvention.support

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TechnicalDependencyCatalogTest {
    @Test
    @DisplayName('Application에서 기술 패키지는 금지하고 허용된 Spring 타입과 일반 타입은 허용한다')
    void forbids_technical_types_except_allowed_spring_types() {
        // given
        List<String> forbidden = ['org.springframework.web.client.RestTemplate', 'jakarta.persistence.EntityManager',
                                  'java.io.File', 'java.sql.Connection']
        List<String> allowed = ['org.springframework.stereotype.Service',
                                'org.springframework.transaction.annotation.Transactional', 'java.util.List']

        // when
        List<Boolean> forbiddenResults = forbidden.collect { TechnicalDependencyCatalog.isForbiddenInApplication(it) }
        List<Boolean> allowedResults = allowed.collect { TechnicalDependencyCatalog.isForbiddenInApplication(it) }

        // then
        assert forbiddenResults.every()
        assert !allowedResults.any()
    }
}
