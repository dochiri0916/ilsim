package io.github.dochiri0916.buildconvention.plugin

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinConventionFunctionalTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('실제 Gradle check가 원본을 통과시키고 규칙 위반 모듈을 실패 보고한다')
    void failsInvalidDomainsAndWritesReports() {
        Map changes = [
            original: null,
            constructor: ['domain/vo/OrderId.kt', 'private constructor(', 'constructor('],
            uuid: ['domain/vo/OrderId.kt', 'val value: UUID', 'val value: UUID?'],
            uuidGuard: ['adapter/in/UuidV7.kt', 'UUID_V7 = 7', 'UUID_V7 = 4'],
            uuidVariant: ['adapter/in/UuidV7.kt', ' || ', ' && '],
            uuidGenerator: ['adapter/out/identifier/UuidV7OrderIdGenerator.kt', 'UuidCreator.getTimeOrderedEpoch()', 'java.util.UUID.randomUUID()'],
            uuidPort: ['application/port/out/OrderIdGenerator.kt', 'fun generate()', 'fun create()'],
            quantity: ['domain/vo/OrderQuantity.kt', 'value < 1', 'value < 0'],
            collection: ['domain/OrderItems.kt', 'java.util.List.copyOf(values)', 'values.toList()'],
            equality: ['domain/Order.kt', 'id.hashCode()', 'status.hashCode()'],
            mutable: ['domain/Order.kt', 'val status:', 'var status:'],
        ]
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'kotlin-domains'\n" +
                changes.keySet().collect { "include '${it}'" }.join('\n') + '\n'
        new File(tempDir, 'build.gradle').text = '''
            plugins { id 'io.github.dochiri0916.build-convention.kotlin' apply false }
            subprojects { apply plugin: 'io.github.dochiri0916.build-convention.kotlin' }
        '''.stripIndent()
        changes.each { name, change ->
            File module = new File(tempDir, name)
            copyTemplate(module)
            if (change != null) {
                File source = new File(module, "src/main/kotlin/com/example/saga/order/${change[0]}")
                assert source.text.contains(change[1])
                source.text = source.text.replace(change[1], change[2])
            }
        }
        def result = runner('check', '--continue').buildAndFail()
        assert result.task(':original:check').outcome == TaskOutcome.SUCCESS
        Map original = report(new File(tempDir, 'original'))
        assert original.status == 'PASS'
        assert original.checks.conventionIntegrity == 'PASS'
        assert original.kotlin.domainFiles == 18
        changes.keySet().findAll { it != 'original' }.each { name ->
            assert result.task(":${name}:validateKotlinDomainConventions").outcome == TaskOutcome.FAILED
            Map failed = report(new File(tempDir, name))
            assert failed.status == 'FAIL'
            assert failed.checks.architecture == 'FAIL'
            assert failed.checks.conventionIntegrity == 'PASS'
            assert !failed.kotlin.violations.isEmpty()
        }
    }

    @Test
    @DisplayName('Kotlin JVM 플러그인과 공존하고 늦게 적용해도 check와 ArchUnit을 실행한다')
    void validatesCompiledKotlinWithLateApplication() {
        copyTemplate(tempDir)
        File runtimeTest = new File(tempDir, 'src/test/kotlin/com/example/saga/order/IdentifierBoundaryTest.kt')
        runtimeTest.parentFile.mkdirs()
        runtimeTest.text = '''
            package com.example.saga.order
            import com.example.saga.order.adapter.`in`.InvalidUuidV7Exception
            import com.example.saga.order.adapter.`in`.requireV7
            import com.example.saga.order.adapter.out.identifier.UuidV7OrderIdGenerator
            import com.example.saga.order.adapter.out.identifier.UuidV7OrderItemIdGenerator
            import com.example.saga.order.domain.vo.OrderId
            import org.junit.jupiter.api.Assertions.*
            import org.junit.jupiter.api.Test
            import java.util.UUID

            class IdentifierBoundaryTest {
                @Test
                fun validatesAllUuidVersionsAndVariantsAtInput() {
                    for (version in 0..15) {
                        for (bits in listOf(0L, Long.MIN_VALUE, -4611686018427387904L, -2305843009213693952L)) {
                            val uuid = UUID(version.toLong() shl 12, bits)
                            if (uuid.version() == 7 && uuid.variant() == 2) {
                                assertSame(uuid, uuid.requireV7("orderId"))
                            } else {
                                val failure = assertThrows(InvalidUuidV7Exception::class.java) { uuid.requireV7("orderId") }
                                assertEquals("orderId", failure.field)
                                assertEquals(uuid, failure.value)
                            }
                        }
                    }
                }

                @Test
                fun generatorsIssueV7AndDomainOnlyWrapsTheSuppliedUuid() {
                    val id = UuidV7OrderIdGenerator().generate()
                    val itemId = UuidV7OrderItemIdGenerator().generate()
                    for (uuid in listOf(id.value, itemId.value)) {
                        assertEquals(7, uuid.version())
                        assertEquals(2, uuid.variant())
                    }
                    assertNotEquals(id, UuidV7OrderIdGenerator().generate())
                    val supplied = UUID.randomUUID()
                    assertSame(supplied, OrderId.of(supplied).value)
                    assertEquals(supplied.toString(), OrderId.of(supplied).toString())
                    assertEquals(OrderId.of(supplied), OrderId.of(supplied))
                }
            }
        '''.stripIndent()
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'compiled-kotlin-domain'\n"
        new File(tempDir, 'build.gradle').text = '''
            plugins {
                id 'org.jetbrains.kotlin.jvm' version '2.3.21'
                id 'io.github.dochiri0916.build-convention.kotlin' apply false
            }
            repositories { mavenCentral() }
            dependencies {
                implementation 'com.github.f4b6a3:uuid-creator:6.1.0'
                implementation 'org.springframework:spring-context:6.2.12'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.0'
            }
            kotlin { jvmToolchain(21) }
            test { useJUnitPlatform() }
            pluginManager.withPlugin('java') {
                afterEvaluate { pluginManager.apply('io.github.dochiri0916.build-convention.kotlin') }
            }
        '''.stripIndent()
        def result = runner('check').build()
        assert result.task(':compileKotlin').outcome == TaskOutcome.SUCCESS
        assert result.task(':validateKotlinDomainConventions').outcome == TaskOutcome.SUCCESS
        assert result.task(':validateKotlinArchUnitArchitecture').outcome == TaskOutcome.SUCCESS
        assert result.task(':check').outcome == TaskOutcome.SUCCESS
        assert result.task(':test').outcome == TaskOutcome.SUCCESS
        Map value = report(tempDir)
        assert value.status == 'PASS'
        assert value.checks.archUnit == 'PASS'
        assert value.checks.compilation == 'PASS'
        assert value.checks.conventionIntegrity == 'PASS'
        assert value.checks.tests == 'PASS'
    }

    @Test
    @DisplayName('실제 Gradle -x와 onlyIf 우회가 check 실행 전에 실패한다')
    void rejectsRealTaskBypasses() {
        copyTemplate(tempDir)
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'kotlin-bypass'\n"
        File build = new File(tempDir, 'build.gradle')
        String baseline = "plugins { id 'io.github.dochiri0916.build-convention.kotlin' }\n"
        build.text = baseline
        def excluded = runner('check', '-x', 'validateKotlinDomainConventions').buildAndFail()
        assert excluded.output.contains('-x excluded task validateKotlinDomainConventions')
        assert report(tempDir).checks.conventionIntegrity == 'FAIL'
        build.text = baseline + "tasks.named('validateKotlinDomainConventions') { onlyIf { false } }\n"
        def skipped = runner('check').buildAndFail()
        assert skipped.output.contains('onlyIf-skipped task validateKotlinDomainConventions')
        assert report(tempDir).checks.conventionIntegrity == 'FAIL'
    }

    private static void copyTemplate(File destination) {
        File source = new File(System.getProperty('kotlinConventionTemplates'))
        source.eachFileRecurse { file ->
            if (file.isFile()) {
                File target = new File(destination, source.toPath().relativize(file.toPath()).toString())
                target.parentFile.mkdirs()
                Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private GradleRunner runner(String... arguments) {
        GradleRunner.create().withProjectDir(tempDir).withPluginClasspath()
                .withArguments(arguments.toList() + ['--stacktrace', '--max-workers=2'])
                .forwardOutput()
    }

    private static Map report(File directory) {
        new JsonSlurper().parse(new File(directory, 'build/reports/build-convention/report.json')) as Map
    }
}
