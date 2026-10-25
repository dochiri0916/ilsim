package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.ReviewCache
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
) {
    fun render(): String = buildString {
        appendLine("## 프로젝트 문서 지침 (반드시 지킨다)")
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
        appendLine("이 작업에 적용되는 이 프로젝트 문서 구간입니다(문서 구간 ${sections}개 중 ${guidelines.size}개). 제출할 때 일심이 지침마다 준수 여부를 판정하고, 어기면 제출이 실패합니다.")
        var used = 0
        val omitted = mutableListOf<Guideline>()
        for (g in guidelines) {
            if (used + g.text.length > MAX_CHARS) { omitted += g; continue }
            used += g.text.length
            appendLine()
            appendLine("### ${g.source}${g.heading.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()} (관련도 ${"%.2f".format(g.relevance)})")
            appendLine(g.text)
        }
        if (omitted.isNotEmpty()) {
            appendLine()
            appendLine("분량 때문에 넣지 못한 지침입니다. 해당 위치를 직접 읽고 지키세요:")
            omitted.forEach { appendLine("- ${it.source}${it.heading.takeIf { h -> h.isNotBlank() }?.let { h -> " — $h" }.orEmpty()}") }
        }
    }.trimEnd()

    companion object {
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
