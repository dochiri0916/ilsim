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
import kotlin.test.assertTrue

class TaskCleanupTest {

    private lateinit var root: Path
    private lateinit var repo: Path
    private val sink = object : TaskSink {
        override fun event(taskId: String, stage: String, type: String, data: String) {}
        override fun result(taskId: String, result: StageResult) {}
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ilsim-cleanup")
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

    private fun executor() = TaskExecutor(
        RunnerConfig(projects = mapOf("demo" to ProjectEntry(repo.toString(), "demo"))), sink, root.resolve("worktrees"),
        ConfigStore(root.resolve("home/runner.json")), { _, _, _, _ -> AgentRun(0) }, { _, _ -> "" },
    )

    private fun worktree(taskId: String, branch: String): Path {
        val path = root.resolve("worktrees/demo-${taskId.take(8)}")
        Shell.gitOk(repo, "worktree", "add", "-b", branch, path.toString(), "HEAD")
        path.resolve("scratch.txt").writeText("미완성")
        return path
    }

    private fun branchExists(name: String) = Shell.git(repo, "rev-parse", "--verify", "--quiet", name).exit == 0

    @Test
    fun `끝나지 않은 작업은 worktree 와 브랜치를 지운다`() {
        val path = worktree("aaaaaaaa-1", "ilsim/open-aaaaaaaa")
        executor().cleanup(Cleanup("aaaaaaaa-1", "demo", "open", "ilsim/open-aaaaaaaa", keepBranch = false))
        assertFalse(path.exists())
        assertFalse(branchExists("ilsim/open-aaaaaaaa"))
    }

    @Test
    fun `끝난 작업은 worktree 만 지우고 브랜치는 남긴다`() {
        val path = worktree("bbbbbbbb-2", "ilsim/done-bbbbbbbb")
        executor().cleanup(Cleanup("bbbbbbbb-2", "demo", "done", "ilsim/done-bbbbbbbb", keepBranch = true))
        assertFalse(path.exists())
        assertTrue(branchExists("ilsim/done-bbbbbbbb"))
    }
}
