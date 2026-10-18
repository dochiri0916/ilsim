package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 git 저장소와 워크트리를 쓰되 Claude 와 AI 호출만 가짜로 바꿔 실패 경로를 확인한다. */
class TaskExecutorFailureTest {

    private class Sink : TaskSink {
        val events = mutableListOf<String>()
        var result: StageResult? = null
        override fun event(taskId: String, stage: String, type: String, data: String) { events += "$type:$data" }
        override fun result(taskId: String, result: StageResult) { this.result = result }
    }

    private lateinit var root: Path
    private lateinit var repo: Path
    private val sink = Sink()
    private var testCommand: String? = null
    private var asked = mutableListOf<String>()
    private var auditAnswer = """{"verdict":"PASS","findings":[]}"""

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ilsim-exec")
        repo = root.resolve("repo").also { it.createDirectories() }
        git(repo, "init", "-q", "-b", "main")
        repo.resolve("README.md").writeText("demo")
        git(repo, "add", "-A")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun git(dir: Path, vararg args: String) = Shell.gitOk(dir, *args)

    private fun task(stage: String, attempt: Int = 1) =
        AssignedTask("11111111-aaaa", "demo", stage, attempt, "slug", "claude-sonnet-5-5", "medium", "프롬프트")

    private fun executor(agent: ClaudeAgent): TaskExecutor {
        val store = ConfigStore(root.resolve("home/runner.json"))
        val config = RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo", testCommand = testCommand)))
        return TaskExecutor(config, sink, root.resolve("worktrees"), store, agent, { prompt, _ -> asked += prompt; auditAnswer })
    }

    private fun run(stage: String, attempt: Int = 1, agent: ClaudeAgent): StageResult {
        executor(agent).run(task(stage, attempt))
        return assertNotNull(sink.result)
    }

    private fun writes(vararg files: Pair<String, String>) = ClaudeAgent { worktree, _, _, _ ->
        files.forEach { (path, text) ->
            val f = worktree.resolve(path)
            f.parent.createDirectories()
            f.writeText(text)
        }
        AgentRun(0)
    }

    private val requirements = "specs/slug/requirements.md" to "# 요구사항\n\n### REQ-1: 기능\n"
    private val worktree get() = root.resolve("worktrees/demo-11111111")

    @Test
    fun `Runner 에 등록되지 않은 프로젝트는 재시도 없이 실패한다`() {
        val r = TaskExecutor(RunnerConfig(), sink, root.resolve("worktrees"), ConfigStore(root.resolve("home/runner.json"))).let {
            it.run(task("REQUIREMENTS")); sink.result!!
        }
        assertFalse(r.passed)
        assertFalse(r.retryable)
        assertTrue(r.error!!.contains("등록되지 않은 프로젝트"))
    }

    @Test
    fun `Claude 가 0 이 아닌 코드로 끝나면 재시도 가능한 실패다`() {
        val r = run("REQUIREMENTS") { _, _, _, _ -> AgentRun(2) }
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.contains("종료 코드 2"))
    }

    @Test
    fun `Claude 가 시간 제한을 넘기면 재시도 가능한 실패이고 이유를 알려 준다`() {
        val r = run("REQUIREMENTS") { _, _, _, _ -> AgentRun(-1, timedOut = true) }
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.contains("넘겨 중단"))
    }

    @Test
    fun `Claude 실행 자체가 예외를 던지면 재시도 없이 실패한다`() {
        val r = run("REQUIREMENTS") { _, _, _, _ -> error("claude 를 찾을 수 없습니다") }
        assertFalse(r.passed)
        assertFalse(r.retryable)
        assertTrue(r.error!!.contains("claude 를 찾을 수 없습니다"))
    }

    @Test
    fun `아무것도 바꾸지 않으면 변경 없음으로 실패한다`() {
        val r = run("REQUIREMENTS") { _, _, _, _ -> AgentRun(0) }
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.contains("변경된 파일이 없습니다"))
    }

    @Test
    fun `허용 범위를 벗어난 변경은 되돌리고 재시도하게 한다`() {
        val r = run("REQUIREMENTS", agent = writes(requirements, "src/Main.java" to "class Main {}"))
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.contains("src/Main.java"))
        assertFalse(worktree.resolve("src/Main.java").exists())
        assertFalse(worktree.resolve(requirements.first).exists())
    }

    @Test
    fun `요구사항 단계가 통과하면 문서를 산출물로 올리고 커밋한다`() {
        val r = run("REQUIREMENTS", agent = writes(requirements))
        assertTrue(r.passed)
        assertEquals(setOf(requirements.first), r.artifacts.keys.filter { it.endsWith("requirements.md") }.toSet())
        assertTrue(git(worktree, "log", "--oneline").contains("ilsim(requirements)"))
    }

    @Test
    fun `테스트 케이스가 요구사항과 연결되지 않으면 실패한다`() {
        run("REQUIREMENTS", agent = writes(requirements))
        sink.result = null
        val r = run("TEST_CASES", agent = writes("specs/slug/test-spec.md" to "TC-1: 아무 연결 없음"))
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.contains("REQ-1"))
        assertEquals("FAILED", r.validation["traceability"])
    }

    private val goodTest = "src/test/java/FooTest.java" to "class FooTest { @Test void a() { assertEquals(1, 1); } }"

    @Test
    fun `테스트 코드 단계에서 구현 없이 테스트가 통과하면 실패한다`() {
        testCommand = "true"
        val r = run("TEST_CODE", agent = writes(goodTest))
        assertFalse(r.passed)
        assertTrue(r.error!!.contains("모두 통과"))
        assertEquals("FAILED", r.validation["tests"])
    }

    @Test
    fun `테스트 코드 단계에서 테스트가 실패하면 RED 로 통과한다`() {
        testCommand = "false"
        val r = run("TEST_CODE", agent = writes(goodTest))
        assertTrue(r.passed, r.error)
        assertEquals("RED", r.validation["tests"])
    }

    @Test
    fun `테스트 코드 단계가 테스트 아닌 파일을 바꾸면 실패한다`() {
        testCommand = "false"
        val r = run("TEST_CODE", agent = writes(goodTest, "src/Main.java" to "class Main {}"))
        assertFalse(r.passed)
        assertTrue(r.error!!.contains("테스트 경로가 아닌 변경"))
    }

    private fun toImplementationBase() {
        testCommand = "false"
        run("TEST_CODE", agent = writes(goodTest))
        sink.result = null
    }

    @Test
    fun `구현 단계에서 테스트가 실패하면 출력과 함께 재시도하게 한다`() {
        toImplementationBase()
        val r = run("IMPLEMENTATION", agent = writes("src/Main.java" to "class Main {}"))
        assertFalse(r.passed)
        assertTrue(r.retryable)
        assertTrue(r.error!!.startsWith("테스트 실패"))
        assertEquals("FAILED", r.validation["tests"])
    }

    @Test
    fun `구현 단계의 테스트 실패는 JUnit 리포트에서 실패한 테스트와 이유를 뽑는다`() {
        toImplementationBase()
        testCommand = "mkdir -p build/test-results/test && printf '%s' '$junitFailure' > build/test-results/test/TEST-FooTest.xml && false"
        val r = run("IMPLEMENTATION", agent = writes("src/Main.java" to "class Main {}"))
        assertFalse(r.passed)
        assertTrue(r.error!!.contains("FooTest.slugify"), r.error)
        assertTrue(r.error!!.contains("expected: <a-b> but was: <a b>"), r.error)
        assertTrue(r.error!!.contains("FooTest.java:12"), r.error)
    }

    @Test
    fun `중지 요청을 받으면 실행 중인 프로세스를 끝내고 중지로 보고한다`() {
        val stopping = object : TaskSink by sink {
            override fun shouldStop(taskId: String) = true
            override fun shouldStop(taskId: String, executionId: String?) = true
        }
        val store = ConfigStore(root.resolve("home/runner.json"))
        val config = RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo")))
        val started = System.nanoTime()
        TaskExecutor(config, stopping, root.resolve("worktrees"), store, { worktree, _, _, _ ->
            val r = Shell.run(worktree, "sleep", "30")
            AgentRun(r.exit)
        }, { _, _ -> "" }).run(task("REQUIREMENTS"))
        val r = assertNotNull(sink.result)
        assertFalse(r.passed)
        assertEquals("사용자가 중지했습니다", r.error)
        assertTrue(System.nanoTime() - started < 20_000_000_000L)
    }

    private val junitFailure = """<testsuite name="FooTest"><testcase classname="com.demo.FooTest" name="slugify()"><failure message="expected: &lt;a-b&gt; but was: &lt;a b&gt;" type="AssertionFailedError">org.opentest4j.AssertionFailedError: expected
	at com.demo.FooTest.slugify(FooTest.java:12)</failure></testcase></testsuite>"""

    @Test
    fun `구현 단계가 테스트를 수정하면 되돌리고 실패한다`() {
        toImplementationBase()
        val r = run("IMPLEMENTATION", agent = writes(goodTest.first to "class FooTest {}"))
        assertFalse(r.passed)
        assertTrue(r.error!!.contains("수정 금지 경로"))
        assertTrue(worktree.resolve(goodTest.first).toFile().readText().contains("assertEquals"))
    }

    @Test
    fun `AI 검토가 지적하면 마지막 시도 전에는 되돌려 고치게 하고 마지막 시도에서는 승인자에게 넘긴다`() {
        toImplementationBase()
        testCommand = "true"
        auditAnswer = """{"verdict":"CONCERN","findings":[{"type":"OMISSION","target":"REQ-1","reason":"다뤄지지 않음"}]}"""

        val first = run("IMPLEMENTATION", attempt = 1, agent = writes("src/Main.java" to "class Main {}"))
        assertFalse(first.passed)
        assertTrue(first.retryable)
        assertTrue(first.error!!.contains("AI 변경 검토가 아래를 지적"))
        assertEquals(0, git(worktree, "log", "--oneline", "HEAD").lines().count { it.contains("ilsim(implementation)") })

        sink.result = null
        val last = run("IMPLEMENTATION", attempt = 3, agent = writes("src/Main.java" to "class Main {}"))
        assertTrue(last.passed, last.error)
        assertEquals("CONCERN", last.validation["ai-audit"])
    }

    @Test
    fun `AI 호출이 실패해도 정적 검사를 통과한 작업은 계속 진행한다`() {
        toImplementationBase()
        testCommand = "true"
        val executor = TaskExecutor(
            RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo", testCommand = "true"))),
            sink, root.resolve("worktrees"), ConfigStore(root.resolve("home/runner.json")),
            writes("src/Main.java" to "class Main {}"), { _, _ -> error("AI 사용 불가") },
        )
        executor.run(task("IMPLEMENTATION"))
        val r = assertNotNull(sink.result)
        assertTrue(r.passed, r.error)
        assertEquals("UNKNOWN", r.validation["ai-audit"])
    }

    @Test
    fun `구현 단계 AI 검토에는 이전 단계에서 커밋된 테스트가 전달된다`() {
        toImplementationBase()
        testCommand = "true"
        run("IMPLEMENTATION", agent = writes("src/Main.java" to "class Main {}"))
        assertTrue(asked.last().contains("기존 테스트") && asked.last().contains("FooTest.java"))
    }

    @Test
    fun `실패한 실행도 쓴 비용과 모델을 결과에 싣는다`() {
        val failed = run("REQUIREMENTS") { _, _, _, _ -> AgentRun(2, costUsd = 0.12) }
        assertFalse(failed.passed)
        assertEquals(0.12, failed.costUsd)
        assertEquals("claude-sonnet-5-5", failed.model)

        sink.result = null
        val passed = run("REQUIREMENTS", agent = { w, t, p, l ->
            writes(requirements).run(w, t, p, l).copy(costUsd = 0.3)
        })
        assertTrue(passed.passed)
        assertEquals(0.3, passed.costUsd)
    }

    @Test
    fun `Claude 를 실행하지 못하면 비용 없이 보고한다`() {
        val r = run("REQUIREMENTS") { _, _, _, _ -> error("claude 없음") }
        assertNull(r.costUsd)
    }

    private fun resumeTask() = task("REQUIREMENTS").copy(resumeSessionId = "sess-1")

    @Test
    fun `이어 갈 세션이 있으면 그 세션으로 실행하고 새 세션 id 를 결과에 싣는다`() {
        val seen = mutableListOf<String?>()
        executor { w, t, p, l ->
            seen += t.resumeSessionId
            writes(requirements).run(w, t, p, l).copy(sessionId = "sess-1", costUsd = 0.05)
        }.run(resumeTask())
        val r = assertNotNull(sink.result)
        assertTrue(r.passed)
        assertEquals(listOf<String?>("sess-1"), seen)
        assertEquals("sess-1", r.sessionId)
    }

    @Test
    fun `세션이 없다는 실패만 새 세션으로 다시 하고 두 실행의 비용을 합친다`() {
        val seen = mutableListOf<String?>()
        executor { w, t, p, l ->
            seen += t.resumeSessionId
            if (t.resumeSessionId != null) AgentRun(1, costUsd = 0.01, failureKind = "SESSION_MISSING")
            else writes(requirements).run(w, t, p, l).copy(sessionId = "new-sess", costUsd = 0.1)
        }.run(resumeTask())
        val r = assertNotNull(sink.result)
        assertTrue(r.passed)
        assertEquals(listOf<String?>("sess-1", null), seen)
        assertEquals("new-sess", r.sessionId)
        assertEquals(0.11, r.costUsd!!, 1e-9)
        assertTrue(sink.events.any { it.contains("새 세션으로 다시") })
    }

    @Test
    fun `시간 초과는 새 세션으로 다시 하지 않는다`() {
        var calls = 0
        executor { _, _, _, _ -> calls++; AgentRun(-1, timedOut = true) }.run(resumeTask())
        assertEquals(1, calls)
        assertTrue(assertNotNull(sink.result).error!!.contains("넘겨 중단"))
    }

    @Test
    fun `일반적인 세션 재개 오류는 새 세션을 기동하지 않는다`() {
        var calls = 0
        executor { _, _, _, _ -> calls++; AgentRun(1, costUsd = 0.03, failureKind = "NETWORK") }.run(resumeTask())
        assertEquals(1, calls)
        assertFalse(assertNotNull(sink.result).passed)
        assertEquals(0.03, sink.result!!.costUsd)
    }

    @Test
    fun `검증 환경만 복구되면 코드를 다시 작성하지 않고 테스트부터 재개한다`() {
        val marker = root.resolve("ready")
        testCommand = "test -f '$marker' || exit 127"
        var calls = 0
        val agent = ClaudeAgent { w, t, p, l -> calls++; writes("src/Main.java" to "class Main {}").run(w, t, p, l) }
        val first = run("IMPLEMENTATION", agent = agent)
        assertFalse(first.passed)
        assertEquals("ENVIRONMENT", first.failureKind)
        marker.writeText("ready")
        val next = run("IMPLEMENTATION", attempt = 2, agent = agent)
        assertTrue(next.passed)
        assertEquals(1, calls)
        assertEquals(1, asked.size)
    }

    @Test
    fun `검증 재개 전에 코드가 바뀌면 작성 결과를 재사용하지 않는다`() {
        val marker = root.resolve("ready")
        testCommand = "test -f '$marker' || exit 127"
        var calls = 0
        val agent = ClaudeAgent { w, t, p, l -> calls++; writes("src/Main.java" to "class Main {}").run(w, t, p, l) }
        assertFalse(run("IMPLEMENTATION", agent = agent).passed)
        worktree.resolve("src/Main.java").writeText("class Main { int changed; }")
        marker.writeText("ready")
        assertTrue(run("IMPLEMENTATION", attempt = 2, agent = agent).passed)
        assertEquals(2, calls)
    }

    @Test
    fun `커밋 후 보고가 실패해도 검증한 결과와 비용을 복구해 Agent 실행을 반복하지 않는다`() {
        var reject = true; var calls = 0
        val failing = object : TaskSink by sink {
            override fun result(taskId: String, result: StageResult) {
                if (reject) error("서버 응답 유실")
                sink.result(taskId, result)
            }
        }
        val store = ConfigStore(root.resolve("home/runner.json"))
        val config = RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo")))
        val agent = ClaudeAgent { w, t, p, l -> calls++; writes(requirements).run(w, t, p, l).copy(costUsd = 0.05) }
        kotlin.test.assertFailsWith<IllegalStateException> { TaskExecutor(config, failing, root.resolve("worktrees"), store, agent).run(task("REQUIREMENTS")) }
        reject = false
        TaskExecutor(config, failing, root.resolve("worktrees"), store, agent).run(task("REQUIREMENTS", 2))
        assertTrue(assertNotNull(sink.result).passed)
        assertEquals(1, calls)
        assertEquals(1, git(worktree, "log", "--oneline").lines().count { "ilsim(requirements)" in it })
        assertEquals(0.05, sink.result!!.usage.single().costUsd)
    }

    @Test
    fun `실행 파일 설정 오류는 Agent를 기동하기 전에 검출한다`() {
        testCommand = "ilsim_missing_test_executable_123 --test"
        var calls = 0
        val r = run("IMPLEMENTATION") { _, _, _, _ -> calls++; AgentRun(0) }
        assertFalse(r.passed)
        assertEquals("ENVIRONMENT", r.failureKind)
        assertEquals(0, calls)
    }

    @Test
    fun `세션이 없는 일반 실행은 실패해도 다시 하지 않는다`() {
        var calls = 0
        executor { _, _, _, _ -> calls++; AgentRun(2) }.run(task("REQUIREMENTS"))
        assertEquals(1, calls)
    }

    @Test
    fun `검토 보정 정책을 바꾸면 이전 완료 지점으로 검증을 생략하지 않는다`() {
        val marker = root.resolve("ready")
        testCommand = "test -f '$marker' || exit 127"
        var calls = 0
        val agent = ClaudeAgent { w, t, p, l -> calls++; writes("src/Main.java" to "class Main {}").run(w, t, p, l) }
        assertFalse(run("IMPLEMENTATION", agent = agent).passed)
        marker.writeText("ready")
        executor(agent).run(task("IMPLEMENTATION", 2).copy(reviewPolicy = ReviewPolicySnapshot(version = "rollback-policy")))
        assertTrue(assertNotNull(sink.result).passed)
        assertEquals(2, calls)
    }
}
