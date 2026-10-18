package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 요구사항 단계에서 Claude 가 질문에 답만 했으면 파일 변경을 버리고 답한 것으로 보고한다. */
class TaskExecutorAnswerTest {

    private class Sink : TaskSink {
        var result: StageResult? = null
        override fun event(taskId: String, stage: String, type: String, data: String) {}
        override fun result(taskId: String, result: StageResult) { this.result = result }
    }

    private lateinit var root: Path
    private lateinit var repo: Path
    private val sink = Sink()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ilsim-answer")
        repo = root.resolve("repo").also { it.createDirectories() }
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        repo.resolve("README.md").writeText("demo")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun say(text: String) =
        """{"type":"assistant","message":{"content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}}"""

    @Test
    fun `질문에 답만 했으면 만든 파일을 버리고 답한 것으로 보고한다`() {
        val config = RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo")))
        val executor = TaskExecutor(config, sink, root.resolve("worktrees"), ConfigStore(root.resolve("home/runner.json")), { worktree, _, _, onLine ->
            worktree.resolve("notes.md").writeText("메모")
            onLine(say("플로우는 요구사항 → 테스트 케이스 → 테스트 코드 → 구현입니다.\n${Gate.ANSWERED_MARK}"))
            AgentRun(0)
        }, { _, _ -> "" })

        executor.run(AssignedTask("22222222-bbbb", "demo", "REQUIREMENTS", 1, "flow", "claude-sonnet-5-5", "medium", "프롬프트"))

        val r = assertNotNull(sink.result)
        assertTrue(r.passed, r.error)
        assertTrue(r.answered)
        assertFalse(root.resolve("worktrees/demo-22222222/notes.md").exists())
    }
}
