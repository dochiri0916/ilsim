package com.dochiri.ilsim.runner.docs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/repository.md` 의 `## 빌드와 검증` 절에 `corpus check` 안내 한 줄을 넣는 변경(feat-5544f339)의 검증.
 * 문서 변경이라 `git diff` 기준(main 과의 merge-base)과 문서 본문, Main.kt·handoff.md 대조, 문서가 말하는 명령의 실제 실행으로 확인한다.
 * diff 기준(main)을 못 찾으면 해당 테스트는 건너뛰지 않고 실패한다.
 */
class RepositoryDocCorpusCheckTest {

    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val docPath = "docs/repository.md"
    private val doc get() = root.resolve(docPath)
    private val mainKt get() = Files.readString(root.resolve("ilsim-runner/src/main/kotlin/com/dochiri/ilsim/runner/Main.kt"))
    private val usage get() = mainKt.substringAfter("private const val USAGE = \"\"\"").substringBefore("\"\"\"")
    private val corpusFun get() = mainKt.substringAfter("private fun corpus(").substringBefore("private fun add(")

    private data class Run(val code: Int, val out: String)

    private fun run(dir: Path, env: Map<String, String> = emptyMap(), vararg cmd: String): Run {
        val pb = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); error("시간 초과: ${cmd.joinToString(" ")}") }
        return Run(p.exitValue(), out)
    }

    private fun git(vararg args: String) = run(root, emptyMap(), "git", *args)

    /** main 과의 merge-base. 기준을 못 찾으면 단언 없이 통과하지 않도록 실패시킨다. */
    private fun diffBase(): String {
        assertEquals(0, git("rev-parse", "--verify", "-q", "main").code, "diff 기준이 되는 main 브랜치가 없다")
        assertTrue(git("merge-base", "--is-ancestor", "HEAD", "main").code != 0, "HEAD 가 이미 main 에 합쳐져 있어 diff 기준을 잡을 수 없다")
        val base = git("merge-base", "HEAD", "main").out.trim()
        assertTrue(base.isNotEmpty(), "main 과의 merge-base 를 찾지 못했다")
        return base
    }

    private fun baseDoc(base: String): List<String> = git("show", "$base:$docPath").out.lines().dropLastWhile { it.isEmpty() }

    private val lines get() = Files.readAllLines(doc)
    private val sectionStart get() = lines.indexOf("## 빌드와 검증")
    private val sectionEnd get() = lines.indexOf("## 저장소 이전")

    /** `## 빌드와 검증` 절 안에서 corpus check 를 말하는 줄들. */
    private fun entries(): List<String> = lines.subList(sectionStart + 1, sectionEnd).filter { it.contains("corpus check") }

    private fun entry(): String {
        assertTrue(sectionStart in 0 until sectionEnd, "빌드와 검증 절이 저장소 이전 절보다 앞에 있어야 한다")
        val found = entries()
        assertEquals(1, found.size, "빌드와 검증 절에 corpus check 안내가 정확히 한 줄 있어야 한다: $found")
        return found.single()
    }

    private fun insideBashBlock(entry: String): Boolean {
        var open = false
        for (l in lines.subList(sectionStart + 1, sectionEnd)) {
            if (l.startsWith("```")) open = !open
            else if (l == entry) return open
        }
        return false
    }

    /** `-w` 로 공백만 바뀐 줄(정렬)은 무시한 추가·삭제 줄 수. */
    private fun numstat(base: String): Pair<Int, Int> {
        val out = git("diff", "-w", "--numstat", base, "--", docPath).out.trim()
        assertTrue(out.isNotEmpty(), "$docPath 에 변경이 없다")
        val (added, deleted) = out.lines().single().split("\t")
        return added.toInt() to deleted.toInt()
    }

    private fun addedLines(base: String) =
        git("diff", "-w", "-U0", base, "--", docPath).out.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.drop(1) }

    private fun commandArgs(entry: String): List<String> {
        val command = entry.substringBefore(" #").trim().trim('`')
        val tail = command.substringAfter("ilsim-runner corpus ").trim()
        return ("corpus " + tail).split(Regex("\\s+")).filterNot { it.startsWith("[") || it.startsWith("<") }
    }

    /** 테스트 JVM 의 클래스패스로 Main 을 직접 실행한다(installDist 없이 문서의 명령과 같은 인수로). */
    private fun runMain(dir: Path, env: Map<String, String> = emptyMap(), vararg args: String): Run {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return run(dir, env, java, "-cp", System.getProperty("java.class.path"), "com.dochiri.ilsim.runner.MainKt", *args)
    }

    @Test
    fun `TC-14 TC-16 TC-17 TC-18 보조 - 테스트 JVM 클래스패스로 Main 을 실행할 수 있다`() {
        val tmp = Files.createTempDirectory("main-probe")
        val ok = runMain(tmp, emptyMap(), "corpus", "check", "--data", tmp.resolve("none").toString(), "--base", tmp.toString())
        assertEquals(0, ok.code, ok.out)
        val unknown = runMain(tmp, emptyMap(), "corpus", "bogus")
        assertEquals(1, unknown.code)
        assertTrue(unknown.out.contains("사용법:"), unknown.out)
    }

    @Test
    fun `TC-1 추가된 줄은 실질적으로 정확히 1줄이다`() {
        val base = diffBase()
        val added = addedLines(base)
        assertEquals(1, added.size, "추가 줄: $added")
        assertTrue(added.single().contains("corpus check"))
    }

    @Test
    fun `TC-2 추가된 줄은 빌드와 검증 헤더 뒤 저장소 이전 헤더 앞에 있다`() {
        val entry = entry()
        val idx = lines.indexOf(entry)
        assertTrue(idx > sectionStart && idx < sectionEnd, "위치 $idx, 절 $sectionStart..$sectionEnd")
        val base = diffBase()
        assertEquals(entry.trim(), addedLines(base).single().trim())
    }

    @Test
    fun `TC-3 ilsim-runner corpus check 가 Main USAGE 와 같은 형태로 들어 있다`() {
        val entry = entry()
        assertTrue(entry.contains("ilsim-runner corpus check"))
        assertTrue(usage.contains("ilsim-runner corpus check [id...]"))
        if (entry.contains("[")) assertTrue(entry.contains("corpus check [id...]"), "선택 인수는 [id...] 형태여야 한다: $entry")
    }

    @Test
    fun `TC-4 시험용 모음을 기준과 비교한다는 뜻과 종료 코드 1이 드러난다`() {
        val description = entry().substringAfter("#", entry())
        assertTrue(description.contains("시험용 모음"), description)
        assertTrue(description.contains("기준") && description.contains("비교"), description)
        assertTrue(Regex("종료\\s*코드\\s*1").containsMatchIn(description), description)
    }

    @Test
    fun `TC-5 변경 규모는 한 파일 추가 1줄 삭제 0줄이다`() {
        val base = diffBase()
        assertEquals(1 to 0, numstat(base))
        val files = git("diff", "--name-only", base, "--", docPath).out.lines().filter { it.isNotBlank() }
        assertEquals(listOf(docPath), files)
    }

    @Test
    fun `TC-6 헤더 구조와 절 사이 빈 줄이 유지된다`() {
        entry()
        val headers = lines.filter { it.startsWith("## ") }
        assertEquals(listOf("## 빌드와 검증", "## 저장소 이전"), headers)
        assertEquals("# 저장소 개발과 이력", lines.first())
        assertTrue(lines[sectionEnd - 1].isBlank(), "저장소 이전 헤더 앞은 빈 줄이어야 한다")
        assertTrue(lines[sectionStart + 1].isBlank(), "빌드와 검증 헤더 뒤는 빈 줄이어야 한다")
        assertTrue(lines.subList(sectionStart, sectionEnd).count { it.startsWith("```") } % 2 == 0, "코드 블록 펜스 짝")
    }

    @Test
    fun `TC-7 블록 안에 넣었다면 기존 줄과 같은 형식이고 펜스가 유지된다`() {
        val entry = entry()
        if (!insideBashBlock(entry)) return
        assertTrue(Regex("^\\S.*\\S +# .*\\p{IsHangul}.*$").matches(entry), "명령 + 공백 + # + 한국어 설명 형식이 아니다: $entry")
        val section = lines.subList(sectionStart, sectionEnd)
        assertEquals(1, section.count { it == "```bash" })
        assertEquals(2, section.count { it.startsWith("```") })
    }

    @Test
    fun `TC-8 블록 안의 모든 줄은 같은 열에 주석 표시가 정렬된다`() {
        val entry = entry()
        if (!insideBashBlock(entry)) return
        val block = lines.subList(sectionStart, sectionEnd).dropWhile { it != "```bash" }.drop(1).takeWhile { !it.startsWith("```") }
        val columns = block.map { it.indexOf(" #") }
        assertTrue(columns.all { it > 0 }, "주석 없는 줄이 있다: $block")
        assertEquals(1, columns.distinct().size, "# 열이 다르다: $columns")
        val base = diffBase()
        assertEquals(0, numstat(base).second, "정렬 외에 기존 줄 내용이 바뀌었다")
    }

    @Test
    fun `TC-9 블록 밖에 넣었다면 한국어 평서문이고 명령은 인라인 코드다`() {
        val entry = entry()
        if (insideBashBlock(entry)) return
        assertTrue(entry.trimEnd().endsWith("다."), entry)
        assertTrue(Regex("`[^`]*corpus check[^`]*`").containsMatchIn(entry), "명령은 인라인 코드여야 한다: $entry")
    }

    @Test
    fun `TC-10 명령 옵션 환경 변수는 USAGE 원문 그대로다`() {
        val entry = entry()
        assertTrue(entry.contains("ilsim-runner") && entry.contains("corpus") && entry.contains("check"))
        assertFalse(Regex("코퍼스|체크").containsMatchIn(entry.substringBefore("#")), "명령 부분이 번역·변형되었다: $entry")
        for (name in Regex("--[a-z]+|ILSIM_[A-Z_]+").findAll(entry).map { it.value }) {
            assertTrue(usage.contains(name), "USAGE 에 없는 표기: $name")
        }
        for (name in listOf("--base", "--data", "ILSIM_CORPUS_BASE")) {
            assertTrue(usage.contains(name), "USAGE 에서 $name 이 사라졌다")
        }
    }

    @Test
    fun `TC-11 기존 줄의 내용은 바뀌지 않는다`() {
        val entry = entry()
        fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")
        val current = lines.map(::norm)
        val base = diffBase()
        for (l in baseDoc(base)) {
            assertTrue(current.any { it == norm(l) }, "기존 줄이 바뀌었거나 사라졌다: $l")
        }
        assertTrue(lines.contains(entry))
        assertEquals(0, numstat(base).second)
    }

    @Test
    fun `TC-12 설명이 Main corpus check 동작과 모순되지 않는다`() {
        val entry = entry()
        val branch = corpusFun.substringAfter("\"check\" ->").substringBefore("else ->")
        assertTrue(branch.contains("corpus.check(") && branch.contains("if (!clean) exitProcess(1)"))
        val description = entry.substringAfter("#", entry)
        assertTrue(description.contains("기준") && description.contains("비교"), description)
        assertTrue(Regex("종료\\s*코드\\s*1").containsMatchIn(description), description)
        if (description.contains("AI")) assertTrue(Regex("AI[^.,]*(않|없)").containsMatchIn(description), "AI 를 쓰지 않는다는 뜻이어야 한다: $description")
    }

    @Test
    fun `TC-13 쓴 옵션과 경로는 코드나 handoff 에 실제로 있다`() {
        val entry = entry()
        val handoff = Files.readString(root.resolve("docs/handoff.md"))
        for (option in Regex("--[a-z]+").findAll(entry).map { it.value }) assertTrue(corpusFun.contains("\"$option\""), "존재하지 않는 옵션: $option")
        for (env in Regex("ILSIM_[A-Z_]+").findAll(entry).map { it.value }) assertTrue(corpusFun.contains(env), "존재하지 않는 환경 변수: $env")
        if (entry.contains("~/programming")) assertTrue(corpusFun.contains("/programming"))
        for (path in Regex("[A-Za-z0-9_./-]*ilsim-runner/[A-Za-z0-9_./-]+").findAll(entry).map { it.value.trimEnd('.') }) {
            assertTrue(Files.exists(root.resolve(path)) || handoff.contains(path) || corpusFun.contains(path), "존재하지 않는 경로: $path")
        }
    }

    @Test
    fun `TC-14 문서에 적힌 형태의 명령은 설치 산출물 경로와 일치하고 같은 인수로 실행된다`() {
        val entry = entry()
        val command = entry.substringBefore(" #").trim().trim('`')
        if (command.contains("/bin/ilsim-runner")) {
            assertTrue(command.startsWith("ilsim-runner/build/install/ilsim-runner/bin/ilsim-runner corpus check"), command)
            assertTrue(Files.readString(root.resolve("ilsim-runner/build.gradle.kts")).contains("application"), "installDist 에는 application 플러그인이 필요하다")
        } else {
            assertTrue(command.startsWith("ilsim-runner corpus check"), command)
        }
        val tmp = Files.createTempDirectory("corpus-base")
        val result = runMain(root, emptyMap(), *commandArgs(entry).toTypedArray(), "--base", tmp.toString())
        assertFalse(result.out.contains("사용법:"), "알 수 없는 명령으로 처리되었다:\n${result.out}")
        assertEquals(1, result.code, "기준 프로젝트가 없으면 종료 코드 1이어야 한다:\n${result.out}")
        assertTrue(result.out.contains("프로젝트 없음"), result.out)
    }

    @Test
    fun `TC-15 gradlew run 형태를 썼다면 run 태스크가 있어야 한다`() {
        val entry = entry()
        if (!entry.contains(":ilsim-runner:run")) return
        val build = Files.readString(root.resolve("ilsim-runner/build.gradle.kts"))
        assertTrue(build.contains("application") && build.contains("mainClass"), "run 태스크가 없다")
    }

    @Test
    fun `TC-16 저장소 루트가 아니면 기준 폴더를 못 찾아 아무 프로젝트도 검사하지 못하고 문서는 이를 틀리게 말하지 않는다`() {
        val entry = entry()
        val elsewhere = Files.createTempDirectory("not-root")
        val result = runMain(elsewhere, emptyMap(), *commandArgs(entry).toTypedArray(), "--base", elsewhere.toString())
        assertFalse(result.out.contains("변화 없음"), "루트가 아닌 곳에서 검사가 수행되었다:\n${result.out}")
        assertFalse(Regex("어디서(나|든)|아무 (곳|디렉터리)").containsMatchIn(entry), "어디서든 실행된다고 말하면 안 된다: $entry")
        if (entry.contains("루트")) assertTrue(entry.contains("저장소 루트"), entry)
    }

    @Test
    fun `TC-17 base 옵션과 ILSIM_CORPUS_BASE 로 기준 위치를 바꿀 수 있다`() {
        val entry = entry()
        val data = root.resolve("ilsim-runner/corpus").toString()
        val viaOption = Files.createTempDirectory("base-opt")
        val a = runMain(root, emptyMap(), *commandArgs(entry).toTypedArray(), "--data", data, "--base", viaOption.toString())
        assertTrue(a.out.contains(viaOption.toString()), "--base 위치를 보지 않았다:\n${a.out}")
        val viaEnv = Files.createTempDirectory("base-env")
        val b = runMain(root, mapOf("ILSIM_CORPUS_BASE" to viaEnv.toString()), *commandArgs(entry).toTypedArray(), "--data", data)
        assertTrue(b.out.contains(viaEnv.toString()), "ILSIM_CORPUS_BASE 위치를 보지 않았다:\n${b.out}")
        if (entry.contains("--base")) assertTrue(corpusFun.contains("option(\"--base\")"))
        if (entry.contains("ILSIM_CORPUS_BASE")) assertTrue(corpusFun.contains("System.getenv(\"ILSIM_CORPUS_BASE\")"))
    }

    @Test
    fun `TC-18 인수 없이도 id 를 주어도 실행되고 id 표기는 선택 인수 의미다`() {
        val entry = entry()
        val data = root.resolve("ilsim-runner/corpus").toString()
        val base = Files.createTempDirectory("base-ids").toString()
        val id = Regex("\"id\":\\s*\"([^\"]+)\"").find(Files.readString(root.resolve("ilsim-runner/corpus/manifest.json")))?.groupValues?.get(1)
        assertNotNull(id)
        val none = runMain(root, emptyMap(), *commandArgs(entry).toTypedArray(), "--data", data, "--base", base)
        val one = runMain(root, emptyMap(), *commandArgs(entry).toTypedArray(), id, "--data", data, "--base", base)
        assertFalse(none.out.contains("사용법:"), none.out)
        assertFalse(one.out.contains("사용법:"), one.out)
        assertTrue(one.out.contains(id), one.out)
        if (entry.contains("[id...]")) assertFalse(entry.contains("<id"), "[id...] 는 선택 인수다: $entry")
    }

    @Test
    fun `TC-19 새 줄은 한 줄 안에 명령 정렬 설명이 들어 있고 줄바꿈이 없다`() {
        val entry = entry()
        assertFalse(entry.trimEnd().endsWith("\\"), "줄 이어쓰기가 있다")
        assertTrue(entry.substringBefore("#").isNotBlank(), "명령이 비어 있다: $entry")
        val section = lines.subList(sectionStart, sectionEnd)
        assertEquals(0, section.count { it.startsWith("```") } % 2, "코드 블록이 깨졌다")
        val base = diffBase()
        assertEquals(baseDoc(base).size + 1, lines.size, "문서 줄 수는 정확히 1줄 늘어야 한다")
    }

    @Test
    fun `TC-20 변경 파일은 docs repository md 와 specs 산출물 그리고 이 테스트뿐이다`() {
        val base = diffBase()
        val changed = (git("diff", "--name-only", base).out.lines() + git("ls-files", "--others", "--exclude-standard").out.lines()).filter { it.isNotBlank() }
        assertTrue(changed.contains(docPath), "$docPath 가 변경되지 않았다: $changed")
        val unexpected = changed.filterNot { it == docPath || it.startsWith("specs/feat-5544f339/") || it == "ilsim-runner/src/test/kotlin/com/dochiri/ilsim/runner/docs/RepositoryDocCorpusCheckTest.kt" }
        assertEquals(emptyList(), unexpected, "허용되지 않은 변경")
    }

    /**
     * `./gradlew check --no-daemon` 을 테스트 안에서 다시 부르면 같은 프로젝트의 빌드 잠금과 충돌하므로 재귀 호출하지 않는다.
     * 대신 check 결과가 변경 전과 같을 전제, 곧 빌드 입력(코드·빌드 스크립트·gradle 설정)이 main 과 같다는 것을 단언한다.
     * 실제 `./gradlew check --no-daemon` 통과는 이 테스트를 포함한 빌드 자체의 실행으로 확인한다.
     */
    @Test
    fun `TC-21 문서만 바뀌어 빌드 입력은 main 과 같다`() {
        val base = diffBase()
        val changed = git("diff", "--name-only", base).out.lines().filter { it.isNotBlank() }
        val buildInputs = changed.filter {
            it.endsWith(".kt") && !it.startsWith("ilsim-runner/src/test/kotlin/com/dochiri/ilsim/runner/docs/") ||
                it.endsWith(".kts") || it.endsWith(".properties") || it.startsWith("gradle/") || it.startsWith("gradlew") || it.endsWith(".toml")
        }
        assertEquals(emptyList(), buildInputs, "문서 밖의 빌드 입력이 바뀌었다")
    }

    @Test
    fun `TC-22 새 줄은 모듈 테스트를 대체하지 않는 별도 단계로 읽힌다`() {
        val entry = entry()
        assertTrue(Files.exists(root.resolve("ilsim-runner/src/test/kotlin/com/dochiri/ilsim/runner/corpus/CorpusTest.kt")))
        assertFalse(Regex("대체|대신|:test\\s*(를|을)?\\s*생략").containsMatchIn(entry), "모듈 테스트를 대체한다는 표현이 있다: $entry")
        assertFalse(entry.contains("gradlew") && entry.contains(":test"), "테스트 명령 줄과 섞였다: $entry")
        assertTrue(lines.contains("./gradlew :ilsim-server:test        # 모듈 하나만") || lines.any { it.startsWith("./gradlew :ilsim-server:test") })
    }
}
