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
        override fun agentTask(taskId: String) = AgentTaskState(taskId, "p", "slug", if (blocked) "BLOCKED" else if (index >= stages.size) "COMPLETED" else "ACTIVE",
            stages.getOrNull(index), if (index >= stages.size) null else status)
        override fun event(taskId: String, stage: String, type: String, data: String) { events += "$type:$data" }
        override fun result(taskId: String, result: StageResult) {
            results += result
            status = if (result.stage in setOf("REQUIREMENTS", "TEST_CASES")) "WAITING_APPROVAL" else "PENDING"
            if (status == "PENDING") index++
        }
        var feedback: String? = null
        override fun approve(taskId: String) { index++; status = "PENDING" }
        override fun reject(taskId: String, feedback: String) { this.feedback = feedback; status = "PENDING" }
        var blocked = false
        var instruction: String? = null
        override fun retry(taskId: String, instruction: String?) { this.instruction = instruction; blocked = false; status = "PENDING" }
    }

    private lateinit var home: Path
    private lateinit var repo: Path
    private lateinit var store: ConfigStore
    private lateinit var active: ActiveTasks
    private val server = FakeServer()
    private val pass: (String, String) -> String = { _, _ -> """{"verdict":"PASS","findings":[]}""" }

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

    private fun jev(): com.dochiri.ilsim.runner.jev.Jev {
        val answer = jevAnswer
        val probe = answer("m0") ?: return com.dochiri.ilsim.runner.jev.Jev(null)
        val transport = object : io.github.gudcks0305.jev.spi.JevTransport {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            override fun post(uri: java.net.URI, headers: Map<String, String>, body: com.fasterxml.jackson.databind.JsonNode): java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> {
                val answers = mapper.createObjectNode()
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(id) ?: probe) }
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

    @Test
    fun `작업을 시작하면 브랜치를 만들고 단계를 통과해야 커밋·보고한다`() {
        assertContains(call("next_step"), "진행 중인 일심 작업이 없습니다")
        assertContains(edit("README.md").stdout, "start_task")

        val started = call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertContains(started, "일심 단계: REQUIREMENTS")
        assertContains(started, "specs/slug/requirements.md")
        assertEquals("ilsim/slug-task-123", Shell.gitOk(repo, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals("p", server.registered.single().projects.single().id)

        assertContains(call("submit_result"), "변경된 파일이 없습니다")
        assertContains(edit("src/Main.java").stdout, "deny")
        assertEquals("", edit("specs/slug/requirements.md").stdout)

        repo.resolve("src").createDirectories()
        repo.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("submit_result"), "허용되지 않은 변경: src/Main.java")
        assertTrue(Shell.gitOk(repo, "diff", "--cached", "--name-only").isBlank(), "실패한 제출은 스테이징을 남기지 않는다")
        assertContains(hook("stop", """{"cwd":"$repo"}""").stdout, "\"decision\":\"block\"")

        repo.resolve("src/Main.java").deleteExisting()
        repo.resolve("specs/slug").createDirectories()
        repo.resolve("specs/slug/requirements.md").writeText("# 슬러그\n\nREQ-1 소문자로 바꾼다\n")
        val submitted = call("submit_result", """{"summary":"요구사항 작성"}""")
        assertContains(submitted, "통과(REQUIREMENTS)")
        assertContains(submitted, "사용자 승인을 기다립니다")
        assertEquals("ilsim(requirements): slug", Shell.gitOk(repo, "log", "-1", "--format=%s").trim())
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
        assertEquals("REQ-2 대문자 처리 추가", server.feedback)
        repo.resolve("specs/slug/requirements.md").writeText("# 슬러그\n\nREQ-1 소문자로 바꾼다\nREQ-2 대문자도 처리한다\n")
        assertContains(call("submit_result"), "통과(REQUIREMENTS)")
        assertContains(call("approve_stage", """{"stage":"REQUIREMENTS"}"""), "일심 단계: TEST_CASES")
        assertEquals("", edit("specs/slug/test-spec.md").stdout)
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
    fun `Bash 의 Git 상태 변경과 작업 없는 파일 쓰기를 막고 종료 차단은 상한이 있다`() {
        fun bash(command: String) = hook("pre-tool-use", buildJsonObject {
            put("cwd", repo.toString())
            put("tool_name", "Bash")
            putJsonObject("tool_input") { put("command", command) }
        }.toString()).stdout
        assertContains(bash("git commit -am x"), "deny")
        assertContains(bash("echo hi > README.md"), "deny")
        assertEquals("", bash("./gradlew test 2>&1 > /dev/null"))
        assertEquals("", bash("git status && git diff"))

        active.save(repo, ActiveTask("t", "p", "slug", "b", "IMPLEMENTATION", IlsimMcp.RUNNING))
        assertEquals("", bash("echo hi > src/A.java"))
        assertContains(bash("git push"), "deny")
        repo.resolve("README.md").writeText("changed\n")
        repeat(Hooks.MAX_STOP_BLOCKS) { assertContains(hook("stop", """{"cwd":"$repo"}""").stdout, "block") }
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
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        val hook = repo.resolve(".git/hooks/pre-commit")
        assertContains(hook.toFile().readText(), Workspace.HOOK_MARK)
        assertContains(hook.toFile().readText(), "hook commit")
        assertTrue(hook.toFile().canExecute())

        repo.resolve("src").createDirectories()
        repo.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("check_change"), "허용되지 않은 변경: src/Main.java")
        repo.resolve("src/Main.java").deleteExisting()
        repo.resolve("specs/slug").createDirectories()
        repo.resolve("specs/slug/requirements.md").writeText("# 슬러그\n\nREQ-1 소문자\n")
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
    fun `구현 단계는 JEV가 누락으로 본 요구사항이 있으면 제출을 실패시키고 확실하면 독립 검토를 건너뛴다`() {
        var audits = 0
        val counting: (String, String) -> String = { _, _ -> audits++; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, counting, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        repo.resolve("specs/slug").createDirectories()
        repo.resolve("specs/slug/requirements.md").writeText("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n- REQ-2: 공백을 하이픈으로 바꾼다\n")
        run("submit_result"); server.approve("t")
        repo.resolve("specs/slug/test-spec.md").writeText("| TC-1 | REQ-1 |\n| TC-2 | REQ-2 |\n")
        run("submit_result"); server.approve("t")
        repo.resolve("src/test").createDirectories()
        repo.resolve("src/test/SlugTest.java").writeText("class SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])
        val before = audits

        repo.resolve("src/main").createDirectories()
        repo.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        jevAnswer = { id -> if (id == "m1") 0.05 else 0.95 }
        val missing = run("submit_result")
        assertContains(missing, "요구사항이 빠진 것으로 보입니다")
        assertContains(missing, "REQ-2")
        assertEquals(before, audits, "누락이면 검토 전에 실패한다")

        jevAnswer = { 0.95 }
        val passed = run("submit_result")
        assertContains(passed, "requirements=PASSED")
        assertTrue("ai-audit=SKIPPED" in passed || audits == before + 1, "확실하면 생략하거나 표본으로만 검토한다")
        assertTrue(server.results.last().decisions.any { it.purpose == "CHANGE_AUDIT" && it.handler in setOf("SKIP", "SAMPLE") })
        assertTrue(server.results.last().decisions.any { it.purpose == "REQUIREMENT_CHECK" && it.handler == "JEV" })
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
}
