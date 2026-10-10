package io.github.dochiri0916.buildconvention.integrity

import static org.junit.jupiter.api.Assertions.assertThrows

import groovy.json.JsonSlurper
import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.report.KotlinBuildConventionReport
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceInspector
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinConventionIntegrityTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('실제 검증 태스크가 원본을 분석하고 소비자 보고서 계약을 유지한다')
    void validatesAndWritesReport() {
        Project project = fixture()
        execute(project, 'validateKotlinDomainConventions')
        execute(project, 'validateKotlinArchUnitArchitecture')
        execute(project, 'check')
        execute(project, 'generateKotlinBuildConventionReport')
        Map report = readReport(project)
        assert report.schemaVersion == '1.0'
        assert report.status == 'PASS'
        assert report.checks.architecture == 'PASS'
        assert report.checks.archUnit == 'NOT_APPLIED'
        assert report.checks.conventionIntegrity == 'PASS'
        assert report.kotlin.files == 23
        assert report.kotlin.domainFiles == 18
        assert report.kotlin.violations.isEmpty()
        assert report.coverage == [line: null, branch: null]
        assert report.mutation == [enabled: false, score: null]
    }

    @Test
    @DisplayName('위반 코드는 태스크 예외와 FAIL 보고서로 전파한다')
    void propagatesRuleFailure() {
        Project project = fixture()
        File source = project.file('src/main/kotlin/com/example/saga/order/domain/vo/OrderId.kt')
        source.text = source.text.replace('private constructor(', 'constructor(')
        GradleException failure = assertThrows(GradleException) { execute(project, 'validateKotlinDomainConventions') }
        assert failure.message.contains('[ARCH-DOMAIN-AGGREGATE]')
        assert failure.message.contains('OrderId.kt')
        KotlinBuildConventionReport.write(project)
        assert readReport(project).status == 'FAIL'
        assert readReport(project).checks.architecture == 'FAIL'
    }

    @Test
    @DisplayName('검증 비활성화 onlyIf 태스크 액션 삭제와 check 의존성 제거를 거부한다')
    void rejectsTaskBypasses() {
        List<Closure> changes = [
            { project -> project.tasks.named('validateKotlinDomainConventions').get().enabled = false },
            { project -> project.tasks.named('validateKotlinArchUnitArchitecture').get().enabled = false },
            { project -> project.tasks.named('generateKotlinBuildConventionReport').get().enabled = false },
            { project -> project.tasks.named('check').get().enabled = false },
            { project -> project.tasks.named('validateKotlinDomainConventions').get().onlyIf { false } },
            { project -> project.tasks.named('validateKotlinDomainConventions').get().onlyIf { throw new IllegalStateException('skip') } },
            { project -> project.tasks.named('validateKotlinDomainConventions').get().setActions([]) },
            { project -> project.tasks.named('validateKotlinDomainConventions').get().doLast { } },
            { project ->
                def task = project.tasks.named('validateKotlinDomainConventions').get()
                task.setActions([])
                task.doLast { }
            },
            { project -> project.tasks.named('check').get().setDependsOn([]) },
            { project -> project.tasks.named('check').get().setFinalizedBy([]) },
            { project -> project.tasks.named('validateKotlinDomainConventions').get().setFinalizedBy([]) },
            { project -> project.extensions.getByType(HexagonalConventionExtension).enforceDomainStaticFactoryMethod = false },
            { project -> project.extensions.getByType(HexagonalConventionExtension).enforceCrossContextIdentifierIsolation = false },
            { project -> project.extensions.getByType(HexagonalConventionExtension).enforceDomainEntitySeparation = false },
        ]
        changes.each { change ->
            Project project = fixture()
            change.call(project)
            GradleException failure = assertThrows(GradleException) { execute(project, 'check') }
            assert failure.message.contains('must not be disabled')
            assert readReport(project).status == 'FAIL'
            assert readReport(project).checks.conventionIntegrity == 'FAIL'
        }
    }

    @Test
    @DisplayName('현재 프로젝트의 -x 우회를 거부하고 다른 프로젝트의 제외와 구분한다')
    void rejectsExcludedTasks() {
        Project project = fixture()
        ['validateKotlinDomainConventions', ':validateKotlinDomainConventions', 'validateKotlinArchUnitArchitecture'].each { name ->
            project.gradle.startParameter.excludedTaskNames = [name]
            try {
                GradleException failure = assertThrows(GradleException) { execute(project, 'check') }
                assert failure.message.contains('-x excluded task')
            } finally {
                project.gradle.startParameter.excludedTaskNames = []
            }
        }
        project.gradle.startParameter.excludedTaskNames = [':other:validateKotlinDomainConventions']
        try {
            execute(project, 'check')
        } finally {
            project.gradle.startParameter.excludedTaskNames = []
        }
    }

    @Test
    @DisplayName('사용자 지정 Kotlin 소스 경로와 소스 제외 패턴으로 검사를 숨기지 못한다')
    void collectsCustomAndExcludedSources() {
        Project project = fixture()
        project.pluginManager.apply('java')
        File old = project.file('src/main/kotlin')
        File custom = project.file('custom/kotlin')
        custom.parentFile.mkdirs()
        assert old.renameTo(custom)
        project.sourceSets.main.java.setSrcDirs([custom])
        project.sourceSets.main.java.exclude('**/vo/**')
        assert KotlinSourceInspector.collect(project).size() == 23
        execute(project, 'validateKotlinDomainConventions')
        assert project.extensions.extraProperties.get('kotlinConventionResult').files == 23
        File source = project.file('custom/kotlin/com/example/saga/order/domain/vo/OrderId.kt')
        source.text = source.text.replace('val value: UUID', 'val value: UUID?')
        assertThrows(GradleException) { execute(project, 'validateKotlinDomainConventions') }
    }

    @Test
    @DisplayName('Java 컨벤션과 같이 쓰면 Kotlin 보고서를 덮어쓰지 않는다')
    void writesSeparateReportForMixedModules() {
        Project project = fixture()
        project.pluginManager.apply('io.github.dochiri0916.build-convention.java')
        execute(project, 'validateKotlinDomainConventions')
        KotlinBuildConventionReport.write(project)
        assert project.file('build/reports/build-convention/report-kotlin.json').isFile()
    }

    private Project fixture() {
        File dir = new File(tempDir, UUID.randomUUID().toString())
        dir.mkdirs()
        Project project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.copy { it.from(System.getProperty('kotlinConventionTemplates')); it.into(dir) }
        project.pluginManager.apply('io.github.dochiri0916.build-convention.kotlin')
        return project
    }

    private static void execute(Project project, String name) {
        def task = project.tasks.named(name).get()
        task.actions.each { it.execute(task) }
    }

    private static Map readReport(Project project) {
        new JsonSlurper().parse(project.file('build/reports/build-convention/report.json')) as Map
    }
}
