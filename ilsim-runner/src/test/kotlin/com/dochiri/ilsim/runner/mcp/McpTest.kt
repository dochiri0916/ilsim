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

        val started = call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        assertContains(started, "일심 단계: REQUIREMENTS")
        assertContains(started, "submit_result 의 document")
        assertEquals("ilsim/slug-task-123", Shell.gitOk(repo, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals("p", server.registered.single().projects.single().id)

        assertContains(call("submit_result"), "document 에 넣어 제출합니다")
        assertContains(edit("src/Main.java").stdout, "deny")
        assertContains(edit("specs/slug/requirements.md").stdout, "deny")

        repo.resolve("src").createDirectories()
        repo.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("submit_result", doc("# 슬러그\n\nREQ-1 소문자로 바꾼다")), "허용되지 않은 변경: src/Main.java")
        assertTrue(Shell.gitOk(repo, "diff", "--cached", "--name-only").isBlank(), "실패한 제출은 스테이징을 남기지 않는다")
        assertContains(hook("stop", """{"cwd":"$repo"}""").stdout, "\"decision\":\"block\"")

        repo.resolve("src/Main.java").deleteExisting()
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
        assertContains(call("submit_result", doc("# 슬러그\n\nREQ-1 소문자로 바꾼다\nREQ-2 대문자도 처리한다")), "통과(REQUIREMENTS)")
        val cases = call("approve_stage", """{"stage":"REQUIREMENTS"}""")
        assertContains(cases, "일심 단계: TEST_CASES")
        assertContains(cases, "REQ-2 대문자도 처리한다")
        assertContains(edit("specs/slug/test-spec.md").stdout, "deny")
        assertTrue(!repo.resolve("specs").toFile().exists(), "프로젝트 안에 일심 산출물을 만들지 않는다")
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
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        val hook = repo.resolve(".git/hooks/pre-commit")
        assertContains(hook.toFile().readText(), Workspace.HOOK_MARK)
        assertContains(hook.toFile().readText(), "hook commit")
        assertTrue(hook.toFile().canExecute())

        repo.resolve("src").createDirectories()
        repo.resolve("src/Main.java").writeText("class Main {}\n")
        assertContains(call("check_change"), "허용되지 않은 변경: src/Main.java")
        repo.resolve("src/Main.java").deleteExisting()
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
        call("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        server.index = server.stages.indexOf("IMPLEMENTATION"); server.status = "PENDING"
        assertContains(call("next_step"), "일심 단계: IMPLEMENTATION")
        repo.resolve("src").createDirectories()
        repo.resolve("src/Main.java").writeText("class Main {}\n")

        assertContains(hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__plugin_ilsim_ilsim__reopen_stage","tool_input":{"stage":"TEST_CODE","feedback":"TC-4"}}""").stdout, "\"permissionDecision\":\"ask\"")
        assertContains(runCatching { call("reopen_stage", """{"stage":"IMPLEMENTATION","feedback":"x"}""") }.exceptionOrNull()!!.message!!, "통과한 단계가 아닙니다")
        assertTrue(repo.resolve("src/Main.java").exists(), "되돌리기에 실패하면 변경을 그대로 둔다")

        val reopened = call("reopen_stage", """{"stage":"TEST_CODE","feedback":"TC-4 기대값을 고친다"}""")
        assertContains(reopened, "일심 단계: TEST_CODE")
        assertContains(reopened, "보관했습니다")
        assertEquals("TEST_CODE" to "TC-4 기대값을 고친다", server.reopened)
        assertTrue(!repo.resolve("src/Main.java").exists())
        assertEquals("IMPLEMENTATION", active.load(repo)!!.shelvedStage)

        server.index = server.stages.indexOf("IMPLEMENTATION"); server.status = "PENDING"
        assertContains(call("next_step"), "되살렸습니다")
        assertEquals("class Main {}\n", repo.resolve("src/Main.java").readText())
        assertEquals(null, active.load(repo)!!.shelved)
    }

    @Test
    fun `구현 단계는 JEV가 누락으로 본 요구사항이 있으면 제출을 실패시키고 확실하면 독립 검토를 건너뛴다`() {
        var audits = 0
        val counting: (String, String) -> String = { _, _ -> audits++; """{"verdict":"PASS","findings":[]}""" }
        fun mcp() = IlsimMcp(store, server, active, repo, counting, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n- REQ-2: 공백을 하이픈으로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |\n| TC-2 | REQ-2 |")); server.approve("t")
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
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다\n\n## 영향 범위\n- `src/main/Slug.java`\n- `src/test/SlugTest.java`")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |")); server.approve("t")
        repo.resolve("src/test").createDirectories()
        repo.resolve("src/test/SlugTest.java").writeText("class SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])
        assertContains(run("next_step"), "영향 범위와 관계없는 파일은 고치지 않는다")

        repo.resolve("src/main").createDirectories()
        repo.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { return s.toLowerCase(); } }\n")
        repo.resolve("src/main/Other.java").writeText("class Other {}\n")
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
        assertTrue("Q2. null" !in guessed, guessed)
        assertTrue(server.results.size == 1, "추측이면 서버에 보고하지 않는다")

        val confirm = hook("pre-tool-use", """{"cwd":"$repo","tool_name":"mcp__ilsim__record_decision","tool_input":{"question":"Q1","decision":"빈 문자열을 돌려준다"}}""")
        assertContains(confirm.stdout, "\"ask\"")
        assertContains(confirm.stdout, "사용자 결정 기록 (Q1): 빈 문자열을 돌려준다")
        assertContains(failure("record_decision", """{"question":"Q9","decision":"x"}"""), "Q9 가 없습니다")
        assertContains(run("record_decision", """{"question":"q1","decision":"빈 문자열을 돌려준다"}"""), "남은 미결정 질문: Q2")
        assertTrue(server.events.any { it == "log:사용자 결정 기록: Q1: 빈 문자열을 돌려준다" })

        jevAnswer = { id -> when { id == "u0" -> 0.5; id[0] == 'v' -> 0.05; else -> 0.95 } }
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
        repo.resolve("src/test").createDirectories()
        repo.resolve("src/test/SlugTest.java").writeText("class SlugTest {}\n")
        assertContains(run("submit_result"), "undecided=UNCERTAIN")
        assertContains(prompts.last(), "- GUESS:")
        assertContains(prompts.last(), "## 정하지 않은 사항\n- Q2. null 이면 예외를 던지는가?")
        assertContains(prompts.last(), "## 사용자가 정한 사항")

        repo.resolve("src/main").createDirectories()
        repo.resolve("src/main/Slug.java").writeText("class Slug { String of(String s) { if (s == null) throw new IllegalArgumentException(); return s.toLowerCase(); } }\n")
        jevAnswer = { id -> when { id == "u0" -> 0.85; id.startsWith("v") -> 0.05; else -> 0.95 } }
        val implemented = run("submit_result")
        assertContains(implemented, "- Q2. null 이면 예외를 던지는가? (정함 확률 0.85)")
        assertTrue(server.results.last().stage == "TEST_CODE")

        val recorded = run("record_decision", """{"question":"Q2","decision":"null 이면 IllegalArgumentException"}""")
        assertContains(recorded, "남은 미결정 질문: 없음")
        assertContains(recorded, "reopen_stage(TEST_CODE)로 테스트를 더할지")
        val passed = run("submit_result")
        assertContains(passed, "통과(IMPLEMENTATION)")
        assertTrue("undecided=" !in passed, passed)
        assertTrue(server.results.last().decisions.none { it.purpose == "UNDECIDED_CHECK" })
        assertTrue(server.results.dropLast(1).last().decisions.any { it.purpose == "UNDECIDED_CHECK" && it.handler == "JEV" })
    }

    @Test
    fun `TC-18 구현 단계 diff가 입력 한도로 잘리면 누락 대신 불확실로 기록한다`() {
        fun mcp() = IlsimMcp(store, server, active, repo, pass, ::jev).tools().associateBy { it.name }
        fun run(name: String, args: String = "{}") = mcp().getValue(name).call(Json.parseToJsonElement(args).jsonObject)
        run("start_task", """{"requirement":"slugify 추가","feature":"slug"}""")
        run("submit_result", doc("# 슬러그\n\n- REQ-1: 소문자로 바꾼다")); server.approve("t")
        run("submit_result", doc("| TC-1 | REQ-1 |")); server.approve("t")
        repo.resolve("src/test").createDirectories()
        repo.resolve("src/test/SlugTest.java").writeText("class SlugTest {}\n")
        run("submit_result")
        assertEquals("IMPLEMENTATION", server.stages[server.index])

        repo.resolve("src/main").createDirectories()
        repo.resolve("src/main/Slug.java").writeText("class Slug {\n" + "    int value = 1;\n".repeat(2000) + "}\n")
        jevAnswer = { id -> when { id == "m0" -> 0.1; id.startsWith("f") -> 0.95; else -> 0.05 } }
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
            id[0] in "km" && "README.md" in body -> 0.05
            id == "s1" && "brew install" in body -> 0.05
            else -> null
        } }

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
