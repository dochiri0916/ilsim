package com.dochiri.ilsim.runner.document

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 프로젝트에 이미 있는 문서를 어떤 기준으로 볼지 정의한다. 명령(목적)마다 기준이 다르다.
 * 기준은 사람이 읽는 지침([criteria])과 후보를 거르는 키워드([keywords])로 나뉜다.
 * 문서 선택은 [DocumentSelector] 가 맡고, 기본 구현은 이름·경로·제목 키워드만 쓴다. JEV 로 교체할 수 있다.
 */
enum class DocPurpose(val label: String, val keywords: List<String>, val criteria: String) {
    ARCHITECTURE(
        "아키텍처",
        listOf("architecture", "adr", "design", "hexagonal", "layer", "structure", "package", "module", "decision", "아키텍처", "구조", "계층", "레이어", "패키지", "모듈", "의존"),
        "패키지·모듈 구조, 계층과 슬라이스 사이의 의존 방향, 금지된 의존, 외부 라이브러리를 쓸 수 있는 경계에 관한 서술만 본다. " +
            "배경 설명, 일정, 구현 예시는 무시한다. 문서가 명시한 규칙이 코드에서 채굴한 규칙과 충돌하면 문서를 근거로 하되 어느 쪽이 맞는지는 사용자가 정하도록 넘긴다.",
    ),
    CONVENTION(
        "코드 컨벤션",
        listOf("convention", "style", "guideline", "contributing", "lint", "format", "naming", "coding", "test", "컨벤션", "규칙", "네이밍", "이름", "포맷", "금지", "테스트"),
        "이름 규칙, 포맷, 금지 패턴, 어노테이션과 의존성 주입 방식, 테스트 작성 규칙에 관한 서술만 본다. " +
            "도구 설치나 일정 같은 절차는 무시한다. 문서에 있는데 채굴된 규칙에 없는 항목은 누락 후보로, 충돌하는 항목은 사용자 선택으로 넘긴다.",
    ),
}

data class DocumentRef(val path: String, val score: Int, val reason: String)

/** 후보 문서 중 이 목적에 쓸 문서를 고른다. JEV 로 교체할 지점이다. */
interface DocumentSelector {
    fun select(purpose: DocPurpose, candidates: List<DocumentFile>): List<DocumentRef>
}

data class DocumentFile(val path: String, val text: String)

/** 파일명·경로·제목에 목적의 키워드가 얼마나 있는지로 고른다. AI를 쓰지 않는다. */
class HeuristicDocumentSelector(private val limit: Int = 3, private val minScore: Int = 3) : DocumentSelector {
    override fun select(purpose: DocPurpose, candidates: List<DocumentFile>): List<DocumentRef> =
        candidates.mapNotNull { d ->
            val lower = d.path.lowercase()
            val name = lower.substringAfterLast('/')
            val nameHits = purpose.keywords.filter { it in name }
            val dirHits = purpose.keywords.filter { it in lower.substringBeforeLast('/', "") }
            val headingHits = d.text.lineSequence().filter { it.startsWith("#") }.take(200).count { h -> purpose.keywords.any { it in h.lowercase() } }
            val guidance = name in setOf("agents.md", "claude.md") // 프로젝트 지침 문서는 두 목적 모두에서 후보
            val score = nameHits.size * 3 + dirHits.size + minOf(headingHits, 3) + if (guidance) 2 else 0
            if (score < minScore) null
            else DocumentRef(d.path, score, listOfNotNull(nameHits.takeIf { it.isNotEmpty() }?.let { "파일명 ${it.joinToString()}" }, dirHits.takeIf { it.isNotEmpty() }?.let { "경로 ${it.joinToString()}" }, headingHits.takeIf { it > 0 }?.let { "제목 ${it}곳" }).joinToString(" · "))
        }.sortedByDescending { it.score }.take(limit)
}

/** 선택한 문서에서 이 목적과 관련된 절만 잘라 낸 결과. [text] 가 AI 검토 입력에 들어간다. */
class DocumentContext(val purpose: DocPurpose, val refs: List<DocumentRef>, val text: String) {
    val hash: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    val isEmpty get() = text.isBlank()

    companion object {
        /** JEV 키가 있으면 JEV 로, 없으면 이름·경로·제목 키워드로 고른다. */
        fun defaultSelector(): DocumentSelector = com.dochiri.ilsim.runner.jev.Jev.fromEnvironment().let { if (it.enabled) com.dochiri.ilsim.runner.jev.JevDocumentSelector(it) else HeuristicDocumentSelector() }

        private const val PER_DOC = 2500
        private val ignoredDirs = setOf(".git", "build", "node_modules", ".gradle", "out", "target", ".idea", "dist", "worktrees")
        private val docExt = setOf("md", "adoc", "txt", "markdown")

        fun find(root: Path): List<DocumentFile> {
            val out = mutableListOf<DocumentFile>()
            Files.walk(root, 6).use { s ->
                s.filter { p -> Files.isRegularFile(p) && p.extension.lowercase() in docExt && root.relativize(p).none { it.name in ignoredDirs } }
                    .limit(2000)
                    .forEach { p ->
                        if (p.fileSize() <= 200_000) out += DocumentFile(root.relativize(p).toString(), runCatching { p.readText() }.getOrDefault(""))
                    }
            }
            return out
        }

        fun build(root: Path, purpose: DocPurpose, selector: DocumentSelector = defaultSelector()): DocumentContext {
            val candidates = find(root)
            val refs = selector.select(purpose, candidates)
            val byPath = candidates.associateBy { it.path }
            val parts = refs.mapNotNull { r ->
                val excerpt = excerpt(byPath.getValue(r.path).text, purpose)
                if (excerpt.isBlank()) null else "### 문서 ${r.path}\n$excerpt"
            }
            val used = refs.filter { r -> parts.any { it.startsWith("### 문서 ${r.path}\n") } }
            return DocumentContext(purpose, used, parts.joinToString("\n\n"))
        }

        /** 제목이나 첫 부분에 목적 키워드가 있는 절만 모아 [PER_DOC] 자까지 자른다. */
        internal fun excerpt(text: String, purpose: DocPurpose): String {
            val sections = mutableListOf<String>()
            var current = StringBuilder()
            for (line in text.lines()) {
                if (line.startsWith("#") && current.isNotEmpty()) { sections += current.toString(); current = StringBuilder() }
                current.appendLine(line)
            }
            if (current.isNotEmpty()) sections += current.toString()
            val relevant = sections.filter { sec -> purpose.keywords.any { it in sec.take(400).lowercase() } }
            return relevant.joinToString("\n").trim().take(PER_DOC)
        }
    }
}
