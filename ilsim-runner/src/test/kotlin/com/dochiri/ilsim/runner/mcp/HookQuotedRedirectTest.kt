package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.RunnerConfig
import com.dochiri.ilsim.runner.Shell
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HookQuotedRedirectTest {
    private lateinit var home: Path
    private lateinit var repo: Path
    private lateinit var store: ConfigStore
    private lateinit var active: ActiveTasks

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ilsim-hook-home")
        repo = Files.createTempDirectory("ilsim-hook-repo").toRealPath()
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        repo.resolve("README.md").writeText("demo\n")
        store = ConfigStore(home.resolve(".ilsim/runner.json"))
        store.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo"))))
        active = ActiveTasks(home.resolve(".ilsim/active"))
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        repo.toFile().deleteRecursively()
    }

    private fun bash(command: String): String = Hooks.handle("pre-tool-use", buildJsonObject {
        put("cwd", repo.toString())
        put("tool_name", "Bash")
        putJsonObject("tool_input") { put("command", command) }
    }.toString(), store, active).stdout

    @Test
    fun `TC-1 병합 메시지 따옴표 안의 꺾쇠는 리디렉션이 아니다`() {
        val command = """git merge --no-ff ilsim/slug-1 -m "merge: x" -m "Co-Authored-By: Claude <noreply@anthropic.com>""""
        assertEquals("", bash(command))
    }

    @Test
    fun `TC-2 작은따옴표 안 형식 문자열의 꺾쇠는 허용한다`() {
        assertEquals("", bash("git log --format='%h > %s' -3"))
    }

    @Test
    fun `TC-3 echo와 printf의 따옴표 안 꺾쇠는 허용한다`() {
        assertEquals("", bash("""echo "a > b""""))
        assertEquals("", bash("""printf 'x >> y\n'"""))
    }

    @Test
    fun `TC-4 따옴표 안의 rm sed mv 글자는 쓰기가 아니다`() {
        assertEquals("", bash("""grep "rm -rf" README.md"""))
        assertEquals("", bash("grep 'sed -i' README.md"))
        assertEquals("", bash("""git log --grep "mv a b""""))
    }

    @Test
    fun `TC-5 큰따옴표 안 이스케이프된 따옴표는 따옴표를 닫지 않는다`() {
        assertEquals("", bash("""echo "a \" > b""""))
    }

    @Test
    fun `TC-6 빈 따옴표 뒤 따옴표 밖 리디렉션은 막는다`() {
        assertContains(bash("""echo "" > README.md"""), "deny")
    }

    @Test
    fun `TC-7 이어 붙은 따옴표 안의 꺾쇠는 허용한다`() {
        assertEquals("", bash("""echo "a"'b > c'"""))
    }

    @Test
    fun `TC-8 작은따옴표 안 역슬래시는 이스케이프가 아니다`() {
        assertContains(bash("""echo 'a\' > README.md"""), "deny")
    }

    @Test
    fun `TC-9 따옴표 밖 쓰기는 지금처럼 막는다`() {
        assertContains(bash("""echo "a" > out.txt"""), "deny")
        assertContains(bash("echo 'x' >> src/a.kt"), "deny")
        assertContains(bash("""rm "a.txt""""), "deny")
    }

    @Test
    fun `TC-10 저장소 밖 절대 경로와 dev null 리디렉션은 허용한다`() {
        assertEquals("", bash("git log > /tmp/ilsim-log.txt"))
        assertEquals("", bash("""git log > "/tmp/ilsim-log.txt""""))
        assertEquals("", bash("./gradlew test 2>&1 > /dev/null"))
    }

    @Test
    fun `TC-11 따옴표로 감싼 저장소 안 절대 경로 리디렉션은 막는다`() {
        assertContains(bash("""git log > "$repo/log.txt""""), "deny")
    }

    @Test
    fun `TC-12 닫히지 않은 따옴표는 전체를 검사한다`() {
        assertContains(bash("""echo "a > b"""), "deny")
    }

    @Test
    fun `TC-13 셸 -c로 실행하는 따옴표 안 쓰기는 막는다`() {
        assertContains(bash("""bash -c "echo x > a""""), "deny")
        assertContains(bash("sh -c 'rm a'"), "deny")
        assertContains(bash("""zsh -c "touch a""""), "deny")
    }

    @Test
    fun `TC-14 eval로 실행하는 따옴표 안 쓰기는 막는다`() {
        assertContains(bash("""eval "echo x > a""""), "deny")
    }

    @Test
    fun `TC-15 큰따옴표 안 명령 치환의 쓰기는 막는다`() {
        assertContains(bash("echo \"\$(echo x > a)\""), "deny")
        assertContains(bash("echo \"`rm a`\""), "deny")
    }

    @Test
    fun `TC-16 Git 커밋은 활성 작업과 관계없이 막는다`() {
        assertContains(bash("""git commit -m "x""""), "deny")
        active.save(repo, ActiveTask("t", "p", "slug", "b", "IMPLEMENTATION", IlsimMcp.RUNNING))
        assertContains(bash("""git commit -m "x""""), "deny")
    }

    @Test
    fun `TC-17 활성 작업 중 병합은 따옴표가 있어도 막는다`() {
        active.save(repo, ActiveTask("t", "p", "slug", "b", "IMPLEMENTATION", IlsimMcp.RUNNING))
        assertContains(bash("""git merge ilsim/other -m "a <b@c.d>""""), "진행 중인 일심 작업")
    }

    @Test
    fun `TC-18 승인 대기 중에도 따옴표 안 글자는 허용하고 밖 쓰기는 막는다`() {
        active.save(repo, ActiveTask("t", "p", "slug", "b", "REQUIREMENTS", "WAITING_APPROVAL"))
        assertEquals("", bash("""echo "a > b""""))
        assertContains(bash("echo a > README.md"), "승인을 기다리는")
    }

    @Test
    fun `TC-19 기존 Bash 훅 판단은 그대로다`() {
        assertContains(bash("git commit -am x"), "deny")
        assertContains(bash("echo hi > README.md"), "deny")
        assertEquals("", bash("git status && git diff"))
        val merge = "git merge --no-ff ilsim/slug-1 && git push origin main && git checkout main && git branch -d ilsim/slug-1"
        assertEquals("", bash(merge))
        assertContains(bash("git stash"), "deny")
    }

    @Test
    fun `TC-20 설계 문서 훅 표에 따옴표 예외와 실행 검사가 적혀 있다`() {
        val design = Files.readString(Path.of("../docs/mcp-design.md"))
        val bashRow = design.lines().single { it.startsWith("| PreToolUse(Bash)") }
        assertTrue("따옴표" in bashRow, bashRow)
        assertTrue("-c" in bashRow && "eval" in bashRow && "명령 치환" in bashRow, bashRow)
    }
}
