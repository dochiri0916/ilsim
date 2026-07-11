package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChangedCodeCoverageValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('커밋되지 않은 Java 변경도 변경 코드 커버리지에 포함한다')
    void validatesUncommittedJavaChanges() {
        // given
        Project project = gitProject('working-tree')
        writeJava(project, sampleSource('1'))
        commitAll(project.projectDir, 'baseline')
        writeJava(project, sampleSource('2'))
        File report = writeCoverageReport(project, 'Sample.java', 5, false)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.any { it.contains('Changed production line coverage') }
        assert violations.any { it.contains('Uncovered changed line: src/main/java/com/example/Sample.java:5') }
    }

    @Test
    @DisplayName('추적되지 않은 신규 Java 파일도 변경 코드 커버리지에 포함한다')
    void validatesUntrackedJavaFiles() {
        // given
        Project project = gitProject('untracked')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        writeJava(project, sampleSource('1'))
        File report = writeCoverageReport(project, 'Sample.java', 5, false)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.any { it.contains('Changed production line coverage') }
        assert violations.any { it.contains('Uncovered changed line: src/main/java/com/example/Sample.java:5') }
    }

    @Test
    @DisplayName('변경된 Java 파일이 JaCoCo XML에 없으면 실패한다')
    void rejectsChangedFileMissingFromCoverageReport() {
        // given
        Project project = gitProject('missing-coverage')
        writeJava(project, sampleSource('1'))
        commitAll(project.projectDir, 'baseline')
        writeJava(project, sampleSource('2'))
        File report = new File(project.projectDir, 'build/reports/jacoco/test/jacocoTestReport.xml')
        report.parentFile.mkdirs()
        report.text = '<?xml version="1.0" encoding="UTF-8"?><report name="test"/>'

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.any {
            it.contains('Changed production file is missing from JaCoCo XML: src/main/java/com/example/Sample.java')
        }
    }

    @Test
    @DisplayName('base ref가 없으면 변경 커버리지를 계산하지 않고 실패한다')
    void rejectsMissingBaseReference() {
        // given
        Project project = gitProject('missing-base')
        File report = writeCoverageReport(project, 'Sample.java', 5, true)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                ' '
        )

        // then
        assert violations == ['changedCoverageBaseRef project property is required']
    }

    @Test
    @DisplayName('JaCoCo XML이 없으면 명시적으로 실패한다')
    void rejectsMissingJacocoReport() {
        // given
        Project project = gitProject('missing-report')
        File report = new File(project.projectDir, 'missing.xml')

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.any { it.contains('JaCoCo XML report does not exist') }
    }

    @Test
    @DisplayName('존재하지 않는 Git base ref는 fail-closed로 거부한다')
    void rejectsUnknownGitBaseReference() {
        // given
        Project project = gitProject('unknown-ref')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        File report = writeCoverageReport(project, 'Sample.java', 5, true)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'missing-ref'
        )

        // then
        assert violations.any { it.contains("Cannot calculate changed code coverage from 'missing-ref...HEAD'") }
    }

    @Test
    @DisplayName('변경 실행 라인이 없으면 커버리지 위반을 만들지 않는다')
    void acceptsChangedFileWithoutExecutableLines() {
        // given
        Project project = gitProject('non-executable')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        writeJava(project, sampleSource('1'))
        File report = writeCoverageReport(project, 'Sample.java', 100, false, 0, 0)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('라인이 충족되어도 변경 branch 커버리지가 부족하면 실패한다')
    void rejectsInsufficientChangedBranchCoverage() {
        // given
        Project project = gitProject('branch-coverage')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        writeJava(project, sampleSource('1'))
        File report = writeCoverageReport(project, 'Sample.java', 5, true, 3, 1)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert !violations.any { it.contains('line coverage') }
        assert violations.any { it.contains('Changed production branch coverage 25.00% is below 85.00%') }
    }

    @Test
    @DisplayName('라인과 branch가 기준 이상이면 변경 커버리지를 통과한다')
    void acceptsCoveredChangedLineAndBranches() {
        // given
        Project project = gitProject('covered-change')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        writeJava(project, sampleSource('1'))
        File report = writeCoverageReport(project, 'Sample.java', 5, true, 0, 4)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('Production 변경이 없으면 빈 위반 목록을 반환한다')
    void acceptsRepositoryWithoutProductionChanges() {
        // given
        Project project = gitProject('no-changes')
        writeJava(project, sampleSource('1'))
        commitAll(project.projectDir, 'baseline')
        File report = writeCoverageReport(project, 'Sample.java', 5, true)

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                new HexagonalConventionExtension(),
                report,
                'HEAD'
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('낮게 설정한 기준은 baseline으로 보정하고 미커버 라인은 20건까지만 표시한다')
    void clampsMinimumAndLimitsUncoveredLineDiagnostics() {
        // given
        Project project = gitProject('many-uncovered-lines')
        runGit(project.projectDir, 'commit', '--allow-empty', '-m', 'baseline')
        List<String> sourceLines = ['package com.example;', '', 'public final class Sample {']
        (1..25).each { index -> sourceLines.add("    int value${index} = ${index};") }
        sourceLines.add('}')
        writeJava(project, sourceLines.join(System.lineSeparator()) + System.lineSeparator())
        File report = writeCoverageReport(project, 'Sample.java', (4..28).toList())
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.changedLineCoverageMinimum = 0.10

        // when
        List<String> violations = ChangedCodeCoverageValidator.validate(
                project,
                convention,
                report,
                'HEAD'
        )

        // then
        assert violations.any { it.contains('is below 90.00%') }
        assert violations.count { it.startsWith('Uncovered changed line:') } == 20
        assert violations.any { it == '5 more uncovered changed lines' }
    }

    private Project gitProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        runGit(projectDir, 'init')
        runGit(projectDir, 'config', 'user.email', 'test@example.com')
        runGit(projectDir, 'config', 'user.name', 'Convention Test')
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeJava(Project project, String source) {
        File file = new File(project.projectDir, 'src/main/java/com/example/Sample.java')
        file.parentFile.mkdirs()
        file.text = source
    }

    private static String sampleSource(String value) {
        return """package com.example;

public final class Sample {
    int value() {
        return ${value};
    }
}
"""
    }

    private static File writeCoverageReport(Project project, String sourceFile, int line, boolean covered) {
        return writeCoverageReport(project, sourceFile, line, covered, 0, 0)
    }

    private static File writeCoverageReport(
            Project project,
            String sourceFile,
            int line,
            boolean covered,
            int missedBranches,
            int coveredBranches
    ) {
        File report = new File(project.projectDir, 'build/reports/jacoco/test/jacocoTestReport.xml')
        report.parentFile.mkdirs()
        int missedInstructions = covered ? 0 : 1
        int coveredInstructions = covered ? 1 : 0
        report.text = """<?xml version="1.0" encoding="UTF-8"?>
<report name="test">
  <package name="com/example">
    <sourcefile name="${sourceFile}">
      <line nr="${line}" mi="${missedInstructions}" ci="${coveredInstructions}" mb="${missedBranches}" cb="${coveredBranches}"/>
    </sourcefile>
  </package>
</report>
"""
        return report
    }

    private static File writeCoverageReport(Project project, String sourceFile, List<Integer> lines) {
        File report = new File(project.projectDir, 'build/reports/jacoco/test/jacocoTestReport.xml')
        report.parentFile.mkdirs()
        String lineElements = lines.collect { line ->
            "      <line nr=\"${line}\" mi=\"1\" ci=\"0\" mb=\"0\" cb=\"0\"/>"
        }.join(System.lineSeparator())
        report.text = """<?xml version="1.0" encoding="UTF-8"?>
<report name="test">
  <package name="com/example">
    <sourcefile name="${sourceFile}">
${lineElements}
    </sourcefile>
  </package>
</report>
"""
        return report
    }

    private static void commitAll(File projectDir, String message) {
        runGit(projectDir, 'add', '.')
        runGit(projectDir, 'commit', '-m', message)
    }

    private static void runGit(File projectDir, String... args) {
        List<String> command = ['git', '-C', projectDir.absolutePath]
        command.addAll(args.toList())
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8')
        int exitCode = process.waitFor()
        assert exitCode == 0: output
    }
}
