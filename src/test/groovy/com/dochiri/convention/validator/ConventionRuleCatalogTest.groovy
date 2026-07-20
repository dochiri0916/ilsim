package com.dochiri.convention.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConventionRuleCatalogTest {

    @Test
    @DisplayName('위반 메시지에 안정적인 rule ID를 붙이고 이미 붙은 ID는 중복하지 않는다')
    void assignsStableRuleIdWithoutDuplicatingIt() {
        // given
        List<String> violations = [
                'domain aggregate state must use private final fields',
                '[ARCH-EXCEPTION-CONTRACT] existing violation',
                'unclassified convention failure'
        ]

        // when
        List<String> identified = ConventionRuleCatalog.withRuleIds(violations)

        // then
        assert identified[0].startsWith('[ARCH-DOMAIN-AGGREGATE] ')
        assert identified[1] == violations[1]
        assert identified[2] == '[ARCH-GENERAL] unclassified convention failure'
        assert ConventionRuleCatalog.ruleId('ProblemDetail handler must return ErrorCode detail') == 'ARCH-EXCEPTION-CONTRACT'
    }
}
