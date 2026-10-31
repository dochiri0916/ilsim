package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.AgentTaskRequest
import com.dochiri.ilsim.runner.AgentTaskServer
import com.dochiri.ilsim.runner.AgentTaskState
import com.dochiri.ilsim.runner.AssignedTask
import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.PollRequest
import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.RunnerConfig
import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.StageResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpTest {
    private class FakeServer : AgentTaskServer {
        val stages = listOf("REQUIREMENTS", "TEST_CASES", "TEST_CODE", "IMPLEMENTATION")
        var index = 0
        var status = "PENDING"
        var executions = 0
        val results = mutableListOf<StageResult>()
        val events = mutableListOf<String>()
        val registered = mutableListOf<PollRequest>()

        override fun register(request: PollRequest) { registered += request }
        override fun createAgentTask(request: AgentTaskRequest) = AgentTaskState("task-1234567890", request.projectId, request.feature ?: "slug", "ACTIVE", "REQUIREMENTS", "PENDING")
        override fun claim(taskId: String, runnerId: String): AssignedTask? {
            if (blocked || index >= stages.size || status == "WAITING_APPROVAL") return null
            if (status == "PENDING") { status = "RUNNING"; executions++ }
            return AssignedTask(taskId, "p", stages[index], 1, "slug", "claude-app", "user", "${stages[index]} 지시", executionId = "e$executions")
        }
        override fun agentTask(taskId: String) = AgentTaskState(taskId, "p", "slug", if (cancelled != null) "CANCELLED" else if (blocked) "BLOCKED" else if (index >= stages.size) "COMPLETED" else "ACTIVE",
            stages.getOrNull(index), if (index >= stages.size) null else status)
        override fun event(taskId: String, stage: String, type: String, data: String) { events += "$type:$data" }
        override fun result(taskId: String, result: StageResult) {
            results += result
            if (!result.passed) { if (!result.retryable) blocked = true; return }
            status = if (result.stage in setOf("REQUIREMENTS", "TEST_CASES")) "WAITING_APPROVAL" else "PENDING"
            if (status == "PENDING") index++
        }
        var feedback: String? = null
        override fun approve(taskId: String) { index++; status = "PENDING" }
        override fun reject(taskId: String, feedback: String) { this.feedback = feedback; status = "PENDING" }
        var blocked = false
        var instruction: String? = null
        override fun retry(taskId: String, instruction: String?) { this.instruction = instruction; blocked = false; status = "PENDING" }
        var cancelled: String? = null
        override fun cancel(taskId: String, reason: String) { cancelled = reason }
        var reopened: Pair<String, String>? = null
        override fun reopen(taskId: String, stage: String, feedback: String) {
            check(stages.indexOf(stage) in 0 until index) { "통과한 단계가 아닙니다" }
            reopened = stage to feedback; index = stages.indexOf(stage); status = "PENDING"
        }
    }

    private lateinit var home: Path
    private lateinit var repo: Path
    private lateinit var store: ConfigStore
    private lateinit var active: ActiveTasks
    private val server = FakeServer()
    private val pass: (String, String) -> String = { _, _ -> """{"verdict":"PASS","findings":[]}""" }

    private val work: Path get() = active.load(repo)?.worktree?.let { Path.of(it) } ?: repo

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ilsim-mcp-home")
        repo = Files.createTempDirectory("ilsim-mcp-repo").toRealPath()
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        repo.resolve("README.md").writeText("demo\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init")
        store = ConfigStore(home.resolve(".ilsim/runner.json"))
        store.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo"))))
        active = ActiveTasks(home.resolve(".ilsim/active"))
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        repo.toFile().deleteRecursively()
    }

    private var jevAnswer: (String) -> Double? = { null }
    private var jevByBody: (String, String) -> Double? = { _, _ -> null }

    private fun jev(): com.dochiri.ilsim.runner.jev.Jev {
        val answer = jevAnswer
        val byBody = jevByBody
        val probe = answer("m0") ?: return com.dochiri.ilsim.runner.jev.Jev(null)
        val transport = object : io.github.gudcks0305.jev.spi.JevTransport {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            override fun post(uri: java.net.URI, headers: Map<String, String>, body: com.fasterxml.jackson.databind.JsonNode): java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> {
                val answers = mapper.createObjectNode()
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", byBody(id, body.toString()) ?: answer(id) ?: probe) }
                return java.util.concurrent.CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev").set("answers", answers))
            }
            override fun close() {}
        }
        return com.dochiri.ilsim.runner.jev.Jev(io.github.gudcks0305.jev.typesafe.TypeSafeJevClient.builder().apiKey("k").transport(transport).build())
    }

    private fun tools(dir: Path = repo) = IlsimMcp(store, server, active, dir, pass, ::jev).tools().associateBy { it.name }

    private fun call(name: String, args: String = "{}", dir: Path = repo): String =
        tools(dir).getValue(name).call(Json.parseToJsonElement(args).jsonObject)

    private fun hook(event: String, payload: String) = Hooks.handle(event, payload, store, active)

    private fun edit(path: String) = hook("pre-tool-use", """{"cwd":"$repo","tool_name":"Write","tool_input":{"file_path":"$repo/$path"}}""")

    private fun doc(text: String, summary: String? = null) = buildJsonObject { put("document", text); summary?.let { put("summary", it) } }.toString()

    @Test
    fun `작업을 시작하면 브랜치를 만들고 단계를 통과해야 커밋·보고한다`() {
        assertContains(call("next_step"), "진행 중인 일심 작업이 없습니다")
        assertContains(edit("README.md").stdout, "start_task")

        call("analyze_project")
        val started = call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertContains(started, "일심 단계: REQUIREMENTS")
        assertContains(started, "submit_result 의 document")
        assertEquals("ilsim/slug-task-123", Shell.gitOk(work, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals(repo.resolve(".ilsim/worktrees/slug-task-123"), work)
        assertEquals("main", Shell.gitOk(repo, "rev-parse", "--abbrev-ref", "HEAD").trim(), "사용자 작업 트리는 브랜치를 바꾸지 않는다")
        assertTrue(Shell.gitOk(repo, "status", "--porcelain").isBlank(), "작업 폴더는 사용자 작업 트리 상태에 보이지 않는다")
        assertContains(started, "작업 폴더")
        assertEquals("p", server.registered.single().projects.single().id)

        assertContains(call("submit_result"), "document 에 넣어 제출합니다")
        assertContains(edit("src/Main.java").stdout, "deny")
        assertContains(edit("specs/slug/requirements.md").stdout, "deny")

        work.resolve("src").createDirectories()
        work.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("submit_result", doc("# 슬러그\n\nREQ-1 소문자로 바꾼다")), "허용되지 않은 변경: src/Main.java")
        assertTrue(Shell.gitOk(work, "diff", "--cached", "--name-only").isBlank(), "실패한 제출은 스테이징을 남기지 않는다")
        assertContains(hook("stop", """{"cwd":"$repo"}""").stdout, "\"decision\":\"block\"")

        work.resolve("src/Main.java").deleteExisting()
        val submitted = call("submit_result", doc("# 슬러그\n\nREQ-1 소문자로 바꾼다", "요구사항 작성"))
        assertContains(submitted, "통과(REQUIREMENTS)")
        assertContains(submitted, "문서를 저장했습니다")
        assertContains(submitted, "사용자 승인을 기다립니다")
        assertContains(submitted, "REQ-1 소문자로 바꾼다")
        assertEquals("init", Shell.gitOk(repo, "log", "-1", "--format=%s").trim(), "문서 단계는 프로젝트에 커밋하지 않는다")
        assertTrue(Shell.gitOk(repo, "status", "--porcelain").isBlank())
        assertEquals("# 슬러그\n\nREQ-1 소문자로 바꾼다\n", home.resolve(".ilsim/projects/p/specs/slug-task-123/requirements.md").readText())
        assertEquals("# 슬러그\n\nREQ-1 소문자로 바꾼다", server.results.single().artifacts["specs/slug/requirements.md"])
        assertEquals(true, server.results.single().passed)
        assertEquals("e1", server.results.single().executionId)
        assertContains(server.events, "text:요구사항 작성")
        assertContains(submitted, "approve_stage")
        assertContains(edit("specs/slug/requirements.md").stdout, "승인을 기다리는")
        assertEquals("", hook("stop", """{"cwd":"$repo"}""").stdout)
        assertContains(hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__plugin_ilsim_ilsim__approve_stage","tool_input":{"stage":"REQUIREMENTS"}}""").stdout, "\"permissionDecision\":\"ask\"")

        assertContains(runCatching { call("approve_stage", """{"stage":"TEST_CASES"}""") }.exceptionOrNull()!!.message!!, "승인 대기 중이 아닙니다")
        val rejected = call("reject_stage", """{"stage":"REQUIREMENTS","feedback":"REQ-2 대문자 처리 추가"}""")
        assertContains(rejected, "일심 단계: REQUIREMENTS")
        assertContains(rejected, "지금까지 작성한 요구사항 문서")
        assertEquals("REQ-2 대문자 처리 추가", server.feedback)
        val decisions = java.nio.file.Files.walk(home.resolve(".ilsim")).use { w -> w.filter { it.fileName.toString() == "decisions.json" }.toList() }
        assertContains(decisions.single().toFile().readText(), "REQ-2 대문자 처리 추가", message = "거절 사유는 사용자 결정으로 남아 지침 판정이 따른다")
        assertContains(call("submit_result", doc("# 슬러그\n\nREQ-1 소문자로 바꾼다\nREQ-2 대문자도 처리한다")), "통과(REQUIREMENTS)")
        val cases = call("approve_stage", """{"stage":"REQUIREMENTS"}""")
        assertContains(cases, "일심 단계: TEST_CASES")
        assertContains(cases, "REQ-2 대문자도 처리한다")
        assertContains(edit("specs/slug/test-spec.md").stdout, "deny")
        assertTrue(!repo.resolve("specs").toFile().exists(), "프로젝트 안에 일심 산출물을 만들지 않는다")
    }

    @Test
    fun `프로젝트 분석이 없거나 분석 뒤 문서가 바뀌면 작업을 시작하지 않고 분석부터 하게 한다`() {
        val missing = runCatching { call("start_task", """{"requirement":"x"}""") }.exceptionOrNull()!!.message!!
        assertContains(missing, "아키텍처 분석이 없습니다")
        assertContains(missing, "analyze_project를 먼저 실행")
        assertContains(call("analyze_project"), "[문서 지침 검증기]")
        assertContains(call("start_task", """{"requirement":"x","feature":"slug"}"""), "작업을 만들었습니다")
    }

    @Test
    fun `커밋하지 않은 변경이 있거나 등록되지 않은 저장소면 시작하지 않는다`() {
        repo.resolve("README.md").writeText("changed\n")
        assertContains(runCatching { call("start_task", """{"requirement":"x"}""") }.exceptionOrNull()!!.message!!, "커밋하지 않은 변경")
        val other = Files.createTempDirectory("ilsim-mcp-other")
        try {
            Shell.gitOk(other, "init", "-q")
            assertContains(runCatching { call("next_step", dir = other) }.exceptionOrNull()!!.message!!, "등록되지 않은 프로젝트")
            assertEquals("", hook("pre-tool-use", """{"cwd":"$other","tool_name":"Write","tool_input":{"file_path":"$other/a.txt"}}""").stdout)
        } finally { other.toFile().deleteRecursively() }
    }

    @Test
    fun `Bash 의 Git 커밋·되돌리기와 작업 중 병합·푸시, 작업 없는 저장소 안 파일 쓰기를 막고 종료 차단은 턴마다 한 번이며 상한이 있다`() {
        fun bash(command: String) = hook("pre-tool-use", buildJsonObject {
            put("cwd", repo.toString())
            put("tool_name", "Bash")
            putJsonObject("tool_input") { put("command", command) }
        }.toString()).stdout
        assertContains(bash("git commit -am x"), "deny")
        assertContains(bash("echo hi > README.md"), "deny")
        assertEquals("", bash("./gradlew test 2>&1 > /dev/null"))
        assertEquals("", bash("git status && git diff"))
        assertEquals("", bash("git log --oneline -5 > /tmp/ilsim-log.txt && git stash list && git show --stat HEAD"))
        assertEquals("", bash("git merge --no-ff ilsim/slug-1 && git push origin main && git checkout main && git branch -d ilsim/slug-1"))
        assertContains(bash("git log > $repo/log.txt"), "deny")
        assertContains(bash("git stash"), "deny")
        assertContains(bash("git reset --hard HEAD~1"), "deny")

        active.save(repo, ActiveTask("t", "p", "slug", "b", "IMPLEMENTATION", IlsimMcp.RUNNING))
        assertEquals("", bash("echo hi > src/A.java"))
        assertContains(bash("git push"), "deny")
        assertContains(bash("git merge ilsim/other"), "진행 중인 일심 작업")
        assertContains(bash("git checkout main"), "deny")
        repo.resolve("README.md").writeText("changed\n")
        assertEquals("", hook("stop", """{"cwd":"$repo","stop_hook_active":true}""").stdout, "같은 턴에서 이미 막았으면 보고하고 멈추게 둔다")
        repeat(Hooks.MAX_STOP_BLOCKS) { assertContains(hook("stop", """{"cwd":"$repo","stop_hook_active":false}""").stdout, "block") }
        assertEquals("", hook("stop", """{"cwd":"$repo"}""").stdout)
        assertContains(hook("user-prompt-submit", """{"cwd":"$repo"}""").stdout, "단계 IMPLEMENTATION")
        assertNull(ActiveTasks(home.resolve("none")).load(repo))
    }

    @Test
    fun `변경 검사·규칙 조회·차단 작업 재시도와 커밋 차단 훅을 제공한다`() {
        assertContains(call("get_rules"), "쓰지 말아야 할 위험 패턴")
        assertContains(call("review_rules"), "저장된 규칙이 없습니다")
        assertContains(runCatching { call("decide_rules", """{"ids":["nope"],"decision":"approve"}""") }.exceptionOrNull()!!.message!!, "알 수 없는 규칙 id")
        assertContains(hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__plugin_ilsim_ilsim__decide_rules","tool_input":{"ids":["a","b"],"decision":"reject"}}""").stdout, "\"permissionDecision\":\"ask\"")
        call("analyze_project")
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        val hook = repo.resolve(".git/hooks/pre-commit")
        assertContains(hook.toFile().readText(), Workspace.HOOK_MARK)
        assertContains(hook.toFile().readText(), "hook commit")
        assertTrue(hook.toFile().canExecute())

        work.resolve("src").createDirectories()
        work.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("check_change"), "허용되지 않은 변경: src/Main.java")
        work.resolve("src/Main.java").deleteExisting()
        assertContains(call("check_change"), "문제 없음")

        assertContains(runCatching { call("retry_task") }.exceptionOrNull()!!.message!!, "차단된 작업이 아닙니다")
        server.blocked = true
        assertContains(call("next_step"), "retry_task")
        assertContains(call("retry_task", """{"instruction":"범위를 줄여서"}"""), "일심 단계: REQUIREMENTS")
        assertEquals("범위를 줄여서", server.instruction)

        assertEquals(1, Hooks.commit(repo, store, mapOf("CLAUDECODE" to "1")).exit)
        assertEquals(0, Hooks.commit(repo, store, emptyMap()).exit)
        assertEquals(0, Hooks.commit(Files.createTempDirectory("ilsim-other"), store, mapOf("CLAUDECODE" to "1")).exit)
        repo.resolve(".git/hooks/pre-commit").writeText("#!/bin/sh\necho mine\n")
        assertContains(Workspace.of(store.load(), repo)!!.installCommitHook("ilsim-runner")!!, "기존 pre-commit 훅")
    }

    @Test
    fun `통과한 테스트 코드 단계로 되돌리면 구현 변경을 보관했다가 구현 단계로 돌아올 때 되살린다`() {
        call("analyze_project")
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        server.index = server.stages.indexOf("IMPLEMENTATION"); server.status = "PENDING"
        assertContains(call("next_step"), "일심 단계: IMPLEMENTATION")
        work.resolve("src").createDirectories()
        work.resolve("src/Main.java").writeText("class Main {}\n")

        assertContains(hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__plugin_ilsim_ilsim__reopen_stage","tool_input":{"stage":"TEST_CODE","feedback":"TC-4"}}""").stdout, "\"permissionDecision\":\"ask\"")
        assertContains(runCatching { call("reopen_stage", """{"stage":"IMPLEMENTATION","feedback":"x"}""") }.exceptionOrNull()!!.message!!, "통과한 단계가 아닙니다")
        assertTrue(work.resolve("src/Main.java").exists(), "되돌리기에 실패하면 변경을 그대로 둔다")

        val reopened = call("reopen_stage", """{"stage":"TEST_CODE","feedback":"TC-4 기대값을 고친다"}""")
        assertContains(reopened, "일심 단계: TEST_CODE")
        assertContains(reopened, "보관했습니다")
        assertEquals("TEST_CODE" to "TC-4 기대값을 고친다", server.reopened)
        assertTrue(!work.resolve("src/Main.java").exists())
        assertEquals("IMPLEMENTATION", active.load(repo)!!.shelvedStage)

        server.index = server.stages.indexOf("IMPLEMENTATION"); server.status = "PENDING"
        assertContains(call("next_step"), "되살렸습니다")
        assertEquals("class Main {}\n", work.resolve("src/Main.java").readText())
        assertEquals(null, active.load(repo)!!.shelved)
    }

    @Test
    fun `꼭 필요한 독립 검토를 실행하지 못하면 통과로 보지 않고 통과하면 검증 원장을 남긴다`() {
        var failing = true
        val ask: (String, String) -> String = { _, _ -> if (failing) throw IllegalStateException("검토 호출 실패") else """{"verdict":"PASS","findings":[]}""" }
        fun run(name: String, args: String = "{}") = IlsimMcp(store, server, active, repo, ask, ::jev).tools().associateBy { it.name }.getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 소문자 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("class SlugTest { // TC-1\n}\n")

        work.resolve("src/test/SlugTest.java").writeText("class SlugTest {}\n")
        val untraced = run("submit_result")
        assertContains(untraced, "TC 번호가 어느 테스트 이름·주석에도 없습니다")
        assertContains(untraced, "- TC-1 REQ-1 소문자")
        work.resolve("src/test/SlugTest.java").writeText("class SlugTest { // TC-1\n}\n")

        val blocked = run("submit_result")
        assertContains(blocked, "검토를 실행하지 못했습니다")
        assertContains(blocked, "테스트 명령 없음")
        assertEquals("TEST_CODE", server.stages[server.index])

        failing = false
        val passed = run("submit_result")
        assertContains(passed, "통과(TEST_CODE)")
        assertContains(passed, "확인 못 한 항목: ")
        assertContains(passed, "tests=NOT_CONFIGURED(설정 없음)")
        val ledger = server.results.last().artifacts.getValue("verification.md")
        assertContains(ledger, "| TC-1 REQ-1 소문자 | - | REQ-1 | SlugTest.java:1 |")
        assertContains(ledger, "독립 검토: 실행(")

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug {}\n")
        val done = run("submit_result")
        assertContains(done, "작업이 끝났습니다")
        assertContains(done, "## 검증 원장 (TEST_CODE)")
        assertContains(done, "## 검증 원장 (IMPLEMENTATION)")
        assertContains(done, "| REQ-1 소문자로 바꾼다 | - | TC-1 | SlugTest.java:1 |")
    }

    @Test
    fun `구현 단계는 JEV가 누락으로 본 요구사항이 있으면 제출을 실패시키고 확실하면 독립 검토를 건너뛴다`() {
        var audits = 0
        val counting: (String, String) -> String = { _, _ -> audits++; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, counting, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n- REQ-2: 공백을 하이픈으로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |\n| TC-2 | REQ-2 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])
        val before = audits

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        jevAnswer = { id -> if (id == "m1") 0.05 else 0.95 }
        val missing = run("submit_result")
        assertContains(missing, "요구사항이 빠진 것으로 보입니다")
        assertContains(missing, "REQ-2")
        assertEquals(before, audits, "누락이면 검토 전에 실패한다")

        jevAnswer = { id -> if (id.startsWith("v")) 0.05 else 0.95 }
        val passed = run("submit_result")
        assertContains(passed, "requirements=PASSED")
        assertTrue("ai-audit=SKIPPED" in passed || audits == before + 1, "확실하면 생략하거나 표본으로만 검토한다")
        assertTrue(server.results.last().decisions.any { it.purpose == "CHANGE_AUDIT" && it.handler in setOf("SKIP", "SAMPLE") })
        assertTrue(server.results.last().decisions.any { it.purpose == "REQUIREMENT_CHECK" && it.handler == "JEV" })
    }

    @Test
    fun `구현 단계는 요구사항 문서의 영향 범위와 관계없는 파일을 바꾸면 제출을 실패시킨다`() {
        fun mcp() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n\n## 영향 범위\n- `src/main/Slug.java`\n- `src/test/SlugTest.java`")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])
        assertContains(run("next_step"), "영향 범위와 관계없는 파일은 고치지 않는다")

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        work.resolve("src/main/Other.java").writeText("class Other {}\n")
        jevAnswer = { id -> when { id.startsWith("f") -> 0.05; id.startsWith("v") -> 0.05; else -> 0.95 } }
        val outside = run("submit_result")
        assertContains(outside, "영향 범위 밖 변경으로 보입니다")
        assertContains(outside, "src/main/Other.java (범위 안 확률 0.05)")
        assertTrue("src/main/Slug.java" !in outside.substringAfter("영향 범위 밖"), outside)

        assertContains(outside, "record_decision")
        assertContains(run("record_decision", """{"decision":"Other 클래스도 고쳐도 된다: src/main/Other.java"}"""), "사용자 결정을 기록했습니다")
        val passed = run("submit_result")
        assertContains(passed, "scope=PASSED")
        assertTrue(server.results.last().decisions.any { it.purpose == "SCOPE_CHECK" && it.handler == "STATIC" })
    }

    @Test
    fun `승인 뒤 단계는 미결정 사항을 추측해 정하면 제출을 실패시키고 사용자가 답을 기록하면 통과시킨다`() {
        val prompts = mutableListOf<String>()
        val capture: (String, String) -> String = { prompt, _ -> prompts += prompt; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, capture, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        fun failure(name: String, args: String) = runCatching { run(name, args) }.exceptionOrNull()?.message.orEmpty()
        jevAnswer = { id -> if (id[0] in "uv") 0.05 else 0.95 }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        val spec = "# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n\n## 영향 범위\n- `src/main/Slug.java`\n- `src/test/SlugTest.java`\n\n## 미결정 사항\n- Q1. 빈 문자열이면 무엇을 돌려주는가?\n- Q2. null 이면 예외를 던지는가?\n"
        assertContains(run("submit_result", doc(spec)), "나중에 답하면 record_decision으로 기록")
        assertContains(failure("record_decision", """{"question":"Q1","decision":"빈 문자열"}"""), "reject_stage")
        server.approve("t")

        val cases = "| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |\n| TC-2 | REQ-1 | 경계값 | of(\"\") | \"\" |"
        jevAnswer = { id -> when { id == "u0" -> 0.9; id[0] in "uv" -> 0.05; else -> 0.95 } }
        val guessed = run("submit_result", doc(cases))
        assertContains(guessed, "미결정 사항을 사용자 결정 없이 정한 것으로 보입니다")
        assertContains(guessed, "- Q1. 빈 문자열이면 무엇을 돌려주는가? (정함 확률 0.90)")
        assertTrue(server.events.any { it.startsWith("log:제출 실패: 요구사항 문서의 미결정 사항") && "(정함 확률 0.90)" in it }, "막힌 제출의 판정 확률을 로그에 남긴다")
        assertTrue("Q2. null" !in guessed, guessed)
        assertTrue(server.results.size == 1, "추측이면 서버에 보고하지 않는다")

        val confirm = hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__ilsim__record_decision","tool_input":{"question":"Q1","decision":"빈 문자열을 돌려준다"}}""")
        assertContains(confirm.stdout, "\"ask\"")
        assertContains(confirm.stdout, "사용자 결정 기록 (Q1): 빈 문자열을 돌려준다")
        assertContains(failure("record_decision", """{"question":"Q9","decision":"x"}"""), "Q9 가 없습니다")
        assertContains(run("record_decision", """{"question":"q1","decision":"빈 문자열을 돌려준다"}"""), "남은 미결정 질문: Q2")
        assertTrue(server.events.any { it == "log:사용자 결정 기록: Q1: 빈 문자열을 돌려준다" })

        jevAnswer = { id -> when { id == "u0" -> 0.5; id[0] in "vb" -> 0.05; else -> 0.95 } }
        assertContains(run("submit_result", doc(cases)), "undecided=UNCERTAIN")
        val waiting = run("next_step")
        assertContains(waiting, "사용자 결정 없이 정했는지 일심이 확신하지 못했습니다")
        assertContains(waiting, "- Q2. null 이면 예외를 던지는가? (정함 확률 0.50)")
        server.approve("t")

        val testing = run("next_step")
        assertContains(testing, "## 사용자가 정한 사항")
        assertContains(testing, "- Q1: 빈 문자열을 돌려준다")
        assertContains(testing, "## 정하지 않은 사항")
        assertTrue("- Q1." !in testing.substringAfter("## 정하지 않은 사항"), testing)
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest {}\n")
        assertContains(run("submit_result"), "undecided=UNCERTAIN")
        assertContains(prompts.last(), "- GUESS:")
        assertContains(prompts.last(), "## 정하지 않은 사항\n- Q2. null 이면 예외를 던지는가?")
        assertContains(prompts.last(), "## 사용자가 정한 사항")

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { if (s == null) throw new IllegalArgumentException(); return s.toLowerCase(); } }\n")
        jevAnswer = { id -> when { id == "u0" -> 0.85; id.startsWith("v") -> 0.05; else -> 0.95 } }
        val implemented = run("submit_result")
        assertContains(implemented, "- Q2. null 이면 예외를 던지는가? (정함 확률 0.85)")
        assertTrue(server.results.last().stage == "TEST_CODE")

        val recorded = run("record_decision", """{"question":"Q2","decision":"null 이면 IllegalArgumentException"}""")
        assertContains(recorded, "남은 미결정 질문: 없음")
        assertContains(recorded, "reopen_stage(TEST_CODE)로 테스트를 더할지")
        val passed = run("submit_result")
        assertContains(passed, "통과(IMPLEMENTATION)")
        assertTrue("undecided=" !in passed.substringBefore("\n"), passed)
        assertTrue(server.results.last().decisions.none { it.purpose == "UNDECIDED_CHECK" })
        assertTrue(server.results.dropLast(1).last().decisions.any { it.purpose == "UNDECIDED_CHECK" && it.handler == "JEV" })
    }

    @Test
    fun `테스트 코드 단계는 JEV가 TC 누락이나 검증 없이 통과하는 테스트를 보면 막고 확실하면 독립 검토를 건너뛴다`() {
        var audits = 0
        val counting: (String, String) -> String = { _, _ -> audits++; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, counting, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n\n## 영향 범위\n- `src/test/SlugTest.java`")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |\n| TC-2 | REQ-1 | 정상(정적 검사) | 구현 확인 | toLowerCase 를 쓴다 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest {}\n")

        jevAnswer = { id -> when { id == "t0" -> 0.05; id[0] in "uvb" -> 0.05; else -> 0.95 } }
        val missing = run("submit_result")
        assertContains(missing, "시나리오 테스트가 없는 것으로 보입니다")
        assertContains(missing, "- TC-1 ")
        assertTrue("TC-2" !in missing, "정적 검사 TC는 테스트를 요구하지 않는다")
        jevAnswer = { id -> when { id == "b0_2" -> 0.9; id[0] in "uvb" -> 0.05; else -> 0.95 } }
        assertContains(run("submit_result"), "- src/test/SlugTest.java (우회 확률 0.90)")
        assertEquals(0, audits, "막히면 독립 검토를 부르지 않는다")

        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        val passed = run("submit_result")
        assertContains(passed, "test-cases=PASSED")
        assertContains(passed, "test-bypass=PASSED")
        assertTrue("ai-audit=SKIPPED" in passed || audits == 1, "확실하면 생략하거나 표본으로만 검토한다")
        assertTrue(server.results.last().decisions.any { it.purpose == "TESTCODE_CHECK" && it.handler == "JEV" && "정적 검사·기존 테스트 회귀 1개 제외" in it.reason })
        assertTrue(server.results.last().decisions.any { it.purpose == "CHANGE_AUDIT" && it.handler in setOf("SKIP", "SAMPLE") })
    }

    @Test
    fun `되돌린 테스트 코드 단계도 앞서 커밋한 테스트까지 보고 TC 누락을 판정한다`() {
        fun mcp() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        jevByBody = { id, body -> if (id[0] == 't') (if ("alphaMarker" in body) 0.95 else 0.05) else null }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertTrue(active.load(repo)?.base?.isNotBlank() == true, "작업 시작 커밋을 저장한다")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest { void alphaMarker() {} }\n")
        work.resolve("src/test/OtherTest.java").writeText("class OtherTest {}\n")
        assertContains(run("submit_result"), "test-cases=PASSED")
        assertEquals("IMPLEMENTATION", server.stages[server.index])

        run("reopen_stage", """{"stage":"TEST_CODE","feedback":"다른 테스트 이름을 고친다"}""")
        work.resolve("src/test/OtherTest.java").writeText("class OtherTest { void renamed() {} }\n")
        assertContains(run("submit_result"), "test-cases=PASSED")
    }

    @Test
    fun `구현 단계에서 애매한 요구사항도 연결된 TC 테스트가 있고 테스트가 통과하면 독립 검토 사유에서 뺀다`() {
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        var audits = 0
        val counting: (String, String) -> String = { _, _ -> audits++; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, counting, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n- REQ-2: 공백을 하이픈으로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |\n| TC-2 | REQ-2 | 정상 | of(\"a b\") | \"a-b\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest { void lower() {} void hyphen() {} }\n")
        assertContains(run("submit_result"), "tests=RED")
        val before = audits

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase().replace(' ', '-'); } }\n")
        jevByBody = { id, body -> if (id.startsWith("m") && "REQ-2" in body && (id == "m1" || "REQ-1" !in body)) 0.6 else null }
        val passed = run("submit_result")
        assertContains(passed, "tests=PASSED")
        assertContains(passed, "requirements=UNCERTAIN")
        val decisions = server.results.last().decisions
        assertTrue(decisions.any { it.purpose == "REQUIREMENT_TESTS" && "REQ-2" in it.reason.substringBefore("(") }, decisions.toString())
        assertTrue(decisions.none { it.purpose == "CHANGE_AUDIT" && "판정이 불확실한 요구사항" in it.reason })
        assertTrue("ai-audit=SKIPPED" in passed || audits == before + 1)
    }

    @Test
    fun `요구사항과 관계없어 보이는 변경 묶음은 JEV 판정으로 막는다`() {
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        val prompts = mutableListOf<String>()
        val reviewer: (String, String) -> String = { prompt, _ -> prompts += prompt; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, reviewer, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest { void lower() {} }\n")
        assertContains(run("submit_result"), "tests=RED")

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.trim().toLowerCase(); } }\n")
        jevAnswer = { id -> when { id == "c0" -> 0.05; id[0] in "uvb" -> 0.05; else -> 0.95 } }
        val failed = run("submit_result")
        assertContains(failed, "요구사항과 관계없는 변경으로 보입니다")
        assertContains(failed, "- src/main/Slug.java (범위 안 확률 0.05)")
        assertContains(failed, "  +class Slug { String of(String s) { return s.trim().toLowerCase(); } }")
        assertTrue(prompts.isEmpty(), "JEV가 막으면 독립 검토를 부르지 않는다")

        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        val passed = run("submit_result")
        assertContains(passed, "scope-change=PASSED")
        val decisions = server.results.last().decisions
        assertTrue(decisions.any { it.purpose == "SCOPE_CHANGE_CHECK" && it.handler == "JEV" && "Slug.java=0.95" in it.reason }, decisions.toString())
    }

    @Test
    fun `기존 코드를 고친 애매한 변경 묶음은 다시 물은 뒤 독립 검토로 올린다`() {
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        repo.resolve("src/main").createDirectories()
        repo.resolve("src/main/Text.java").writeText("class Text {\n    String trim(String s) { return s.trim(); }\n}\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "text")
        val prompts = mutableListOf<String>()
        val reviewer: (String, String) -> String = { prompt, _ -> prompts += prompt; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, reviewer, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest { void lower() {} }\n")
        assertContains(run("submit_result"), "tests=RED")

        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        work.resolve("src/main/Text.java").writeText("class Text {\n    String trim(String s) { return s.strip(); }\n}\n")
        jevAnswer = { id -> when { id[0] == 'c' -> 0.5; id[0] in "uvb" -> 0.05; else -> 0.95 } }
        assertContains(run("submit_result"), "scope-change=UNCERTAIN")
        val decisions = server.results.last().decisions
        assertTrue(decisions.any { it.purpose == "CHANGE_AUDIT" && "애매한 기존 코드 수정 1개" in it.reason }, decisions.toString())
        assertTrue(prompts.any { "요구사항에 필요한지 애매한 기존 코드 수정" in it && "Text.java (범위 안 확률 0.50)" in it && "Slug.java (범위" !in it.substringAfter("애매한 기존 코드 수정") }, prompts.joinToString("\n---\n").take(3000))
    }

    @Test
    fun `JEV를 못 쓰면 독립 검토에 이 프로젝트 문서 구간 전체를 넣는다`() {
        store.save(store.load().let { c -> c.copy(projects = mapOf("p" to c.projects.getValue("p").copy(testCommand = "test -f src/main/Slug.java"))) })
        repo.resolve("AGENTS.md").writeText("# 규칙\n\n## 작업 원칙\n\n- 요청 범위 밖 리팩터링이나 검증 규칙 완화를 하지 않는다.\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        val prompts = mutableListOf<String>()
        val reviewer: (String, String) -> String = { prompt, _ -> prompts += prompt; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, reviewer, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        jevAnswer = { id -> if (id[0] in "uvb") 0.05 else 0.95 }
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        jevAnswer = { null }
        run("submit_result", doc("| TC-1 | REQ-1 | 정상 | of(\"A\") | \"a\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest { void lower() {} }\n")
        assertContains(run("submit_result"), "tests=RED")
        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")

        assertContains(run("submit_result"), "tests=PASSED")
        val prompt = prompts.last()
        assertContains(prompt, "JEV를 쓸 수 없어 일심이 이 작업에 적용할 지침을 고르지 못했습니다")
        assertContains(prompt, "### AGENTS.md:")
        assertContains(prompt, "- 요청 범위 밖 리팩터링이나 검증 규칙 완화를 하지 않는다.")
    }

    @Test
    fun `TC-18 구현 단계 diff가 입력 한도로 잘리면 누락 대신 불확실로 기록한다`() {
        fun mcp() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/SlugTest.java").writeText("// TC-1 TC-2 TC-3 TC-4\nclass SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])

        work.resolve("src/main").createDirectories()
        work.resolve("src/main/Slug.java").writeText("class Slug {\n" + "    int value = 1;\n".repeat(2000) + "}\n")
        jevAnswer = { id -> when { id == "m0" -> 0.1; id[0] in "fc" -> 0.95; else -> 0.05 } }
        val submitted = run("submit_result")
        assertTrue("요구사항이 빠진 것으로 보입니다" !in submitted, submitted)
        assertEquals("UNCERTAIN", server.results.last().validation["requirements"])
        assertTrue(server.results.last().decisions.any { it.purpose == "REQUIREMENT_CHECK" && "diff 일부만 판정" in it.reason })
    }

    @Test
    fun `그 프로젝트 문서에서 JEV가 고른 지침만 단계 지시에 넣고 어기면 제출을 실패시킨다`() {
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/architecture.md").writeText("# 아키텍처\n\n## 계층\n도메인은 어댑터를 의존하지 않는다.\n\n## 설치\nbrew install demo\n")
        repo.resolve("sub").createDirectories()
        repo.resolve("sub/GUIDE.md").writeText("# 다른 프로젝트\n\n다른 프로젝트 지침\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        store.save(store.load().let { it.copy(projects = it.projects + ("q" to ProjectEntry(repo.resolve("sub").toString(), "other"))) })
        var violation = 0.95
        jevAnswer = { 0.9 }
        jevByBody = { id, body -> when {
            id.startsWith("v") -> violation
            id.startsWith("x") -> 0.05
            id[0] in "km" && "README.md" in body -> 0.05
            id == "s1" && "brew install" in body -> 0.05
            else -> null
        } }

        call("analyze_project")
        val started = call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertContains(started, "## 프로젝트 문서 지침")
        assertContains(started, "docs/architecture.md:3 — 아키텍처 > 계층")
        assertContains(started, "도메인은 어댑터를 의존하지 않는다")
        assertTrue("brew install" !in started && "다른 프로젝트 지침" !in started && "demo\n" !in started.substringAfter("## 프로젝트 문서 지침"))
        assertContains(call("get_guidelines"), "도메인은 어댑터를 의존하지 않는다")

        val failed = call("submit_result", doc("# 슬러그\n\n- REQ-1: 도메인이 어댑터를 직접 호출한다"))
        assertContains(failed, "문서 지침을 어긴 것으로 보입니다")
        assertContains(failed, "docs/architecture.md:3")
        assertTrue(server.results.isEmpty())

        violation = 0.05
        assertContains(call("submit_result", doc("# 슬러그\n\n- REQ-1: 도메인이 어댑터를 직접 호출한다")), "guidelines=PASSED")
        assertTrue(server.results.last().decisions.any { it.purpose == "GUIDELINE_CHECK" && it.handler == "JEV" })
        val selection = server.results.last().decisions.single { it.purpose == "GUIDELINE_SELECT" }
        assertContains(selection.reason, "docs/architecture.md:3=0.90")
        assertContains(selection.reason, "docs/architecture.md:6=0.05")
        assertTrue("준수를 확인하지 못했습니다" !in call("next_step"))
        assertContains(started, "불합리하거나 이상해 보여도")

        call("reject_stage", """{"stage":"REQUIREMENTS","feedback":"다시"}""")
        violation = 0.6
        assertContains(call("submit_result", doc("# 슬러그\n\n- REQ-1: 도메인이 어댑터를 직접 호출하지 않는다")), "guidelines=UNCERTAIN")
        val waiting = call("next_step")
        assertContains(waiting, "준수를 확인하지 못했습니다")
        assertContains(waiting, "docs/architecture.md:3 — 아키텍처 > 계층 (위반 확률 0.60)")
    }

    @Test
    fun `요구사항 단계 뒤에는 승인된 요구사항 문서로 지침을 다시 고르고 정하지 않은 사항은 알리고 추측하지 말라고 한다`() {
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/guide.md").writeText("# 지침\n\n## 결제\n결제 금액은 Money 로 다룬다.\n\n## 배송\n배송비는 정수 원 단위로 다룬다.\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        jevAnswer = { 0.9 }
        jevByBody = { id, body -> when {
            id.startsWith("v") -> 0.05
            id[0] in "km" && "README.md" in body -> 0.05
            id == "s1" && "영향 범위: 배송" !in body -> 0.05
            else -> null
        } }

        call("analyze_project")
        val started = call("start_task", """{"requirement":"결제 금액 계산을 고친다","feature":"slug"}""")
        assertContains(started, "결제 금액은 Money 로 다룬다")
        assertTrue("배송비는 정수 원 단위로 다룬다" !in started)

        val spec = "# 결제·배송\n\n영향 범위: 배송\n\n- REQ-1: 배송비를 계산한다\n\n## 미결정 사항\n- Q1: 무료 배송 기준 금액은?\n- Q2: 도서 지역 추가 요금은?\n"
        val waiting = call("submit_result", doc(spec))
        assertContains(waiting, "사용자가 정하지 않은 사항이 2개")
        assertContains(waiting, "- Q1: 무료 배송 기준 금액은?")

        val cases = call("approve_stage", """{"stage":"REQUIREMENTS"}""")
        assertContains(cases, "일심 단계: TEST_CASES")
        assertContains(cases, "승인된 요구사항 문서 기준")
        assertContains(cases, "배송비는 정수 원 단위로 다룬다")
        assertContains(cases, "## 정하지 않은 사항")
        assertContains(cases, "- Q2: 도서 지역 추가 요금은?")
    }

    @Test
    fun `사용자가 기록한 결정은 지침 판정 맥락으로 들어가 허락한 부분은 막지 않는다`() {
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/spec.md").writeText("# 스펙\n\n## 문구\n새 인사 문구는 마침표로 끝난다.\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        jevAnswer = { 0.9 }
        jevByBody = { id, body -> when {
            id[0] == 'v' && "TEST_CODE" in body && "기존 greet 문구는 그대로" !in body -> 0.95
            id[0] in "vb" -> 0.05
            id[0] in "km" && "README.md" in body -> 0.05
            else -> null
        } }
        call("analyze_project")
        call("start_task", """{"requirement":"작별 인사 추가","feature":"slug"}""")
        call("submit_result", doc("# 작별\n\n- REQ-1: 작별 인사를 더한다")); server.approve("t")
        call("submit_result", doc("| TC-1 | REQ-1 | 정상 | bye(\"Kim\") | \"Bye, Kim.\" |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/ByeTest.java").writeText("// TC-1\nclass ByeTest {}\n")
        val blocked = call("submit_result")
        assertContains(blocked, "문서 지침을 어긴 것으로 보입니다")
        assertContains(blocked, "record_decision(허락한 내용)")
        call("record_decision", """{"decision":"기존 greet 문구는 그대로 두는 회귀 테스트를 허락한다"}""")
        assertContains(call("submit_result"), "guidelines=PASSED")
    }

    @Test
    fun `테스트 코드 단계 지침 판정은 승인된 테스트 케이스를 근거로 받아 승인한 테스트 방식을 다시 막지 않는다`() {
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/spec.md").writeText("# 스펙\n\n## 테스트\n삭제된 행은 테스트하지 않는다.\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        jevAnswer = { 0.9 }
        val bodies = mutableListOf<String>()
        jevByBody = { id, body -> when {
            id[0] == 'v' && "TEST_CODE" in body -> { bodies += body; if ("승인된 테스트 케이스 TC-1: REQ-1 | 예외 | 삭제된 행" in body) 0.05 else 0.95 }
            id[0] in "vb" -> 0.05
            id[0] in "km" && "README.md" in body -> 0.05
            else -> null
        } }
        call("analyze_project")
        call("start_task", """{"requirement":"삭제 제외","feature":"slug"}""")
        call("submit_result", doc("# 삭제 제외\n\n- REQ-1: 삭제된 행은 목록에서 뺀다")); server.approve("t")
        call("submit_result", doc("| TC-1 | REQ-1 | 예외 | 삭제된 행 | 목록에 없다 |")); server.approve("t")
        work.resolve("src/test").createDirectories()
        work.resolve("src/test/ListTest.java").writeText("// TC-1\nclass ListTest {}\n")
        assertContains(call("submit_result"), "guidelines=PASSED")
        assertTrue(bodies.isNotEmpty() && bodies.all { "승인된 테스트 케이스 TC-1" in it })
    }

    @Test
    fun `사용자가 그만두라고 하면 작업을 취소하고 작업 폴더를 지운 뒤 새 작업을 시작할 수 있다`() {
        call("analyze_project")
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertContains(hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__plugin_ilsim_ilsim__cancel_task","tool_input":{"reason":"요청 철회"}}""").stdout, "\"permissionDecision\":\"ask\"")
        val folder = work
        assertTrue(folder != repo && folder.toFile().exists())
        val cancelled = call("cancel_task", """{"reason":"요청 철회"}""")
        assertContains(cancelled, "작업을 취소했습니다")
        assertContains(cancelled, "작업 폴더를 지웠습니다")
        assertEquals("요청 철회", server.cancelled)
        assertTrue(!folder.toFile().exists())
        assertContains(runCatching { call("cancel_task", """{"reason":"다시"}""") }.exceptionOrNull()!!.message!!, "진행 중인 일심 작업이 없습니다")
    }

    @Test
    fun `사용자가 지침과 다르게 정한 결정은 위반 판정이 높아도 그 지침을 막지 않는다`() {
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/spec.md").writeText("# 스펙\n\n## 목록\n모든 목록 API는 페이지를 받는다.\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "docs")
        jevAnswer = { 0.9 }
        var violation = 0.05
        jevByBody = { id, body -> when {
            id[0] == 'v' && "REQUIREMENTS" in body -> violation
            id[0] == 'x' -> if ("페이지 적용 안 함" in body) 0.9 else 0.05
            id[0] in "vb" -> 0.05
            id[0] in "km" && "README.md" in body -> 0.05
            else -> null
        } }
        call("analyze_project")
        call("start_task", """{"requirement":"센터 목록 검색","feature":"slug"}""")
        violation = 0.95
        assertContains(call("submit_result", doc("# 검색\n\n- REQ-1: 목록은 배열로 준다")), "문서 지침을 어긴 것으로 보입니다")
        assertContains(call("record_decision", """{"decision":"페이지 적용 안 함, 응답은 배열 그대로"}"""), "사용자 결정을 기록했습니다")
        assertContains(call("submit_result", doc("# 검색\n\n- REQ-1: 목록은 배열로 준다")), "guidelines=PASSED")
        assertTrue(server.results.last().decisions.any { it.purpose == "GUIDELINE_PERMISSION" && "docs/spec.md" in it.reason })
    }

    @Test
    fun `MCP 서버 안내는 모든 도구를 언제 쓰는지 알려 플러그인 스킬 없이 절차를 전한다`() {
        val missing = tools().keys.filterNot { it in IlsimMcp.INSTRUCTIONS }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `MCP 서버는 초기화·도구 목록·호출·오류를 JSON-RPC 로 답한다`() {
        val mcp = McpServer("ilsim", "0.1.0", "안내", listOf(McpTool("echo", "에코", buildJsonObject { put("type", "object") }) { args ->
            args["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("text 가 필요합니다")
        }))
        val init = Json.parseToJsonElement(mcp.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")!!).jsonObject
        assertEquals("2025-03-26", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val list = Json.parseToJsonElement(mcp.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!).jsonObject
        assertEquals("echo", list["result"]!!.jsonObject["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val ok = mcp.handle("""{"jsonrpc":"2.0","id":"a","method":"tools/call","params":{"name":"echo","arguments":{"text":"안녕"}}}""")!!
        assertContains(ok, "\"text\":\"안녕\"")
        assertContains(ok, "\"isError\":false")
        assertContains(mcp.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{}}}""")!!, "\"isError\":true")
        assertContains(mcp.handle("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""")!!, "-32601")
        assertContains(mcp.handle("not json")!!, "-32700")
    }

    @Test
    fun `저장소에 커밋한 정책 상한을 넘으면 작업을 멈추고 사용자가 다시 시도하면 새로 센다`() {
        repo.resolve(".ilsim-policy.json").writeText("""{"maxSubmissions":1}""")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "policy")
        fun mcp() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("analyze_project")
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |")); server.approve("t")
        assertContains(run("submit_result"), "제출 실패(TEST_CODE)")
        assertTrue(server.events.any { it.startsWith("attempt:stage=TEST_CODE;passed=false;cause=path;") }, server.events.toString())
        val stopped = run("submit_result")
        assertContains(stopped, "정책 상한(.ilsim-policy.json)에 걸려 작업을 멈췄습니다: 이 단계에서 실패한 제출 1회(상한 1회)")
        assertTrue(server.blocked)
        assertTrue(server.results.last().let { !it.passed && !it.retryable })
        run("retry_task", """{"instruction":"계속"}""")
        assertContains(run("submit_result"), "제출 실패(TEST_CODE)")
    }

    @Test
    fun `같은 대화에서 이미 준 지침은 다음 지시에서 제목만 주고 새 대화에서는 다시 전부 준다`() {
        fun tools() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        tools().getValue("analyze_project").call(Json.parseToJsonElement("{}").jsonObject)
        val session = tools()
        val first = session.getValue("start_task").call(Json.parseToJsonElement("""{"requirement":"slugify 추가","feature":"slug"}""").jsonObject)
        assertContains(first, "## 프로젝트 문서 지침 (반드시 지킨다)")
        val again = session.getValue("next_step").call(Json.parseToJsonElement("{}").jsonObject)
        assertContains(again, "## 프로젝트 문서 지침 (이 대화에서 이미 받은 것과 같음, 반드시 지킨다)")
        assertTrue("## 프로젝트 문서 지침 (반드시 지킨다)" !in again)
        assertContains(session.getValue("get_guidelines").call(Json.parseToJsonElement("{}").jsonObject), "## 프로젝트 문서 지침 (반드시 지킨다)")
        assertContains(tools().getValue("next_step").call(Json.parseToJsonElement("{}").jsonObject), "## 프로젝트 문서 지침 (반드시 지킨다)")
    }

    @Test
    fun `작업은 사용자 작업 트리와 분리된 작업 폴더에서 하고 훅이 원래 폴더 수정을 작업 폴더로 돌린다`() {
        call("analyze_project")
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        val folder = work
        server.index = server.stages.indexOf("IMPLEMENTATION"); server.status = "PENDING"
        assertContains(call("next_step"), "## 작업 폴더")
        val redirected = hook("pre-tool-use", """{"cwd":"$repo","tool_name":"Write","tool_input":{"file_path":"$repo/src/main/Slug.java"}}""").stdout
        assertContains(redirected, "\"permissionDecision\":\"deny\"")
        assertContains(redirected, "$folder/src/main/Slug.java")
        assertEquals("", hook("pre-tool-use", """{"cwd":"$repo","tool_name":"Write","tool_input":{"file_path":"$folder/src/main/Slug.java"}}""").stdout)
        assertContains(hook("user-prompt-submit", """{"cwd":"$repo"}""").stdout, "작업 폴더 $folder")
        assertEquals(1, Hooks.commit(folder, store, mapOf("CLAUDECODE" to "1")).exit, "작업 폴더에서도 Agent 커밋을 막는다")
        server.index = server.stages.size
        assertContains(call("next_step"), "작업 폴더를 지웠습니다")
        assertTrue(!folder.toFile().exists())
        assertEquals("ilsim/slug-task-123", Shell.gitOk(repo, "branch", "--list", "ilsim/slug-task-123", "--format=%(refname:short)").trim(), "브랜치는 남긴다")
    }
}
