package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList

import javax.xml.parsers.DocumentBuilderFactory

class ChangedCodeCoverageValidator {
    private static final BigDecimal BASE_CHANGED_LINE_COVERAGE = 0.90
    private static final BigDecimal BASE_CHANGED_BRANCH_COVERAGE = 0.85

    static List<String> validate(
            Project project,
            HexagonalConventionExtension convention,
            File jacocoXmlReport,
            String baseRef
    ) {
        List<String> violations = []

        if (baseRef == null || baseRef.isBlank()) {
            violations.add('changedCoverageBaseRef project property is required')
            return violations
        }
        if (!jacocoXmlReport.exists()) {
            violations.add("JaCoCo XML report does not exist: ${project.relativePath(jacocoXmlReport)}")
            return violations
        }

        DiffResult diffResult = collectChangedLines(project, baseRef)
        if (!diffResult.errorMessage.isBlank()) {
            violations.add(diffResult.errorMessage)
            return violations
        }
        if (diffResult.changedLinesByFile.isEmpty()) {
            return violations
        }

        Map<String, Map<Integer, LineCoverage>> coverageByFile = parseCoverage(project, jacocoXmlReport)
        CoverageSummary summary = summarizeChangedCoverage(diffResult.changedLinesByFile, coverageByFile)

        summary.missingCoverageFiles.each { String missingFile ->
            violations.add("Changed production file is missing from JaCoCo XML: ${missingFile}")
        }

        if (summary.executableLineCount == 0) {
            return violations
        }

        BigDecimal lineMinimum = minimum(convention.changedLineCoverageMinimum, BASE_CHANGED_LINE_COVERAGE)
        BigDecimal actualLineCoverage = ratio(summary.coveredLineCount, summary.executableLineCount)
        boolean lineCoverageFailed = actualLineCoverage < lineMinimum
        if (lineCoverageFailed) {
            violations.add(
                    "Changed production line coverage ${formatRatio(actualLineCoverage)} is below "
                            + "${formatRatio(lineMinimum)} (${summary.coveredLineCount}/${summary.executableLineCount})"
            )
        }

        if (summary.branchCount > 0) {
            BigDecimal branchMinimum = minimum(convention.changedBranchCoverageMinimum, BASE_CHANGED_BRANCH_COVERAGE)
            BigDecimal actualBranchCoverage = ratio(summary.coveredBranchCount, summary.branchCount)
            if (actualBranchCoverage < branchMinimum) {
                violations.add(
                        "Changed production branch coverage ${formatRatio(actualBranchCoverage)} is below "
                                + "${formatRatio(branchMinimum)} (${summary.coveredBranchCount}/${summary.branchCount})"
                )
            }
        }

        if (lineCoverageFailed) {
            summary.uncoveredLines.take(20).each { String uncovered ->
                violations.add("Uncovered changed line: ${uncovered}")
            }
            if (summary.uncoveredLines.size() > 20) {
                violations.add("${summary.uncoveredLines.size() - 20} more uncovered changed lines")
            }
        }

        return violations
    }

    private static DiffResult collectChangedLines(Project project, String baseRef) {
        DiffResult committedDiff = collectDiffChangedLines(project, "${baseRef}...HEAD".toString())
        if (!committedDiff.errorMessage.isBlank()) {
            return committedDiff
        }

        DiffResult workingTreeDiff = collectDiffChangedLines(project, 'HEAD')
        if (!workingTreeDiff.errorMessage.isBlank()) {
            return workingTreeDiff
        }

        DiffResult untrackedFiles = collectUntrackedJavaLines(project)
        if (!untrackedFiles.errorMessage.isBlank()) {
            return untrackedFiles
        }

        Map<String, Set<Integer>> changedLines = [:].withDefault { new TreeSet<Integer>() }
        [committedDiff, workingTreeDiff, untrackedFiles].each { DiffResult result ->
            result.changedLinesByFile.each { String filePath, Set<Integer> lines ->
                changedLines[filePath].addAll(lines)
            }
        }
        return new DiffResult(changedLines.findAll { String path, Set<Integer> lines -> !lines.isEmpty() }, '')
    }

    private static DiffResult collectDiffChangedLines(Project project, String revision) {
        List<String> command = [
                'git',
                '-C',
                project.projectDir.absolutePath,
                'diff',
                '--unified=0',
                '--no-ext-diff',
                '--diff-filter=ACMRT',
                revision,
                '--',
                'src/main/java'
        ]
        Process process = new ProcessBuilder(command)
                .directory(project.projectDir)
                .start()
        String stdout = process.inputStream.getText('UTF-8')
        String stderr = process.errorStream.getText('UTF-8')
        int exitValue = process.waitFor()

        if (exitValue != 0) {
            String message = stderr.trim()
            return new DiffResult([:], "Cannot calculate changed code coverage from '${revision}': ${message}")
        }

        Map<String, Set<Integer>> changedLinesByFile = [:].withDefault { new TreeSet<Integer>() }
        String currentFile = null
        stdout.readLines().each { String line ->
            if (line.startsWith('+++ ')) {
                currentFile = extractNewFilePath(line)
                return
            }

            def hunkMatcher = line =~ /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@.*/
            if (currentFile != null && hunkMatcher.matches()) {
                int startLine = hunkMatcher.group(1) as int
                int lineCount = hunkMatcher.group(2) == null ? 1 : hunkMatcher.group(2) as int
                if (lineCount > 0) {
                    (startLine..<(startLine + lineCount)).each { int changedLine ->
                        changedLinesByFile[currentFile].add(changedLine)
                    }
                }
            }
        }

        return new DiffResult(changedLinesByFile.findAll { String path, Set<Integer> lines ->
            path.startsWith('src/main/java/') && !lines.isEmpty()
        }, '')
    }

    private static DiffResult collectUntrackedJavaLines(Project project) {
        List<String> command = [
                'git',
                '-C',
                project.projectDir.absolutePath,
                'ls-files',
                '--others',
                '--exclude-standard',
                '--',
                'src/main/java'
        ]
        Process process = new ProcessBuilder(command)
                .directory(project.projectDir)
                .start()
        String stdout = process.inputStream.getText('UTF-8')
        String stderr = process.errorStream.getText('UTF-8')
        int exitValue = process.waitFor()
        if (exitValue != 0) {
            return new DiffResult([:], "Cannot find untracked Java files: ${stderr.trim()}")
        }

        Map<String, Set<Integer>> changedLinesByFile = [:].withDefault { new TreeSet<Integer>() }
        stdout.readLines().findAll { String path ->
            path.startsWith('src/main/java/') && path.endsWith('.java')
        }.each { String path ->
            File file = project.file(path)
            int lineCount = file.readLines('UTF-8').size()
            if (lineCount > 0) {
                changedLinesByFile[path].addAll(1..lineCount)
            }
        }
        return new DiffResult(changedLinesByFile, '')
    }

    private static String extractNewFilePath(String diffLine) {
        String path = diffLine.substring(4).trim()
        if (path == '/dev/null') {
            return null
        }
        return path.startsWith('b/') ? path.substring(2) : path
    }

    private static Map<String, Map<Integer, LineCoverage>> parseCoverage(Project project, File jacocoXmlReport) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
        factory.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
        factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
        factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
        factory.setXIncludeAware(false)
        factory.setExpandEntityReferences(false)

        Document document = factory.newDocumentBuilder().parse(jacocoXmlReport)
        Map<String, Map<Integer, LineCoverage>> coverageByFile = [:]
        NodeList packages = document.getElementsByTagName('package')
        for (int packageIndex = 0; packageIndex < packages.length; packageIndex++) {
            Element packageElement = packages.item(packageIndex) as Element
            String packagePath = packageElement.getAttribute('name')
            NodeList children = packageElement.childNodes
            for (int childIndex = 0; childIndex < children.length; childIndex++) {
                Node child = children.item(childIndex)
                if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == 'sourcefile') {
                    Element sourceFile = child as Element
                    String relativePath = "src/main/java/${packagePath}/${sourceFile.getAttribute('name')}"
                    coverageByFile[relativePath] = parseSourceFileCoverage(sourceFile)
                }
            }
        }

        return coverageByFile
    }

    private static Map<Integer, LineCoverage> parseSourceFileCoverage(Element sourceFile) {
        Map<Integer, LineCoverage> coverageByLine = [:]
        NodeList lineNodes = sourceFile.getElementsByTagName('line')
        for (int lineIndex = 0; lineIndex < lineNodes.length; lineIndex++) {
            Element line = lineNodes.item(lineIndex) as Element
            int lineNumber = line.getAttribute('nr') as int
            coverageByLine[lineNumber] = new LineCoverage(
                    line.getAttribute('mi') as int,
                    line.getAttribute('ci') as int,
                    line.getAttribute('mb') as int,
                    line.getAttribute('cb') as int
            )
        }
        return coverageByLine
    }

    private static CoverageSummary summarizeChangedCoverage(
            Map<String, Set<Integer>> changedLinesByFile,
            Map<String, Map<Integer, LineCoverage>> coverageByFile
    ) {
        CoverageSummary summary = new CoverageSummary()
        changedLinesByFile.each { String filePath, Set<Integer> changedLines ->
            Map<Integer, LineCoverage> fileCoverage = coverageByFile[filePath]
            if (fileCoverage == null) {
                summary.missingCoverageFiles.add(filePath)
                return
            }
            changedLines.each { Integer lineNumber ->
                LineCoverage coverage = fileCoverage[lineNumber]
                if (coverage == null || !coverage.executable) {
                    return
                }
                summary.executableLineCount++
                if (coverage.covered) {
                    summary.coveredLineCount++
                } else {
                    summary.uncoveredLines.add("${filePath}:${lineNumber}")
                }
                if (coverage.branchCount > 0) {
                    summary.branchCount += coverage.branchCount
                    summary.coveredBranchCount += coverage.coveredBranchCount
                }
            }
        }
        return summary
    }

    private static BigDecimal minimum(BigDecimal configured, BigDecimal baseline) {
        if (configured == null) {
            return baseline
        }
        return configured < baseline ? baseline : configured
    }

    private static BigDecimal ratio(int covered, int total) {
        if (total == 0) {
            return 1.00
        }
        return covered / total
    }

    private static String formatRatio(BigDecimal ratio) {
        return String.format(Locale.ROOT, '%.2f%%', ratio * 100)
    }

    private static final class DiffResult {
        final Map<String, Set<Integer>> changedLinesByFile
        final String errorMessage

        DiffResult(Map<String, Set<Integer>> changedLinesByFile, String errorMessage) {
            this.changedLinesByFile = changedLinesByFile
            this.errorMessage = errorMessage
        }
    }

    private static final class CoverageSummary {
        int executableLineCount
        int coveredLineCount
        int branchCount
        int coveredBranchCount
        List<String> uncoveredLines = []
        Set<String> missingCoverageFiles = new TreeSet<>()
    }

    private static final class LineCoverage {
        final int missedInstructionCount
        final int coveredInstructionCount
        final int missedBranchCount
        final int coveredBranchCount

        LineCoverage(
                int missedInstructionCount,
                int coveredInstructionCount,
                int missedBranchCount,
                int coveredBranchCount
        ) {
            this.missedInstructionCount = missedInstructionCount
            this.coveredInstructionCount = coveredInstructionCount
            this.missedBranchCount = missedBranchCount
            this.coveredBranchCount = coveredBranchCount
        }

        boolean getExecutable() {
            return missedInstructionCount + coveredInstructionCount > 0
        }

        boolean getCovered() {
            return coveredInstructionCount > 0
        }

        int getBranchCount() {
            return missedBranchCount + coveredBranchCount
        }
    }
}
