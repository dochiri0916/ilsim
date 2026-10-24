package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.convention.ConventionStore
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

private const val MAX_ATTEMPTS = 3

private const val STOP_CHECK_MILLIS = 3000L


class TaskExecutor(
    private val config: RunnerConfig,
    private val client: TaskSink,
    private val workRoot: Path = Path.of(System.getProperty("user.home"), ".ilsim", "worktrees"),
    private val configStore: ConfigStore = ConfigStore(),
    private val agent: ClaudeAgent = ProcessClaudeAgent,
    ask: ((prompt: String, model: String) -> String)? = null,
    private val architectures: ArchitectureStore = ArchitectureStore(configStore),
    private val conventions: ConventionStore = ConventionStore(configStore),
) {
    private var runCost: Double? = null

    private var runSession: String? = null

    private var reviewCost: Double? = null
    private val runUsage = mutableListOf<AiUsage>()
    private val decisions = mutableListOf<ProcessingDecision>()
    private var failureKind: String? = null
    private var actualModel: String? = null
    private var requestedModel: String = "unknown"
    private var requestedEffort: String = "medium"
    private val ledger = SessionCostLedger(configStore.location.parent.resolve("sessions"))

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
                    DecisionScope.collect(decisions) { StopSignal.within(signal) {
                        ClaudeAccountScope.within(claudeAccount(task)) { execute(task) }
                    } }
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

    fun cleanup(job: Cleanup) {
        val repo = config.projects[job.projectId]?.path?.let { Path.of(it) } ?: return
        val worktree = workRoot.resolve("${job.projectId}-${job.taskId.take(8)}")
        if (worktree.exists()) Shell.git(repo, "worktree", "remove", "--force", worktree.toString())
        Shell.git(repo, "worktree", "prune")
        if (!job.keepBranch) Shell.git(repo, "branch", "-D", job.branch ?: "ilsim/${job.feature}-${job.taskId.take(8)}")
    }

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

    private fun accumulate(run: AgentRun) {
        val calls = run.usage.ifEmpty { listOf(AiUsage(java.util.UUID.randomUUID().toString(), "WRITE", requestedModel, requestedEffort, costUsd = run.costUsd, status = if (run.costUsd == null) "UNKNOWN" else "CONFIRMED")) }
        run.actualModel?.let { actualModel = it }
        calls.forEach(UsageScope::record)
        calls.forEach { call -> ledger.delta(call)?.let { runCost = (runCost ?: 0.0) + it } }
        run.sessionId?.let { runSession = it }
    }

    private fun log(task: AssignedTask, msg: String) = client.event(task.taskId, task.stage, "log", msg)

    private fun claudeAccount(task: AssignedTask): String? {
        val resume = task.resumeSessionId
        if (resume != null) {
            val owner = ClaudeAccountScope.accountOf(resume)
            if (owner == null || owner in ClaudeAccounts.usableAny()) return owner
        }
        ProviderAccounts.usableName()?.takeIf { name -> ProviderAccounts.config(name)?.let { ProviderAccounts.eligible(task, it) } == true }?.let { return it }
        return ClaudeAccounts.pick()
    }

    private fun execute(assigned: AssignedTask): StageResult {
        val project = config.projects[assigned.projectId]
            ?: return StageResult(false, error = "Runner에 등록되지 않은 프로젝트: ${assigned.projectId}")
        val task = assigned.copy(testCommand = project.testCommand)
        val gates = StageGates(configStore, architectures, conventions, ask) { log(task, it) }
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
        val own = task.resumeSessionId?.let {
            if (ClaudeAccountScope.accountOf(it) == ClaudeAccountScope.account) task
            else null.also { _ -> log(task, "다른 계정의 세션이라 새 세션으로 이어서 실행합니다") }
        } ?: task.copy(resumeSessionId = null)
        ClaudeAccountScope.account?.let { log(task, "Claude 계정: $it") }
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
        } else agent.run(worktree, own, gates.promptWithRules(own), onLine).also(::accumulate)
        if (own.resumeSessionId != null && run.exit != 0 && run.failureKind == "SESSION_MISSING" && !run.timedOut && !StopSignal.isStopped()) {
            log(task, "이전 세션을 이어 가지 못해 새 세션으로 다시 실행합니다")
            val fresh = own.copy(resumeSessionId = null)
            run = agent.run(worktree, fresh, gates.promptWithRules(fresh), onLine)
            accumulate(run)
        }
        failureKind = run.failureKind
        if (run.timedOut) return StageResult(false, retryable = true, error = "claude 실행이 ${CLAUDE_TIMEOUT_MINUTES}분을 넘겨 중단했습니다. 범위를 줄여 다시 시도하세요", branch = branch)
        if (run.exit != 0) return StageResult(false, retryable = run.failureKind !in setOf("AUTH", "NETWORK", "ENVIRONMENT"), error = "claude 종료 코드 ${run.exit}" + (run.diagnostic?.let { "\n$it" } ?: ""), branch = branch)
        if (answered && task.stage == "REQUIREMENTS") {
            Shell.git(worktree, "reset", "--hard", "HEAD")
            Shell.git(worktree, "clean", "-fd")
            log(task, "질문에 답했습니다. 문서·테스트·구현 단계 없이 끝냅니다")
            return StageResult(true, branch = branch, answered = true)
        }
        if (saved == null) checkpoint.save(ExecutionCheckpoint.State(scope, ExecutionCheckpoint.snapshot(worktree), task.executionId, runUsage.toList(), runSession))

        val changed = gates.changedFiles(worktree)
        val violations = Gate.pathViolations(task.stage, task.feature, changed)
        if (violations.isNotEmpty()) {
            checkpoint.invalidate()
            Shell.git(worktree, "reset", "--hard", "HEAD")
            Shell.git(worktree, "clean", "-fd")
            return StageResult(false, retryable = true, error = violations.joinToString("\n"), branch = branch)
        }

        val validation = linkedMapOf<String, String>()
        gates.failure(task, project, worktree, validation).also { failureKind = gates.failureKind ?: failureKind }?.let {
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
        val audit = gates.aiAudit(task, project, worktree, diff, validation, scope)
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
}
