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
import com.dochiri.ilsim.runner.testing.TestFailures
import com.dochiri.ilsim.runner.testing.TestIntegrity
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** 서버의 단계별 최대 시도 횟수(TaskService.MAX_ATTEMPTS)와 같아야 한다. */
private const val MAX_ATTEMPTS = 3

private const val STOP_CHECK_MILLIS = 3000L


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
    private val runUsage = mutableListOf<AiUsage>()
    private val decisions = mutableListOf<ProcessingDecision>()
    private var failureKind: String? = null
    private var actualModel: String? = null
    private var requestedModel: String = "unknown"
    private var requestedEffort: String = "medium"
    private val ledger = SessionCostLedger(configStore.location.parent.resolve("sessions"))
    private val reviewCache = ReviewCache(configStore.location.parent.resolve("reviews"))

    private val ask: (prompt: String, model: String) -> String = ask ?: { prompt, model ->
        val a = ReviewRouter.askWithCost(prompt, model)
        a.costUsd?.let { reviewCost = (reviewCost ?: 0.0) + it }
        a.text
    }

    fun run(task: AssignedTask) {
        runCost = null
        runSession = null
        reviewCost = null
        runUsage.clear()
        decisions.clear()
        val journal = UsageJournal(configStore.location.parent.resolve("usage/${task.taskId}-${task.stage}.json"))
        runUsage.addAll(journal.load())
        failureKind = null
        actualModel = null
        requestedModel = task.model
        requestedEffort = task.effort
        val signal = StopSignal()
        val watcher = stopWatcher(task, signal)
        val outcome = try {
            UsageScope.collect(runUsage, { journal.save(runUsage) }, task.executionId) {
                val policy = task.reviewPolicy.copy(sample = task.reviewPolicy.sample?.takeIf { it.executionId == task.executionId })
                ReviewContext.within(policy, task.stage, policy.sample?.let { grant -> runUsage.any { it.review?.sampleId == grant.id } } ?: false) {
                    DecisionScope.collect(decisions) { StopSignal.within(signal) { execute(task) } }
                }
            }
        } catch (e: Exception) {
            failureKind = FailureKind.classify(e.message.orEmpty())
            StageResult(passed = false, retryable = false, error = e.message ?: e.toString())
        } finally {
            watcher.interrupt()
        }
        val result = if (signal.stopped) StageResult(passed = false, retryable = false, error = "사용자가 중지했습니다", branch = outcome.branch) else outcome
        client.result(task.taskId, result.copy(costUsd = runCost, model = actualModel ?: task.model, reviewCostUsd = reviewCost, reviewModel = ClaudeModels.REVIEW, sessionId = runSession,
            executionId = task.executionId, stage = task.stage, usage = runUsage.toList(), failureKind = if (signal.stopped) "STOPPED" else failureKind ?: if (!result.passed) "MODEL" else null,
            decisions = decisions.toList()))
        if (result.passed) ExecutionCheckpoint(configStore.location.parent.resolve("checkpoints/${task.taskId}-${task.stage}.json")).invalidate()
        journal.clear()
    }

    /** 삭제된 작업의 worktree 를 지운다. 끝나지 않은 작업이면 브랜치도 지운다. 실패해도 다음 일에 영향을 주지 않는다. */
    fun cleanup(job: Cleanup) {
        val repo = config.projects[job.projectId]?.path?.let { Path.of(it) } ?: return
        val worktree = workRoot.resolve("${job.projectId}-${job.taskId.take(8)}")
        if (worktree.exists()) Shell.git(repo, "worktree", "remove", "--force", worktree.toString())
        Shell.git(repo, "worktree", "prune")
        if (!job.keepBranch) Shell.git(repo, "branch", "-D", job.branch ?: "ilsim/${job.feature}-${job.taskId.take(8)}")
    }

    /** 서버에 중지 요청이 있는지 몇 초마다 묻는다. 묻지 못하면(네트워크 오류) 계속 실행한다. */
    private fun stopWatcher(task: AssignedTask, signal: StopSignal): Thread = Thread {
        try {
            while (!signal.stopped) {
                Thread.sleep(STOP_CHECK_MILLIS)
                val shouldStop = try { client.shouldStop(task.taskId, task.executionId) } catch (e: InterruptedException) { throw e } catch (_: Exception) { false }
                if (shouldStop) {
                    log(task, "중지 요청을 받아 실행 중인 프로세스를 끝냅니다")
                    signal.stop()
                }
            }
        } catch (_: InterruptedException) {
        }
    }.apply { isDaemon = true; start() }

    /** 코드를 쓰는 단계에는 승인된 기존 규칙과, CLI 를 쓸 때처럼 Claude 가 직접 돌려 볼 테스트 명령을 알려 준다. */
    private fun promptWithRules(task: AssignedTask): String {
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return task.prompt
        val brief = RulesBrief.build(
            architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId),
        )
        val test = config.projects[task.projectId]?.testCommand?.let { Gate.selfCheck(task.stage, it) }
        return listOfNotNull(task.prompt, brief, test).joinToString("\n\n")
    }

    private fun accumulate(run: AgentRun) {
        val calls = run.usage.ifEmpty { listOf(AiUsage(java.util.UUID.randomUUID().toString(), "WRITE", requestedModel, requestedEffort, costUsd = run.costUsd, status = if (run.costUsd == null) "UNKNOWN" else "CONFIRMED")) }
        run.actualModel?.let { actualModel = it }
        calls.forEach(UsageScope::record)
        calls.forEach { call -> ledger.delta(call)?.let { runCost = (runCost ?: 0.0) + it } }
        run.sessionId?.let { runSession = it }
    }

    private fun log(task: AssignedTask, msg: String) = client.event(task.taskId, task.stage, "log", msg)

    private fun execute(assigned: AssignedTask): StageResult {
        val project = config.projects[assigned.projectId]
            ?: return StageResult(false, error = "Runner에 등록되지 않은 프로젝트: ${assigned.projectId}")
        val task = assigned.copy(testCommand = project.testCommand)
        val repo = Path.of(project.path)
        val branch = "ilsim/${task.feature}-${task.taskId.take(8)}"
        val worktree = workRoot.resolve("${task.projectId}-${task.taskId.take(8)}")
        if (!worktree.exists()) {
            workRoot.createDirectories()
            val base = task.baseBranch ?: "HEAD"
            log(task, "worktree 생성: $worktree ($branch, 기준 $base)")
            Shell.gitOk(repo, "worktree", "add", "-b", branch, worktree.toString(), base)
        }

        if (task.stage in setOf("TEST_CODE", "IMPLEMENTATION")) {
            for (command in listOfNotNull(project.testCommand, project.mutationCommand) + project.analysisCommands) {
                val syntax = Shell.run(worktree, "sh", "-n", "-c", command)
                if (syntax.exit != 0) {
                    failureKind = "ENVIRONMENT"
                    return StageResult(false, error = "검증 명령 문법 오류: $command\n${syntax.output}", branch = branch)
                }
                val executable = Regex("^\\s*([A-Za-z0-9_./+-]+)(?:\\s|$)").find(command)?.groupValues?.get(1)
                if (executable != null && !executable.startsWith("./") && !executable.startsWith("../")) {
                    val available = Shell.run(worktree, "sh", "-c", "command -v \"\$1\" >/dev/null 2>&1", "ilsim-preflight", executable)
                    if (available.exit != 0) {
                        failureKind = "ENVIRONMENT"
                        return StageResult(false, error = "검증 실행 파일을 찾을 수 없습니다: $executable", branch = branch)
                    }
                }
            }
        }

        val checkpoint = ExecutionCheckpoint(configStore.location.parent.resolve("checkpoints/${task.taskId}-${task.stage}.json"))
        val rules = configStore.location.parent.resolve("projects/${task.projectId}")
        val ruleSnapshot = if (!rules.exists()) "" else java.nio.file.Files.list(rules).use { files -> files.filter { it.fileName.toString() in setOf("architecture.json", "convention.json", "risk.json") }.sorted().toList().joinToString { it.readText() } }
        val scope = ReviewCache.digest(task.prompt.substringBefore("\n## 이전 시도 피드백") + ruleSnapshot + project.toString() + ReviewRouter.policyKey +
            listOf("PATH", "JAVA_HOME", "GRADLE_USER_HOME").joinToString { System.getenv(it).orEmpty() })
        val previous = checkpoint.load()?.takeIf { it.scope == scope }
        if (previous?.result != null && previous.commitTree != null && previous.commitParent != null) {
            val head = Shell.gitOk(worktree, "rev-parse", "HEAD").trim()
            val committed = head != previous.commitParent && Shell.git(worktree, "rev-parse", "HEAD^").output.trim() == previous.commitParent &&
                Shell.gitOk(worktree, "rev-parse", "HEAD^{tree}").trim() == previous.commitTree && Shell.gitOk(worktree, "status", "--porcelain", "-uall").isBlank()
            val readyToCommit = head == previous.commitParent && previous.snapshot == ExecutionCheckpoint.snapshot(worktree) &&
                Shell.gitOk(worktree, "write-tree").trim() == previous.commitTree
            if (committed || readyToCommit) {
                log(task, "검토·커밋 완료 지점 복구: Agent 재실행 없이 결과 보고")
                previous.usage.forEach(UsageScope::record)
                decisions.addAll(previous.result.decisions)
                DecisionScope.record("WRITE", "CACHE", "완료한 커밋과 전체 검증 결과를 확인해 보고 재개", scope)
                runSession = previous.sessionId
                if (readyToCommit) commit(worktree, task)
                return previous.result
            }
        }
        val saved = previous?.takeIf { it.snapshot == ExecutionCheckpoint.snapshot(worktree) &&
            (it.reusable || it.executionId != null && it.executionId == task.executionId) }
        log(task, if (saved != null) "작성 완료 지점에서 검증 재개" else "claude 실행 (model=${task.model}, effort=${task.effort}, attempt=${task.attempt})")
        DecisionScope.record("WRITE", if (saved == null) "AGENT" else "CACHE", if (saved == null) "새 코드·문서 생성은 Agent가 담당" else "코드·규칙·명령·환경 유효성 확인 후 검증 재개", scope)
        var answered = false
        val onLine: (String) -> Unit = { line ->
            if (Gate.ANSWERED_MARK in line && "\"text\"" in line) answered = true
            StreamSummarizer.summarize(line).forEach { (type, data) -> client.event(task.taskId, task.stage, type, data) }
        }
        var run = if (saved != null) {
            runSession = saved.sessionId
            runUsage.addAll(saved.usage)
            AgentRun(0)
        } else agent.run(worktree, task, promptWithRules(task), onLine).also(::accumulate)
        if (task.resumeSessionId != null && run.exit != 0 && run.failureKind == "SESSION_MISSING" && !run.timedOut && !StopSignal.isStopped()) {
            // 이어 갈 세션을 잃었으면(기록 삭제 등) 새 세션으로 다시 한다. 지시문이 문서 경로를 알려 주므로 파일에서 이어진다.
            log(task, "이전 세션을 이어 가지 못해 새 세션으로 다시 실행합니다")
            val fresh = task.copy(resumeSessionId = null)
            run = agent.run(worktree, fresh, promptWithRules(fresh), onLine)
            accumulate(run)
        }
        failureKind = run.failureKind
        if (run.timedOut) return StageResult(false, retryable = true, error = "claude 실행이 ${CLAUDE_TIMEOUT_MINUTES}분을 넘겨 중단했습니다. 범위를 줄여 다시 시도하세요", branch = branch)
        if (run.exit != 0) return StageResult(false, retryable = run.failureKind !in setOf("AUTH", "NETWORK", "ENVIRONMENT"), error = "claude 종료 코드 ${run.exit}" + (run.diagnostic?.let { "\n$it" } ?: ""), branch = branch)
        if (answered && task.stage == "REQUIREMENTS") {
            // 질문에 답만 한 실행이다. 혹시 만든 파일은 버리고 검증 없이 끝낸다.
            Shell.git(worktree, "reset", "--hard", "HEAD")
            Shell.git(worktree, "clean", "-fd")
            log(task, "질문에 답했습니다. 문서·테스트·구현 단계 없이 끝냅니다")
            return StageResult(true, branch = branch, answered = true)
        }
        if (saved == null) checkpoint.save(ExecutionCheckpoint.State(scope, ExecutionCheckpoint.snapshot(worktree), task.executionId, runUsage.toList(), runSession))

        val changed = changedFiles(worktree)
        val violations = Gate.pathViolations(task.stage, task.feature, changed)
        if (violations.isNotEmpty()) {
            checkpoint.invalidate()
            // 허용 범위를 벗어난 변경은 되돌린 뒤 재시도한다.
            Shell.git(worktree, "reset", "--hard", "HEAD")
            Shell.git(worktree, "clean", "-fd")
            return StageResult(false, retryable = true, error = violations.joinToString("\n"), branch = branch)
        }

        val validation = linkedMapOf<String, String>()
        failure(task, project, worktree, validation)?.let {
            if (failureKind !in setOf("ENVIRONMENT", "NETWORK", "AUTH")) checkpoint.invalidate()
            else checkpoint.load()?.let { state -> checkpoint.save(state.copy(reusable = true)) }
            return StageResult(false, retryable = true, error = it, validation = validation, branch = branch)
        }

        val artifacts = when (task.stage) {
            "REQUIREMENTS" -> mapOf(Gate.requirementsPath(task.feature) to worktree.resolve(Gate.requirementsPath(task.feature)).readText())
            "TEST_CASES" -> mapOf(Gate.testSpecPath(task.feature) to worktree.resolve(Gate.testSpecPath(task.feature)).readText())
            else -> emptyMap()
        }
        Shell.gitOk(worktree, "add", "-A")
        val diff = Shell.gitOk(worktree, "diff", "--cached", "HEAD")
        val audit = aiAudit(task, project, worktree, diff, validation, scope)
        // 서버의 재시도 상한과 같다: 마지막 시도에서는 AI 지적만으로 작업을 막지 않고 승인자에게 넘긴다.
        if (audit.fixable.isNotEmpty() && task.attempt < MAX_ATTEMPTS) {
            checkpoint.invalidate()
            return StageResult(
                false, retryable = true, validation = validation, branch = branch,
                error = "AI 변경 검토가 아래를 지적했습니다. 해당 부분만 고치세요:\n" + audit.fixable.joinToString("\n") { "- [${it.type}] ${it.target} ${it.reason}" },
            )
        }
        val result = StageResult(true, diff = diff, artifacts = artifacts + audit.artifacts, validation = validation, branch = branch, decisions = decisions.toList())
        checkpoint.save(ExecutionCheckpoint.State(scope, ExecutionCheckpoint.snapshot(worktree), task.executionId, runUsage.toList(), runSession,
            result = result, commitParent = Shell.gitOk(worktree, "rev-parse", "HEAD").trim(), commitTree = Shell.gitOk(worktree, "write-tree").trim()))
        commit(worktree, task)
        return result
    }

    private fun commit(worktree: Path, task: AssignedTask) {
        Shell.gitOk(worktree, "-c", "user.name=ilsim", "-c", "user.email=ilsim@localhost", "commit", "-m", "ilsim(${task.stage.lowercase()}): ${task.feature}")
    }

    /**
     * 정적 검사를 모두 통과한 뒤 AI가 변경을 한 번 더 본다(누락, 무관한 변경, 테스트 우회). 코드를 쓰는 단계에서만, 통과한 시도마다 한 번 호출한다.
     * 결과는 승인자가 볼 수 있게 산출물과 검증 상태로 남기며 통과 여부는 바꾸지 않는다. AI 호출이 실패해도 작업은 계속된다.
     */
    private class AuditOutcome(val artifacts: Map<String, String>, val fixable: List<ChangeAudit.Finding>)

    private fun aiAudit(task: AssignedTask, project: ProjectEntry, worktree: Path, diff: String, validation: MutableMap<String, String>, contextHash: String): AuditOutcome {
        val none = AuditOutcome(emptyMap(), emptyList())
        if (task.stage != "TEST_CODE" && task.stage != "IMPLEMENTATION") return none
        return try {
            fun spec(path: String) = worktree.resolve(path).takeIf { it.exists() }?.readText()
            val prompt = ChangeAudit.prompt(
                task.stage, spec(Gate.requirementsPath(task.feature)), spec(Gate.testSpecPath(task.feature)),
                RulesBrief.build(architectures.load(task.projectId), conventions.load(task.projectId), RiskScanner.Store(configStore).load(task.projectId)),
                validation, diff, survivors.remove(task.taskId).orEmpty(), committedTests(task, worktree),
            )
            val result = reviewCache.get(prompt, "${ClaudeModels.REVIEW}/${ClaudeModels.EFFORT}/${ReviewRouter.policyKey}/$contextHash", ChangeAudit::parse) {
                ReviewContext.forPurpose("CHANGE_AUDIT") { ask(prompt, ClaudeModels.REVIEW) }
            }
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
        val files = Shell.gitOk(worktree, "ls-files").lines().filter { it.isNotBlank() && Gate.isTestPath(it) && it !in inDiff }
        val evidence = inDiff.joinToString("\n") { path -> worktree.resolve(path).takeIf { it.exists() }?.readText().orEmpty() } +
            worktree.resolve(Gate.testSpecPath(task.feature)).takeIf { it.exists() }?.readText().orEmpty()
        val refs = (Regex("(?:REQ|TC)-\\d+").findAll(evidence).map { it.value } + inDiff.map { it.substringAfterLast('/').substringBeforeLast('.') }).toSet()
        val tests = files.associateWith { worktree.resolve(it).readText() }
        val related = tests.filter { (path, text) -> refs.any { it in text || it in path } }
        return (related.ifEmpty { tests }).toSortedMap()
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
        risk(task, worktree, validation, review = false)?.let { return it }

        val changed = changedFiles(worktree).toSet()
        testIntegrity(task, worktree, changed, validation)?.let { return it }

        val testCommand = project.testCommand
        if (testCommand == null) {
            validation["tests"] = "UNKNOWN"
            risk(task, worktree, validation, review = true)
            return null
        }
        log(task, "테스트 실행: $testCommand")
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
            // 구현 전에는 신규 테스트가 실패(red)해야 의미가 있다.
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
            val updated = RiskReviewer.review(worktree, targets.take(20), known, "${ClaudeModels.REVIEW}/${ClaudeModels.EFFORT}/${ReviewRouter.policyKey}", false,
                JevRiskScreen.batched(Jev.fromEnvironment(), worktree, targets.take(20)), checkpoint = { opinions.save(task.projectId, it) }) { prompt ->
                    ReviewContext.forPurpose("RISK_REVIEW") { ask(prompt, ClaudeModels.REVIEW) }
                }
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
