package io.github.dochiri0916.buildconvention.validator.architecture

import io.github.dochiri0916.buildconvention.catalog.TechnicalDependencyCatalog
import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.java.SourceInspector
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionGapRegressionTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('Application은 HTTP 클라이언트와 파일 I/O 타입에 의존할 수 없다')
    void forbidsIoTypesInApplication() {
        // given
        List<String> ioTypes = ['java.net.http.HttpClient', 'java.nio.file.Files', 'java.io.FileWriter']

        // when & then
        ioTypes.each { assert TechnicalDependencyCatalog.isForbiddenInApplication(it) }
        assert !TechnicalDependencyCatalog.isForbiddenInApplication('java.util.List')
    }

    @Test
    @DisplayName('FQN으로 던지는 JDK 기본 예외도 Domain에서 거부한다')
    void rejectsFullyQualifiedJdkExceptionInDomain() {
        // given
        Project project = project()
        write(project, 'src/main/java/com/example/sales/domain/vo/Money.java', '''
                package com.example.sales.domain.vo;
                public record Money(long value) {
                    public Money { if (value < 0) throw new java.lang.IllegalArgumentException(); }
                    public static Money of(long value) { return new Money(value); }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert violations.any { it.contains('domain must use domain-specific exceptions instead of JDK basic exceptions') }
    }

    @Test
    @DisplayName('NOPMD와 CHECKSTYLE:OFF 주석은 품질 도구 억제로 거부한다')
    void rejectsSuppressionComments() {
        // given
        Project project = project()
        write(project, 'src/main/java/com/example/sales/domain/vo/Money.java', '''
                package com.example.sales.domain.vo;
                public record Money(long value) { // NOPMD
                    public static Money of(long value) { return new Money(value); }
                }
                ''')
        write(project, 'src/main/java/com/example/sales/domain/vo/Price.java', '''
                package com.example.sales.domain.vo;
                // CHECKSTYLE:OFF
                public record Price(long value) {
                    public static Price of(long value) { return new Price(value); }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        ['Money', 'Price'].each { String name ->
            assert violations.any { it.contains("${name}.java must not suppress PMD/Checkstyle/SpotBugs warnings") }
        }
    }

    @Test
    @DisplayName('추가 srcDir과 non-main source set의 파일도 검사 대상에 포함한다')
    void collectsSourcesFromCustomSourceDirectories() {
        // given
        Project project = project()
        project.pluginManager.apply('java')
        project.sourceSets.main.java.srcDir('src/gen/java')
        project.sourceSets.create('integrationTest')
        write(project, 'src/main/java/com/example/A.java', 'package com.example; public class A {}')
        write(project, 'src/gen/java/com/example/B.java', 'package com.example; public class B {}')
        write(project, 'src/test/java/com/example/ATest.java', 'package com.example; class ATest {}')
        write(project, 'src/integrationTest/java/com/example/AIT.java', 'package com.example; class AIT {}')

        // when
        List<String> main = SourceInspector.collectMainSourceFiles(project).collect { it.name }
        List<String> tests = SourceInspector.collectTestJavaFiles(project).collect { it.name }

        // then
        assert main.toSet() == ['SalesApplication.java', 'A.java', 'B.java'].toSet()
        assert tests.toSet() == ['ATest.java', 'AIT.java'].toSet()
    }

    private Project project() {
        File dir = java.nio.file.Files.createTempDirectory(tempDir.toPath(), 'gap').toFile()
        Project project = ProjectBuilder.builder().withProjectDir(dir).build()
        write(project, 'src/main/java/com/example/SalesApplication.java',
                'package com.example; public class SalesApplication {}')
        return project
    }

    private static List<String> validate(Project project) {
        return JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())
    }

    private static void write(Project project, String path, String source) {
        File file = new File(project.projectDir, path)
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
