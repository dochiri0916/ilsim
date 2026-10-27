package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.Applicability
import com.dochiri.ilsim.runner.architecture.ArchitectureChecker
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.audit.ChangeAudit
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.convention.ConventionChecker
import com.dochiri.ilsim.runner.convention.DocRules
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.JevRiskScreen
import com.dochiri.ilsim.runner.memory.NoiseDetector
import com.dochiri.ilsim.runner.memory.ProjectMemory
import com.dochiri.ilsim.runner.profile.StackDetector
import com.dochiri.ilsim.runner.risk.RiskConfig
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import com.dochiri.ilsim.runner.testing.MutationReport
import com.dochiri.ilsim.runner.testing.TestFailures
import com.dochiri.ilsim.runner.testing.TestIntegrity
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

class StageGates(
    private val configStore: ConfigStore,
    private val architectures: ArchitectureStore,
    private val conventions: ConventionStore,
    private val ask: (prompt: String, model: String) -> String,
    private val log: (String) -> Unit,
) {
    var failureKind: String? = null
        private set
    var riskCandidates: Int = 0
        private set
    private var survivors: List<String> = emptyList()
    private val reviewCache = ReviewCache(configStore.location.parent.resolve("reviews"))

    fun promptWithRules(task: AssignedTask): String {
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return task.prompt
        val brief = RulesBrief.build(
            architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId),
        )
        val test = task.testCommand?.let { Gate.selfCheck(task.stage, it) }
        return listOfNotNull(task.prompt, brief, test).joinToString("\n\n")
    }


    class AuditOutcome(val artifacts: Map<String, String>, val fixable: List<ChangeAudit.Finding>)

    fun aiAudit(task: AssignedTask, project: ProjectEntry, worktree: Path, diff: String, validation: MutableMap<String, String>, contextHash: String, guidelines: String? = null): AuditOutcome {
        val none = AuditOutcome(emptyMap(), emptyList())
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return none
        return try {
            fun spec(path: String) = worktree.resolve(path).takeIf { it.exists() }?.readText()
            val prompt = ChangeAudit.prompt(
                task.stage, spec(Gate.requirementsPath(task.feature)), spec(Gate.testSpecPath(task.feature)),
                listOfNotNull(RulesBrief.build(architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId)), guidelines)
                    .joinToString("\n\n").ifEmpty { null },
                validation, diff, survivors.also { survivors = emptyList() }, committedTests(task, worktree),
            )
            val result = reviewCache.get(prompt, "${ClaudeModels.REVIEW}/${ClaudeModels.EFFORT}/${ReviewRouter.policyKey}/$contextHash", ChangeAudit::parse) {
                ReviewContext.forPurpose("CHANGE_AUDIT") { ask(prompt, ClaudeModels.REVIEW) }
            }
            validation["ai-audit"] = if (result.verdict == "PASS") "PASSED" else "CONCERN"
            result.findings.forEach { log("AI 검토 지적: [${it.type}] ${it.target} ${it.reason}") }
            AuditOutcome(mapOf("ai-audit.md" to ChangeAudit.render(result)), ChangeAudit.fixable(result, task.stage))
        } catch (e: Exception) {
            validation["ai-audit"] = "UNKNOWN"
            log("AI 변경 검토를 건너뜀: ${e.message}")
            none
        }
    }

    private fun committedTests(task: AssignedTask, worktree: Path): Map<String, String> {
        if (task.stage != "IMPLEMENTATION") return emptyMap()
        val inDiff = changedFiles(worktree).toSet()
        val files = Shell.gitOk(worktree, "ls-files").lines().filter { it.isNotBlank() && Gate.isTestPath(it) && it !in inDiff }
        val evidence = inDiff.joinToString("\n") { path -> worktree.resolve(path).takeIf { it.exists() }?.readText().orEmpty() } +
            worktree.resolve(Gate.testSpecPath(task.feature)).takeIf { it.exists() }?.readText().orEmpty()
        val refs = (Regex("(?:REQ|TC)-\\d+").findAll(evidence).map { it.value } + inDiff.map { it.substringAfterLast('/').substringBeforeLast('.') }).toSet()
        val tests = files.associateWith { worktree.resolve(it).readText() }
        val related = tests.filter { (path, text) -> refs.any { it in text || it in path } }
        return (related.ifEmpty { tests }).toSortedMap()
    }

    fun failure(task: AssignedTask, project: ProjectEntry, worktree: Path, validation: MutableMap<String, String>): String? {
        if (task.stage == "TEST_CASES") {
            val v = Gate.traceabilityViolations(
                worktree.resolve(Gate.requirementsPath(task.feature)).readText(),
                worktree.resolve(Gate.testSpecPath(task.feature)).readText(),
            )
            validation["traceability"] = if (v.isEmpty()) "PASSED" else "FAILED"
            if (v.isNotEmpty()) return v.joinToString("\n")
        }
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return null

        val analysis = analysis(task, project, worktree, validation)
        if (analysis != null) return analysis
        architecture(task, worktree, validation)?.let { return it }
        convention(task, worktree, validation)?.let { return it }
        risk(task, worktree, validation, review = false)?.let { return it }

        val changed = changedFiles(worktree).toSet()
        testIntegrity(task, worktree, changed, validation)?.let { return it }

        val testCommand = project.testCommand
        if (testCommand == null) {
            validation["tests"] = "UNKNOWN"
            risk(task, worktree, validation, review = true)
            return null
        }
        log("테스트 실행: $testCommand")
        val since = Instant.now().minusSeconds(2)
        val r = Shell.run(worktree, "sh", "-c", testCommand)
        if (r.exit in setOf(126, 127, -1)) {
            failureKind = "ENVIRONMENT"
            validation["tests"] = "UNKNOWN"
            return "테스트 실행 환경 오류(종료 코드 ${r.exit}):\n${r.output}"
        }
        val skipped = TestIntegrity.skipped(worktree, since, changed.filter(Gate::isTestPath).toSet())
        if (skipped.isNotEmpty()) {
            validation["tests"] = "FAILED"
            return "건너뛴 테스트가 있습니다. 테스트를 끄지 말고 통과시키세요:\n" + skipped.take(20).joinToString("\n")
        }
        if (task.stage == "TEST_CODE") {
            if (r.exit == 0) {
                validation["tests"] = "FAILED"
                return "구현이 없는데 테스트가 모두 통과했습니다. 테스트가 요구사항을 검증하지 못하고 있습니다."
            }
            validation["tests"] = "RED"
        } else {
            validation["tests"] = if (r.exit == 0) "PASSED" else "FAILED"
            if (r.exit != 0) {
                val failures = TestFailures.collect(worktree, since)
                return if (failures.isNotEmpty()) "실패한 테스트 ${failures.size}건${if (failures.size >= 10) " 이상" else ""}:\n" + TestFailures.render(failures)
                else "테스트 실패(종료 코드 ${r.exit}):\n" + TestFailures.trimOutput(r.output)
            }
            mutation(task, project, worktree, changed, validation)
        }
        risk(task, worktree, validation, review = true)
        return null
    }

    fun quickCheck(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>): List<String> = listOfNotNull(
        architecture(task, worktree, validation),
        convention(task, worktree, validation),
        risk(task, worktree, validation, review = false),
        testIntegrity(task, worktree, changedFiles(worktree).toSet(), validation),
    )

    private fun testIntegrity(task: AssignedTask, worktree: Path, changed: Set<String>, validation: MutableMap<String, String>): String? {
        if (task.stage != "TEST_CODE") return null
        val violations = TestIntegrity.scan(worktree, StackDetector.detect(worktree), changed)
        validation["test-integrity"] = if (violations.isEmpty()) "PASSED" else "FAILED"
        if (violations.isEmpty()) return null
        return "테스트가 검증하지 못하는 변경 ${violations.size}건:\n" + violations.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    private fun mutation(task: AssignedTask, project: ProjectEntry, worktree: Path, changed: Set<String>, validation: MutableMap<String, String>) {
        val command = project.mutationCommand
        if (command == null) {
            validation["mutation"] = "UNKNOWN"
            return
        }
        log("변이 테스트 실행: $command")
        val since = Instant.now().minusSeconds(2)
        val r = Shell.run(worktree, "sh", "-c", command, timeoutMinutes = 30)
        val result = if (r.exit == 0) MutationReport.evaluate(worktree, since, changed.filterNot(Gate::isTestPath).toSet()) else null
        if (result == null) {
            validation["mutation"] = "UNKNOWN"
            log(if (r.exit != 0) "변이 테스트 실패(종료 ${r.exit}): ${r.output.takeLast(300)}" else "바뀐 소스에 대한 변이 결과가 없습니다")
            return
        }
        validation["mutation"] = if (result.score >= MutationReport.THRESHOLD) "PASSED" else "LOW"
        log("변이 점수 ${Math.round(result.score * 100)}% (${result.killed}/${result.total}), 기준 ${Math.round(MutationReport.THRESHOLD * 100)}%")
        survivors = result.survivors.take(10)
        result.survivors.take(10).forEach { log("살아남은 변이: $it") }
    }

    private fun architecture(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>): String? {
        val model = architectures.load(task.projectId)
        if (model == null || model.rules.none { it.approved && !it.rejected }) {
            validation["architecture"] = "UNKNOWN"
            return null
        }
        if (model.applicability.status == Applicability.NOT_APPLICABLE) {
            validation["architecture"] = "SKIPPED"
            return null
        }
        val violations = try {
            ArchitectureChecker.check(worktree, StackDetector.detect(worktree), model, approvedOnly = true)
        } catch (e: IllegalArgumentException) {
            validation["architecture"] = "UNKNOWN"
            log("아키텍처 검사 건너뜀: ${e.message}")
            return null
        }
        val blocking = violations.filter { it.severity == "BLOCK" }
        if (model.applicability.status == Applicability.PARTIAL) log("아키텍처 부분 적용: ${model.applicability.reasons.joinToString("; ")}")
        validation["architecture"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "아키텍처 위반 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    private fun convention(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>): String? {
        val model = conventions.load(task.projectId)
        if (model == null || model.rules.none { it.approved && !it.rejected }) {
            validation["convention"] = "UNKNOWN"
            return null
        }
        val changed = changedFiles(worktree).toSet()
        val found = ConventionChecker.check(worktree, StackDetector.detect(worktree), model, approvedOnly = true, onlyFiles = changed)
            .filter { it.severity == "BLOCK" }
        val blocking = DocRules.introduced(worktree, model, found) { path -> Shell.git(worktree, "show", "HEAD:$path").takeIf { it.exit == 0 }?.output?.lines() }
        (found.size - blocking.size).takeIf { it > 0 }?.let { log("문서 규칙의 기존 위반 ${it}건은 이번 변경이 만든 것이 아니라서 막지 않습니다") }
        validation["convention"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "컨벤션 위반 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    private fun risk(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>, review: Boolean): String? {
        val config = RiskScanner.Store(configStore).load(task.projectId)
        val findings = RiskScanner.scan(worktree, StackDetector.detect(worktree), config, changedFiles(worktree).toSet())
        if (findings == null) {
            validation["risk"] = "UNKNOWN"
            return null
        }
        val memory = ProjectMemory(configStore)
        val suppressed = memory.suppressed(task.projectId)
        if (review) aiReview(task, worktree, findings, config)
        NoiseDetector.proposals(config, findings, memory.load(task.projectId))
            .forEach { log("알림: " + NoiseDetector.message(task.projectId, it)) }
        val candidates = findings.filter { it.severity == "REVIEW" && RiskReviewer.fingerprint(worktree, it) !in suppressed }
        riskCandidates = candidates.size
        candidates.take(10)
            .forEach { log("위험 후보: ${it.file}:${it.line} ${it.message} <${it.ruleId}> 권고: ${RiskScanner.remedy(it.ruleId)}") }
        val blocking = findings.filter { it.severity == "BLOCK" }
        validation["risk"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "트랜잭션·동시성 위험 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" } +
            "\n권고:\n" + RiskScanner.remedies(blocking)
    }

    private fun aiReview(task: AssignedTask, worktree: Path, findings: List<Violation>, config: RiskConfig) {
        val targets = findings.filter { it.severity == "REVIEW" && it.ruleId in config.aiReview }
        if (targets.isEmpty()) return
        val opinions = RiskReviewer.Store(configStore)
        try {
            val known = opinions.load(task.projectId)
            val updated = RiskReviewer.review(worktree, targets.take(20), known, "${ClaudeModels.REVIEW}/${ClaudeModels.EFFORT}/${ReviewRouter.policyKey}", false,
                JevRiskScreen.batched(Jev.fromEnvironment(), worktree, targets.take(20)), checkpoint = { opinions.save(task.projectId, it) }) { prompt ->
                    ReviewContext.forPurpose("RISK_REVIEW") { ask(prompt, ClaudeModels.REVIEW) }
                }
            opinions.save(task.projectId, updated)
            log("AI 검토(승인한 규칙): ${targets.take(20).count { RiskReviewer.fingerprint(worktree, it) in updated }}건 의견")
        } catch (e: Exception) {
            log("AI 검토를 건너뜀: ${e.message}")
        }
    }

    private fun analysis(task: AssignedTask, project: ProjectEntry, worktree: Path, validation: MutableMap<String, String>): String? {
        if (project.analysisCommands.isEmpty()) {
            validation["analysis"] = "UNKNOWN"
            return null
        }
        for (cmd in project.analysisCommands) {
            log("정적 분석: $cmd")
            val r = Shell.run(worktree, "sh", "-c", cmd)
            if (r.exit != 0) {
                validation["analysis"] = "FAILED"
                return "정적 분석 실패 ($cmd):\n${r.output}"
            }
        }
        validation["analysis"] = "PASSED"
        return null
    }

    fun changedFiles(worktree: Path): List<String> =
        Shell.gitOk(worktree, "status", "--porcelain", "-uall")
            .lines().filter { it.length > 3 }
            .map { it.substring(3).trim().removeSurrounding("\"") }
            .filterNot(Gate::isIgnored)
}
