package io.github.dochiri0916.buildconvention.validator

import groovy.json.JsonSlurper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConventionCoverageLedgerTest {
    @Test
    @DisplayName('관리표는 문서의 요구사항과 실재하는 구현·테스트 근거 및 상태 의미를 연결한다')
    void links_current_documents_implementations_and_evidence() {
        // given
        Map ledger = new JsonSlurper().parse(new File('docs/conventions/coverage.json')) as Map
        // when
        List requirements = ledger.requirements
        // then
        assert requirements.size() == 140
        assert requirements.collect { it.id }.unique().size() == requirements.size()
        assert ledger.statusMeaning.keySet() == ['pending', 'partial', 'review', 'verified'] as Set
        requirements.each { requirement ->
            assert requirement.status in ledger.statusMeaning.keySet()
            assert requirement.status != 'pending'
            assert new File('docs/conventions/' + requirement.source).text.contains(requirement.requirement)
            assert requirement.evidence
            requirement.implementation.each { assert new File(it).isFile() }
            requirement.evidence.findAll { it.startsWith('src/test/') }.each { assert new File(it).isFile() }
            if (requirement.status in ['partial', 'verified']) {
                assert requirement.implementation
                assert requirement.evidence.any { it.startsWith('src/test/') }
            }
            if (requirement.source == 'database-migration-conventions.md') assert requirement.status == 'review'
        }
    }
}
