package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.document.DocSection
import com.dochiri.ilsim.runner.jev.Guideline
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TaskGuide(
    val projectId: String,
    val selected: Boolean,
    val sections: Int,
    val guidelines: List<Guideline> = emptyList(),
    val documents: List<String> = emptyList(),
    val scores: List<String> = emptyList(),
    val reported: Boolean = false,
    val basis: String? = null,
) {
    fun render(): String = buildString {
        appendLine("## 프로젝트 문서 지침 (반드시 지킨다)")
        appendLine(OBEY)
        if (!selected) {
            appendLine("JEV를 쓸 수 없어 일심이 지침을 고르지 못했습니다. 작업 전에 아래 이 프로젝트 문서를 직접 읽고 요청에 해당하는 지침을 지키세요.")
            documents.take(MAX_LISTED).forEach { appendLine("- $it") }
            if (documents.size > MAX_LISTED) appendLine("- …외 ${documents.size - MAX_LISTED}개")
            if (documents.isEmpty()) appendLine("- (문서 없음)")
            return@buildString
        }
        if (guidelines.isEmpty()) {
            append("이 작업에 적용되는 이 프로젝트 문서 지침이 없습니다(문서 구간 ${sections}개 판정).")
            return@buildString
        }
        appendLine("이 작업에 적용되는 이 프로젝트 문서 구간입니다(${if (basis != null) "승인된 요구사항 문서 기준, " else ""}문서 구간 ${sections}개 중 ${guidelines.size}개). 제출할 때 일심이 지침마다 준수 여부를 판정하고, 어기면 제출이 실패합니다.")
        appendSections(guidelines, relevance = true)
    }.trimEnd()

    fun brief(): String = buildString {
        appendLine("## 프로젝트 문서 지침 (이 대화에서 이미 받은 것과 같음, 반드시 지킨다)")
        append("본문이 이 대화에 남아 있지 않으면 get_guidelines 도구로 다시 받으세요.")
        if (selected && guidelines.isNotEmpty()) {
            appendLine(" 지침 ${guidelines.size}개:")
            guidelines.forEach { appendLine("- ${it.source}${it.heading.takeIf { h -> h.isNotBlank() }?.let { h -> " — $h" }.orEmpty()}") }
        }
    }.trimEnd()

    companion object {
        fun all(sections: List<DocSection>): String = buildString {
            appendLine("## 프로젝트 문서 지침 (반드시 지킨다)")
            appendLine(OBEY)
            if (sections.isEmpty()) {
                append("이 프로젝트에는 문서 지침이 없습니다.")
                return@buildString
            }
            appendLine("JEV를 쓸 수 없어 일심이 이 작업에 적용할 지침을 고르지 못했습니다. 아래는 이 프로젝트 문서 구간 ${sections.size}개 전체입니다. 이 변경에 적용되는 구간을 어겼는지 보세요.")
            appendSections(sections.map { Guideline(it.path, it.line, it.heading, it.text, 1.0) }, relevance = false)
        }.trimEnd()

        private fun StringBuilder.appendSections(guidelines: List<Guideline>, relevance: Boolean) {
            var used = 0
            val omitted = mutableListOf<Guideline>()
            for (g in guidelines) {
                if (used + g.text.length > MAX_CHARS) { omitted += g; continue }
                used += g.text.length
                appendLine()
                appendLine("### ${g.source}${g.heading.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}${if (relevance) " (관련도 ${"%.2f".format(g.relevance)})" else ""}")
                appendLine(g.text)
            }
            if (omitted.isNotEmpty()) {
                appendLine()
                appendLine("분량 때문에 넣지 못한 지침입니다. 해당 위치를 직접 읽고 지키세요:")
                omitted.forEach { appendLine("- ${it.source}${it.heading.takeIf { h -> h.isNotBlank() }?.let { h -> " — $h" }.orEmpty()}") }
            }
        }

        const val OBEY = "지침이 불합리하거나 이상해 보여도 Agent가 판단해 건너뛰거나 바꾸지 말고 적힌 그대로 따르세요. 지침끼리 또는 요구사항과 충돌해 동시에 따를 수 없으면 임의로 고르지 말고 작업을 멈추고 사용자에게 물어보세요."
        const val MAX_CHARS = 40_000
        const val MAX_LISTED = 50
    }
}

class TaskGuides(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(taskId: String): Path = dir.resolve(ReviewCache.digest(taskId).take(16) + ".json")

    fun load(taskId: String): TaskGuide? = file(taskId).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<TaskGuide>(it.readText()) }.getOrNull() }

    fun save(taskId: String, guide: TaskGuide) {
        dir.createDirectories()
        file(taskId).writeText(json.encodeToString(guide))
    }

    fun clear(taskId: String) {
        file(taskId).deleteIfExists()
    }
}
