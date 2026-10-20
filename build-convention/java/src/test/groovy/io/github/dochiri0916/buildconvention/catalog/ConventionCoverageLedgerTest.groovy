package io.github.dochiri0916.buildconvention.catalog

import groovy.json.JsonSlurper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConventionCoverageLedgerTest {
    private static final File ROOT = new File('..')
    private static final String TEST_EVIDENCE = /(?:core|java|kotlin)\/src\/test\/.+/

    @Test
    @DisplayName('관리표는 문서의 요구사항과 실재하는 구현·테스트 근거 및 상태 의미를 연결한다')
    void links_current_documents_implementations_and_evidence() {
        Map ledger = new JsonSlurper().parse(new File(ROOT, 'docs/conventions/coverage.json')) as Map
        List requirements = ledger.requirements
        assert requirements.size() == 143
        assert requirements.collect { it.id }.unique().size() == requirements.size()
        assert ledger.statusMeaning.keySet() == ['pending', 'partial', 'review', 'verified'] as Set
        requirements.each { requirement ->
            assert requirement.status in ledger.statusMeaning.keySet()
            assert requirement.status != 'pending'
            assert new File(ROOT, 'docs/conventions/' + requirement.source).text.contains(requirement.requirement)
            assert requirement.evidence
            requirement.implementation.each { assert new File(ROOT, it).isFile() }
            requirement.evidence.findAll { it ==~ TEST_EVIDENCE }.each { assert new File(ROOT, it).isFile() }
            if (requirement.status in ['partial', 'verified']) {
                assert requirement.implementation
                assert requirement.evidence.any { it ==~ TEST_EVIDENCE }
            }
            if (requirement.source == 'database-migration-conventions.md') assert requirement.status == 'review'
        }
    }
}
