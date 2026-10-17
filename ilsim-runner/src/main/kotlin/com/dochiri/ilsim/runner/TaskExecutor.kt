package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.Applicability
import com.dochiri.ilsim.runner.audit.ChangeAudit
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.architecture.ArchitectureChecker
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.convention.ConventionChecker
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.profile.StackDetector
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.JevRiskScreen
import com.dochiri.ilsim.runner.memory.NoiseDetector
import com.dochiri.ilsim.runner.memory.ProjectMemory
import com.dochiri.ilsim.runner.risk.RiskConfig
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import com.dochiri.ilsim.runner.testing.MutationReport
import com.dochiri.ilsim.runner.testing.TestIntegrity
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** 서버의 단계별 최대 시도 횟수(TaskService.MAX_ATTEMPTS)와 같아야 한다. */
private const val MAX_ATTEMPTS = 3


class TaskExecutor(
    private val config: RunnerConfig,
    private val client: TaskSink,
    private val workRoot: Path = Path.of(System.getProperty("user.home"), ".ilsim", "worktrees"),
    private val configStore: ConfigStore = ConfigStore(),
    private val agent: ClaudeAgent = ProcessClaudeAgent,
    /** 도구 없이 한 번 묻는 AI 호출(변경 검토, 위험 검토). */
    ask: ((prompt: String, model: String) -> String)? = null,
    private val architectures: ArchitectureStore = ArchitectureStore(configStore),
    private val conventions: ConventionStore = ConventionStore(configStore),
) {
    /** 변이 테스트에서 살아남은 변이를 작업별로 모아 AI 변경 검토에 넘긴다(상위 10건). */
    private val survivors = mutableMapOf<String, List<String>>()

    /** 이번 실행에서 Claude 가 쓴 비용. 실패해도 이미 쓴 비용이므로 결과에 항상 싣는다. */
    private var runCost: Double? = null

    /** 이번 실행에서 Claude 가 연 세션. 요구사항 대화를 이어 갈 때 서버가 쓴다. */
    private var runSession: String? = null

    /** 이번 실행에서 AI 검토 호출에 쓴 비용. */
    private var reviewCost: Double? = null

    private val ask: (prompt: String, model: String) -> String = ask ?: { prompt, model ->
        val a = ClaudeCli.askWithCost(prompt, model)
        a.costUsd?.let { reviewCost = (reviewCost ?: 0.0) + it }
        a.text
    }

    fun run(task: AssignedTask) {
        runCost = null
        runSession = null
        reviewCost = null
        val result = try {
            execute(task)
        } catch (e: Exception) {
            StageResult(passed = false, retryable = false, error = e.message ?: e.toString())
        }
        client.result(task.taskId, result.copy(costUsd = runCost, model = task.model, reviewCostUsd = reviewCost, reviewModel = ClaudeModels.REVIEW, sessionId = runSession))
    }

    /** 코드를 쓰는 단계에는 승인된 기존 규칙을 함께 알려 준다. */
    private fun promptWithRules(task: AssignedTask): String {
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return task.prompt
        val brief = RulesBrief.build(
            architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId),
        )
        return if (brief == null) task.prompt else task.prompt + "\n\n" + brief
    }

    private fun accumulate(run: AgentRun) {
        run.costUsd?.let { runCost = (runCost ?: 0.0) + it }
        run.sessionId?.let { runSession = it }
    }

    private fun log(task: AssignedTask, msg: String) = client.event(task.taskId, task.stage, "log", msg)

    private fun execute(task: AssignedTask): StageResult {
        val project = config.projects[task.projectId]
            ?: return StageResult(false, error = "Runner에 등록되지 않은 프로젝트: ${task.projectId}")
        val repo = Path.of(project.path)
        val branch = "ilsim/${task.feature}-${task.taskId.take(8)}"
        val worktree = workRoot.resolve("${task.projectId}-${task.taskId.take(8)}")
        if (!worktree.exists()) {
            workRoot.createDirectories()
            log(task, "worktree 생성: $worktree ($branch)")
            Shell.gitOk(repo, "worktree", "add", "-b", branch, worktree.toString(), "HEAD")
        }

        log(task, "claude 실행 (model=${task.model}, attempt=${task.attempt})")
        val onLine: (String) -> Unit = { line ->
            StreamSummarizer.summarize(line).forEach { (type, data) -> client.event(task.taskId, task.stage, type, data) }
        }
        var run = agent.run(worktree, task, promptWithRules(task), onLine)
        accumulate(run)
        if (task.resumeSessionId != null && run.exit != 0 && !run.timedOut) {
            // 이어 갈 세션을 잃었으면(기록 삭제 등) 새 세션으로 다시 한다. 지시문이 문서 경로를 알려 주므로 파일에서 이어진다.
            log(task, "이전 세션을 이어 가지 못해 새 세션으로 다시 실행합니다")
            val fresh = task.copy(resumeSessionId = null)
            run = agent.run(worktree, fresh, promptWithRules(fresh), onLine)
            accumulate(run)
        }
        if (run.timedOut) return StageResult(false, retryable = true, error = "claude 실행이 ${CLAUDE_TIMEOUT_MINUTES}분을 넘겨 중단했습니다. 범위를 줄여 다시 시도하세요", branch = branch)
        if (run.exit != 0) return StageResult(false, retryable = true, error = "claude 종료 코드 ${run.exit}", branch = branch)

        val changed = changedFiles(worktree)
        val violations = Gate.pathViolations(task.stage, task.feature, changed)
        if (violations.isNotEmpty()) {
            // 허용 범위를 벗어난 변경은 되돌린 뒤 재시도한다.
            Shell.git(worktree, "reset", "--hard", "HEAD")
            Shell.git(worktree, "clean", "-fd")
            return StageResult(false, retryable = true, error = violations.joinToString("\n"), branch = branch)
        }

        val validation = linkedMapOf<String, String>()
        failure(task, project, worktree, validation)?.let {
            return StageResult(false, retryable = true, error = it, validation = validation, branch = branch)
        }

        val artifacts = when (task.stage) {
            "REQUIREMENTS" -> mapOf(Gate.requirementsPath(task.feature) to worktree.resolve(Gate.requirementsPath(task.feature)).readText())
            "TEST_CASES" -> mapOf(Gate.testSpecPath(task.feature) to worktree.resolve(Gate.testSpecPath(task.feature)).readText())
            else -> emptyMap()
        }
        Shell.gitOk(worktree, "add", "-A")
        val diff = Shell.gitOk(worktree, "diff", "--cached", "HEAD")
        val audit = aiAudit(task, project, worktree, diff, validation)
        // 서버의 재시도 상한과 같다: 마지막 시도에서는 AI 지적만으로 작업을 막지 않고 승인자에게 넘긴다.
        if (audit.fixable.isNotEmpty() && task.attempt < MAX_ATTEMPTS) {
            return StageResult(
                false, retryable = true, validation = validation, branch = branch,
                error = "AI 변경 검토가 아래를 지적했습니다. 해당 부분만 고치세요:\n" + audit.fixable.joinToString("\n") { "- [${it.type}] ${it.target} ${it.reason}" },
            )
        }
        Shell.gitOk(
            worktree, "-c", "user.name=ilsim", "-c", "user.email=ilsim@localhost",
            "commit", "-m", "ilsim(${task.stage.lowercase()}): ${task.feature}",
        )
        return StageResult(true, diff = diff, artifacts = artifacts + audit.artifacts, validation = validation, branch = branch)
    }

    /**
     * 정적 검사를 모두 통과한 뒤 AI가 변경을 한 번 더 본다(누락, 무관한 변경, 테스트 우회). 코드를 쓰는 단계에서만, 통과한 시도마다 한 번 호출한다.
     * 결과는 승인자가 볼 수 있게 산출물과 검증 상태로 남기며 통과 여부는 바꾸지 않는다. AI 호출이 실패해도 작업은 계속된다.
     */
    private class AuditOutcome(val artifacts: Map<String, String>, val fixable: List<ChangeAudit.Finding>)

    private fun aiAudit(task: AssignedTask, project: ProjectEntry, worktree: Path, diff: String, validation: MutableMap<String, String>): AuditOutcome {
        val none = AuditOutcome(emptyMap(), emptyList())
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return none
        return try {
            fun spec(path: String) = worktree.resolve(path).takeIf { it.exists() }?.readText()
            val prompt = ChangeAudit.prompt(
                task.stage, spec(Gate.requirementsPath(task.feature)), spec(Gate.testSpecPath(task.feature)),
                RulesBrief.build(architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId)),
                validation, diff, survivors.remove(task.taskId).orEmpty(), committedTests(task, worktree),
            )
            val result = ChangeAudit.parse(ask(prompt, ClaudeModels.REVIEW))
            validation["ai-audit"] = if (result.verdict == "PASS") "PASSED" else "CONCERN"
            result.findings.forEach { log(task, "AI 검토 지적: [${it.type}] ${it.target} ${it.reason}") }
            AuditOutcome(mapOf("ai-audit.md" to ChangeAudit.render(result)), ChangeAudit.fixable(result))
        } catch (e: Exception) {
            validation["ai-audit"] = "UNKNOWN"
            log(task, "AI 변경 검토를 건너뜀: ${e.message}")
            none
        }
    }

    /** 구현 단계의 검토가 이전 단계에서 커밋된 테스트를 볼 수 있게 한다. 이번 diff 에 이미 있는 파일은 제외한다. */
    private fun committedTests(task: AssignedTask, worktree: Path): Map<String, String> {
        if (task.stage != "IMPLEMENTATION") return emptyMap()
        val inDiff = changedFiles(worktree).toSet()
        return Shell.gitOk(worktree, "ls-files").lines()
            .filter { it.isNotBlank() && Gate.isTestPath(it) && it !in inDiff }
            .associateWith { worktree.resolve(it).readText() }
    }

    /** 단계별 검증을 실행한다. 실패 사유를 돌려주고, 통과하면 null. 결과는 [validation]에 기록한다. */
    private fun failure(task: AssignedTask, project: ProjectEntry, worktree: Path, validation: MutableMap<String, String>): String? {
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
        risk(task, worktree, validation)?.let { return it }

        val changed = changedFiles(worktree).toSet()
        testIntegrity(task, worktree, changed, validation)?.let { return it }

        val testCommand = project.testCommand
        if (testCommand == null) {
            validation["tests"] = "UNKNOWN"
            return null
        }
        log(task, "테스트 실행: $testCommand")
        val since = Instant.now().minusSeconds(2)
        val r = Shell.run(worktree, "sh", "-c", testCommand)
        val skipped = TestIntegrity.skipped(worktree, since, changed.filter(Gate::isTestPath).toSet())
        if (skipped.isNotEmpty()) {
            validation["tests"] = "FAILED"
            return "건너뛴 테스트가 있습니다. 테스트를 끄지 말고 통과시키세요:\n" + skipped.take(20).joinToString("\n")
        }
        if (task.stage == "TEST_CODE") {
            // 구현 전에는 신규 테스트가 실패(red)해야 의미가 있다.
            if (r.exit == 0) {
                validation["tests"] = "FAILED"
                return "구현이 없는데 테스트가 모두 통과했습니다. 테스트가 요구사항을 검증하지 못하고 있습니다."
            }
            validation["tests"] = "RED"
        } else {
            validation["tests"] = if (r.exit == 0) "PASSED" else "FAILED"
            if (r.exit != 0) return "테스트 실패:\n${r.output}"
            mutation(task, project, worktree, changed, validation)
        }
        return null
    }

    /** 이번 작업에서 바뀐 테스트 파일이 테스트를 끄거나 단언을 비우지 않았는지 정적으로 본다. */
    private fun testIntegrity(task: AssignedTask, worktree: Path, changed: Set<String>, validation: MutableMap<String, String>): String? {
        if (task.stage != "TEST_CODE") return null
        val violations = TestIntegrity.scan(worktree, StackDetector.detect(worktree), changed)
        validation["test-integrity"] = if (violations.isEmpty()) "PASSED" else "FAILED"
        if (violations.isEmpty()) return null
        return "테스트가 검증하지 못하는 변경 ${violations.size}건:\n" + violations.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    /**
     * 변이 테스트(PIT)로 바뀐 소스의 테스트 강도를 잰다. 구현 단계에서는 테스트를 고칠 수 없으므로 막지 않고 결과와 살아남은 변이를 알린다.
     * 점수가 기준 미만이면 LOW 로 기록한다.
     */
    private fun mutation(task: AssignedTask, project: ProjectEntry, worktree: Path, changed: Set<String>, validation: MutableMap<String, String>) {
        val command = project.mutationCommand
        if (command == null) {
            validation["mutation"] = "UNKNOWN"
            return
        }
        log(task, "변이 테스트 실행: $command")
        val since = Instant.now().minusSeconds(2)
        val r = Shell.run(worktree, "sh", "-c", command, timeoutMinutes = 30)
        val result = if (r.exit == 0) MutationReport.evaluate(worktree, since, changed.filterNot(Gate::isTestPath).toSet()) else null
        if (result == null) {
            validation["mutation"] = "UNKNOWN"
            log(task, if (r.exit != 0) "변이 테스트 실패(종료 ${r.exit}): ${r.output.takeLast(300)}" else "바뀐 소스에 대한 변이 결과가 없습니다")
            return
        }
        validation["mutation"] = if (result.score >= MutationReport.THRESHOLD) "PASSED" else "LOW"
        log(task, "변이 점수 ${Math.round(result.score * 100)}% (${result.killed}/${result.total}), 기준 ${Math.round(MutationReport.THRESHOLD * 100)}%")
        survivors[task.taskId] = result.survivors.take(10)
        result.survivors.take(10).forEach { log(task, "살아남은 변이: $it") }
    }

    /**
     * 승인된 아키텍처 규칙을 워크트리에 적용한다. 규칙이 없거나 승인된 것이 없으면 UNKNOWN,
     * 검증 비적용 프로젝트면 SKIPPED 로 기록하고 막지 않는다. BLOCK 위반만 실패로 본다.
     */
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
            log(task, "아키텍처 검사 건너뜀: ${e.message}")
            return null
        }
        val blocking = violations.filter { it.severity == "BLOCK" }
        if (model.applicability.status == Applicability.PARTIAL) log(task, "아키텍처 부분 적용: ${model.applicability.reasons.joinToString("; ")}")
        validation["architecture"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "아키텍처 위반 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    /** 승인된 컨벤션 규칙을 이번 작업에서 바뀐 파일에만 적용한다. 기존 코드의 예외는 막지 않는다. */
    private fun convention(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>): String? {
        val model = conventions.load(task.projectId)
        if (model == null || model.rules.none { it.approved && !it.rejected }) {
            validation["convention"] = "UNKNOWN"
            return null
        }
        val changed = changedFiles(worktree).toSet()
        val blocking = ConventionChecker.check(worktree, StackDetector.detect(worktree), model, approvedOnly = true, onlyFiles = changed)
            .filter { it.severity == "BLOCK" }
        validation["convention"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "컨벤션 위반 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" }
    }

    /** 트랜잭션·동시성 위험을 이번 작업에서 바뀐 파일에만 적용한다. BLOCK 으로 지정된 규칙의 결과만 막는다. */
    private fun risk(task: AssignedTask, worktree: Path, validation: MutableMap<String, String>): String? {
        val config = RiskScanner.Store(configStore).load(task.projectId)
        val findings = RiskScanner.scan(worktree, StackDetector.detect(worktree), config, changedFiles(worktree).toSet())
        if (findings == null) {
            validation["risk"] = "UNKNOWN"
            return null
        }
        val memory = ProjectMemory(configStore)
        val suppressed = memory.suppressed(task.projectId)
        aiReview(task, worktree, findings, config)
        NoiseDetector.proposals(config, findings, memory.load(task.projectId))
            .forEach { log(task, "알림: " + NoiseDetector.message(task.projectId, it)) }
        findings.filter { it.severity == "REVIEW" && RiskReviewer.fingerprint(worktree, it) !in suppressed }.take(10)
            .forEach { log(task, "위험 후보: ${it.file}:${it.line} ${it.message} <${it.ruleId}> 권고: ${RiskScanner.remedy(it.ruleId)}") }
        val blocking = findings.filter { it.severity == "BLOCK" }
        validation["risk"] = if (blocking.isEmpty()) "PASSED" else "FAILED"
        if (blocking.isEmpty()) return null
        return "트랜잭션·동시성 위험 ${blocking.size}건:\n" + blocking.take(20).joinToString("\n") { "${it.file}:${it.line} ${it.message} <${it.ruleId}>" } +
            "\n권고:\n" + RiskScanner.remedies(blocking)
    }

    /** 사용자가 AI 검토를 승인한 규칙의 새 REVIEW 후보만 AI에 묻는다. 실패해도 게이트는 계속 진행하고, BLOCK 판정은 AI가 바꾸지 않는다. */
    private fun aiReview(task: AssignedTask, worktree: Path, findings: List<Violation>, config: RiskConfig) {
        val targets = findings.filter { it.severity == "REVIEW" && it.ruleId in config.aiReview }
        if (targets.isEmpty()) return
        val opinions = RiskReviewer.Store(configStore)
        try {
            val known = opinions.load(task.projectId)
            val updated = RiskReviewer.review(worktree, targets.take(20), known, ClaudeModels.REVIEW, false, JevRiskScreen.of(Jev.fromEnvironment(), worktree)) { ask(it, ClaudeModels.REVIEW) }
            opinions.save(task.projectId, updated)
            log(task, "AI 검토(승인한 규칙): ${targets.take(20).count { RiskReviewer.fingerprint(worktree, it) in updated }}건 의견")
        } catch (e: Exception) {
            log(task, "AI 검토를 건너뜀: ${e.message}")
        }
    }

    private fun analysis(task: AssignedTask, project: ProjectEntry, worktree: Path, validation: MutableMap<String, String>): String? {
        if (project.analysisCommands.isEmpty()) {
            validation["analysis"] = "UNKNOWN"
            return null
        }
        for (cmd in project.analysisCommands) {
            log(task, "정적 분석: $cmd")
            val r = Shell.run(worktree, "sh", "-c", cmd)
            if (r.exit != 0) {
                validation["analysis"] = "FAILED"
                return "정적 분석 실패 ($cmd):\n${r.output}"
            }
        }
        validation["analysis"] = "PASSED"
        return null
    }

    private fun changedFiles(worktree: Path): List<String> =
        Shell.gitOk(worktree, "status", "--porcelain", "-uall")
            .lines().filter { it.length > 3 }
            .map { it.substring(3).trim().removeSurrounding("\"") }
            .filterNot(Gate::isIgnored)
}
