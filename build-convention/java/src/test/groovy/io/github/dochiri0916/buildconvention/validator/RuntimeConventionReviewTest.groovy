package io.github.dochiri0916.buildconvention.validator

import groovy.json.JsonOutput
import io.github.dochiri0916.buildconvention.plugin.BuildConventionReport
import org.gradle.api.internal.tasks.TaskExecutionOutcome
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RuntimeConventionReviewTest {
    @TempDir File directory

    @Test
    @DisplayName('실행 의미 검증은 누락된 시나리오와 함께 REVIEW로 보고한다')
    void reports_runtime_obligations_without_false_pass() {
        // given
        def project = project()
        write('src/main/java/example/book/adapter/out/persistence/BookQueryRepository.java', '''
            package example.book.adapter.out.persistence;
            public class BookQueryRepository { public void search() { query.offset(0).limit(10); } }
        ''')
        // when
        def review = RuntimeConventionReview.report(project)
        // then
        assert review.status == 'REVIEW'
        assert review.requirements.collect { it.id }.toSet() == ['QUERY-RESULTS', 'QUERY-PAGINATION'] as Set
        assert review.requirements.every { it.status == 'REVIEW' && it.evidence.every { it.status == 'MISSING' } }
        BuildConventionReport.write(project)
        def report = new groovy.json.JsonSlurper().parse(new File(directory, 'build/reports/build-convention/report.json'))
        assert report.schemaVersion == '1.0'
        assert report.conventionReview == review
    }

    @Test
    @DisplayName('현재 성공한 JUnit 테스트만 실행 근거로 연결하며 의미 검토는 REVIEW로 유지한다')
    void links_successful_tests_and_rejects_stale_or_skipped_evidence() {
        // given
        def project = project()
        String source = 'src/main/java/example/book/adapter/in/web/BookController.java'
        write(source, 'package example.book.adapter.in.web; public class BookController {}')
        write('.build-convention/runtime-evidence.json', JsonOutput.toJson([schemaVersion: 1, items: [[
            id: 'WEB-CONVERSION', source: source, scenario: 'request-to-usecase-contract',
            testClass: 'example.BookTest', testName: 'converts_request()', review: '실제 HTTP 요청과 Command 필드를 검증한다'
        ]]]))
        String xml = '<testsuite><testcase classname="example.BookTest" name="converts_request()"/></testsuite>'
        write('build/test-results/test/TEST-example.BookTest.xml', xml)
        def task = project.tasks.getByName('test')
        // when & then
        assert evidence(project).status == 'MISSING'
        task.state.outcome = TaskExecutionOutcome.EXECUTED
        assert evidence(project).status == 'EXECUTED'
        assert RuntimeConventionReview.report(project).status == 'REVIEW'
        write('build/test-results/test/TEST-example.BookTest.xml', xml.replace('/>', '><skipped/></testcase>'))
        assert evidence(project).status == 'MISSING'
        write('build/test-results/test/TEST-example.BookTest.xml', xml.replace('/>', '><failure/></testcase>'))
        assert evidence(project).status == 'MISSING'
        write('build/test-results/test/TEST-example.BookTest.xml', xml)
        task.state.outcome = new RuntimeException('test failed')
        assert evidence(project).status == 'MISSING'
    }

    @Test
    @DisplayName('가짜 결과 경로와 중복·깨진 JSON 및 XML 외부 엔티티를 성공 근거로 인정하지 않는다')
    void rejects_invalid_evidence_and_external_xml() {
        // given
        def project = project()
        write('src/main/java/example/book/adapter/in/web/BookController.java', 'package example.book.adapter.in.web; public class BookController {}')
        write('.build-convention/runtime-evidence.json', '{broken')
        // when & then
        assert RuntimeConventionReview.report(project).errors
        write('.build-convention/runtime-evidence.json', JsonOutput.toJson([schemaVersion: 1, items: [[id: 'fake']]]))
        assert RuntimeConventionReview.report(project).errors
        write('.build-convention/runtime-evidence.json', JsonOutput.toJson([schemaVersion: 1, items: []]))
        write('build/test-results/test/TEST-fake.xml', '<!DOCTYPE testsuite [<!ENTITY leak SYSTEM "file:///does-not-exist">]><testsuite>&leak;</testsuite>')
        project.tasks.getByName('test').state.outcome = TaskExecutionOutcome.EXECUTED
        assert RuntimeConventionReview.report(project).requirements.every { it.evidence.every { it.status == 'MISSING' } }
    }

    @Test
    @DisplayName('기술이 없는 프로젝트는 NOT_APPLIED이며 파싱 실패는 미적용으로 숨기지 않는다')
    void distinguishes_not_applied_from_unknown_sources() {
        // given
        def project = project()
        // when & then
        assert RuntimeConventionReview.report(project).status == 'NOT_APPLIED'
        write('src/main/java/example/Broken.java', 'package example; public class Broken { ??? }')
        assert RuntimeConventionReview.report(project).status == 'REVIEW'
        assert RuntimeConventionReview.report(project).errors
    }

    @Test
    @DisplayName('메시징·동일 Port의 기본 safe 구현은 해당 실행 시나리오를 보고한다')
    void discovers_messaging_and_alternative_adapter_scenarios() {
        // given
        def project = project()
        write('src/main/java/example/order/adapter/out/persistence/BasicAdapter.java', 'package example.order.adapter.out.persistence; public class BasicAdapter implements OrderPort {}')
        write('src/main/java/example/order/adapter/out/persistence/SafeAdapter.java', 'package example.order.adapter.out.persistence; public class SafeAdapter implements OrderPort {}')
        write('src/main/java/example/order/adapter/out/persistence/outbox/OrderOutboxEntry.java', 'package example.order.adapter.out.persistence.outbox; @Entity public class OrderOutboxEntry {}')
        write('src/main/java/example/order/adapter/out/persistence/inbox/OrderInboxEntry.java', 'package example.order.adapter.out.persistence.inbox; @Entity public class OrderInboxEntry {}')
        write('src/main/java/example/order/application/port/out/event/OrderChanged.java', 'package example.order.application.port.out.event; public record OrderChanged(String id) {}')
        // when
        def review = RuntimeConventionReview.report(project)
        // then
        assert review.requirements.count { it.id == 'PERSISTENCE-CONTRACT' } == 2
        assert review.requirements.collect { it.id }.containsAll(['OUTBOX-DELIVERY', 'INBOX-DELIVERY', 'EVENT-CONTRACT'])
    }

    private def project() {
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        project
    }
    private def evidence(def project) { RuntimeConventionReview.report(project).requirements.first().evidence.first() }
    private void write(String path, String text) {
        File target = new File(directory, path)
        target.parentFile.mkdirs()
        target.text = text
    }
}
