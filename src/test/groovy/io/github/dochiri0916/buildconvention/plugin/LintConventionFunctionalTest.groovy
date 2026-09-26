package io.github.dochiri0916.buildconvention.plugin

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import javax.tools.ToolProvider
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class LintConventionFunctionalTest {

    @TempDir
    File tempDir

    @Test
    void writesPassingReportFromCheck() {
        writeReportFixture()

        BuildResult result = runner('check', '-PchangedCoverageBaseRef=HEAD').build()
        Map report = readReport()

        assert result.task(':check').outcome == TaskOutcome.SUCCESS
        assert report.schemaVersion == '1.0'
        assert report.pluginVersion == '1.0.1'
        assert report.status == 'PASS'
        assert report.checks.architecture == 'PASS'
        assert report.checks.mutationGate == 'NOT_APPLIED'
        assert report.mutation == [enabled: false, score: null]
        assert report.coverage.line == 1
        assert report.coverage.branch == null
    }

    @Test
    void excludesOnlyAnnotatedSpringBootClassFromCoverage() {
        writeReportFixture()
        writeJava('org/springframework/boot/autoconfigure/SpringBootApplication.java', '''
                package org.springframework.boot.autoconfigure;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.CLASS)
                public @interface SpringBootApplication {}
                ''')
        writeJava('com/example/BootApplication.java', '''
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class BootApplication {
                    public static void main(String[] args) { System.out.println("boot"); }
                }
                ''')
        writeJava('com/example/application/service/PaymentService.java', '''
                package com.example.application.service;
                public class PaymentService {
                    public int value() { return 7; }
                }
                ''')
        File test = new File(tempDir, 'src/test/java/com/example/GreetingApplicationTest.java')
        test.text = test.text.replace('import static org.junit.jupiter.api.Assertions.assertEquals;',
                'import static org.junit.jupiter.api.Assertions.assertEquals;\nimport com.example.application.service.PaymentService;')
                .replace('assertEquals(42, value, "값이 42다");',
                        'assertEquals(42, value, "값이 42다");\n        assertEquals(7, new PaymentService().value());')

        BuildResult passing = runner('jacocoTestReport', 'jacocoTestCoverageVerification').build()
        assert passing.task(':jacocoTestCoverageVerification').outcome == TaskOutcome.SUCCESS
        String xml = new File(tempDir, 'build/reports/jacoco/test/jacocoTestReport.xml').text
        assert !xml.contains('name="com/example/BootApplication"')
        assert xml.contains('name="com/example/GreetingApplication"')
        assert xml.contains('name="com/example/application/service/PaymentService"')

        test.text = test.text.replace('        assertEquals(7, new PaymentService().value());\n', '')
        BuildResult failing = runner('jacocoTestCoverageVerification').buildAndFail()
        assert failing.output.contains('Rule violated')
    }

    @Test
    void passesCheckAndReportWithUntestedBootMainAndTestedProductionClasses() {
        writeReportFixture()
        File annotationSource = new File(tempDir,
                'annotation-src/org/springframework/boot/autoconfigure/SpringBootApplication.java')
        annotationSource.parentFile.mkdirs()
        annotationSource.text = '''
                package org.springframework.boot.autoconfigure;
                public @interface SpringBootApplication {}
                '''.stripIndent().trim()
        File componentSource = new File(tempDir,
                'annotation-src/org/springframework/stereotype/Component.java')
        componentSource.parentFile.mkdirs()
        componentSource.text = '''
                package org.springframework.stereotype;
                public @interface Component {}
                '''.stripIndent().trim()
        File annotationClasses = new File(tempDir, 'annotation-classes')
        annotationClasses.mkdirs()
        assert ToolProvider.systemJavaCompiler.run(null, null, null,
                '-d', annotationClasses.absolutePath, annotationSource.absolutePath,
                componentSource.absolutePath) == 0
        File annotationJar = new File(tempDir, 'libs/spring-boot-annotation.jar')
        annotationJar.parentFile.mkdirs()
        annotationJar.withOutputStream { output ->
            new JarOutputStream(output).withCloseable { jar ->
                ['org/springframework/boot/autoconfigure/SpringBootApplication.class',
                 'org/springframework/stereotype/Component.class'].each { String path ->
                    jar.putNextEntry(new JarEntry(path))
                    jar.write(new File(annotationClasses, path).bytes)
                    jar.closeEntry()
                }
            }
        }
        new File(tempDir, 'build.gradle') << "\ndependencies { implementation files('libs/spring-boot-annotation.jar') }\n"
        writeJava('com/example/ValidationLabApplication.java', '''
                package com.example;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class ValidationLabApplication { public static void main(String[] args) {} }
                ''')
        writeJava('com/example/order/adapter/out/external/GetValidationService.java', '''
                package com.example.order.adapter.out.external;
                @org.springframework.stereotype.Component
                public class GetValidationService { public int value() { return 7; } }
                ''')
        writeJava('com/example/order/adapter/in/web/ValidationController.java', '''
                package com.example.order.adapter.in.web;
                public class ValidationController { public int value() { return 8; } }
                ''')
        File test = new File(tempDir, 'src/test/java/com/example/GreetingApplicationTest.java')
        test.text = test.text.replace('import static org.junit.jupiter.api.Assertions.assertEquals;', '''
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import com.example.order.adapter.out.external.GetValidationService;
                import com.example.order.adapter.in.web.ValidationController;
                '''.stripIndent().trim())
                .replace('assertEquals(42, value, "값이 42다");', '''
                assertEquals(42, value, "값이 42다");
                assertEquals(7, new GetValidationService().value(), "서비스 값이다");
                assertEquals(8, new ValidationController().value(), "컨트롤러 값이다");
                '''.stripIndent().trim())
        ['git', '-C', tempDir.absolutePath, 'add', '.'].execute().waitFor()
        ['git', '-C', tempDir.absolutePath, '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
         'commit', '-qm', 'fixture coverage classes'].execute().waitFor()

        BuildResult result = runner('check', '-PchangedCoverageBaseRef=HEAD').build()
        assert result.task(':jacocoTestCoverageVerification').outcome == TaskOutcome.SUCCESS
        assert readReport().status == 'PASS'
        String xml = new File(tempDir, 'build/reports/jacoco/test/jacocoTestReport.xml').text
        assert !xml.contains('name="com/example/ValidationLabApplication"')
        assert xml.contains('name="com/example/order/adapter/out/external/GetValidationService"')
        assert xml.contains('name="com/example/order/adapter/in/web/ValidationController"')
    }


    @Test
    void writesFailureReportWithoutWeakeningCheck() {
        writeReportFixture()
        File test = new File(tempDir, 'src/test/java/com/example/GreetingApplicationTest.java')
        test.text = test.text.replace('import org.junit.jupiter.api.DisplayName;\n', '')
                .replace('    @DisplayName("값을 반환한다")\n', '')

        BuildResult result = runner('check', '-PchangedCoverageBaseRef=HEAD').buildAndFail()
        Map report = readReport()

        assert result.output.contains('Architecture convention violations')
        assert report.status == 'FAIL'
        assert report.checks.architecture == 'FAIL'
    }

    private void writeReportFixture() {
        writeBuildFiles()
        new File(tempDir, 'build.gradle') << '''

                repositories { mavenCentral() }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                hexagonalConvention { basePackage = 'com.example' }
                '''.stripIndent()
        writeJava('com/example/GreetingApplication.java', '''
                package com.example;

                public class GreetingApplication {
                    public int value() { return 42; }
                }
                ''')
        File test = new File(tempDir, 'src/test/java/com/example/GreetingApplicationTest.java')
        test.parentFile.mkdirs()
        test.text = '''
                package com.example;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class GreetingApplicationTest {
                    @Test
                    @DisplayName("값을 반환한다")
                    void returnsValue() {
                        // given
                        GreetingApplication application = new GreetingApplication();
                        // when
                        int value = application.value();
                        // then
                        assertEquals(42, value, "값이 42다");
                    }
                }
                '''.stripIndent().trim() + System.lineSeparator()
        ['git', 'init', '-q', tempDir.absolutePath].execute().waitFor()
        ['git', '-C', tempDir.absolutePath, 'add', '.'].execute().waitFor()
        ['git', '-C', tempDir.absolutePath, '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
         'commit', '-qm', 'fixture'].execute().waitFor()
    }

    private Map readReport() {
        File file = new File(tempDir, 'build/reports/build-convention/report.json')
        assert file.isFile()
        new JsonSlurper().parse(file) as Map
    }

    @Test
    @DisplayName('실제 Gradle 프로젝트에서 아키텍처 검증 태스크를 실행한다')
    void runsArchitectureConventionTaskInRealGradleBuild() {
        // given
        writeBuildFiles()
        writeJava('com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')

        // when
        BuildResult result = runner('validateArchitectureConventions').build()

        // then
        assert result.task(':validateArchitectureConventions').outcome == TaskOutcome.SUCCESS
    }

    @Test
    @DisplayName('실제 Gradle 프로젝트의 Application 기술 의존 위반을 실패시킨다')
    void rejectsApplicationTechnicalDependencyInRealGradleBuild() {
        // given
        writeBuildFiles()
        writeJava('com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')
        writeJava('com/example/order/application/port/out/OrderQueryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderQueryPort {
                    org.springframework.data.domain.Page<String> findAll();
                }
                ''')

        // when
        BuildResult result = runner('validateArchitectureConventions').buildAndFail()

        // then
        assert result.output.contains('Architecture convention violations')
        assert result.output.contains('application must not depend on technical framework type')
    }

    private void writeBuildFiles() {
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'functional-test'\n"
        new File(tempDir, 'build.gradle').text = '''
                plugins {
                    id 'java'
                    id 'io.github.dochiri0916.build-convention'
                }
                '''.stripIndent().trim() + System.lineSeparator()
    }

    private void writeJava(String path, String source) {
        File file = new File(tempDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(tempDir)
                .withArguments(arguments.toList())
                .withPluginClasspath()
                .forwardOutput()
    }
}
