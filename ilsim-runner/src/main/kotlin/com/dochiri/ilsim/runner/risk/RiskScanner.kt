package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 프로젝트별 위험 규칙 설정. overrides 의 값은 BLOCK | REVIEW | OFF 이고 없으면 규칙의 기본 심각도를 쓴다. */
@Serializable
data class RiskConfig(
    val overrides: Map<String, String> = emptyMap(),
    /** 사용자가 AI 검토 도입을 승인한 규칙. 승인 전에는 어떤 규칙도 AI를 호출하지 않는다. */
    val aiReview: Set<String> = emptySet(),
)

object RiskScanner {

    val ruleIds: List<String> get() = RiskRules.all.map { it.id }

    fun effective(config: RiskConfig, id: String): String = config.overrides[id] ?: RiskRules.byId.getValue(id).defaultSeverity

    /** [onlyFiles] 가 있으면 그 파일의 결과만 돌려준다 (게이트에서 바꾼 파일에만 강제하기 위함). 소스가 하나도 없으면 null. */
    fun scan(root: Path, profile: ProjectProfile, config: RiskConfig = RiskConfig(), onlyFiles: Set<String>? = null): List<Violation>? {
        val facts = FactScanner.scan(root, profile.modules)
        if (facts.main.isEmpty()) return null
        return Detectors.detect(facts.files, hasAllOpen(root), facts.dependencies)
            .filter { onlyFiles == null || it.file in onlyFiles }
            .mapNotNull { h ->
                val severity = effective(config, h.ruleId)
                if (severity == "OFF") null else Violation(h.ruleId, severity, h.file, h.line, h.message)
            }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))
    }

    /** 빌드 파일에 kotlin-spring(allopen) 플러그인이 있는지. 있으면 Kotlin 클래스도 프록시할 수 있다. */
    private fun hasAllOpen(root: Path): Boolean = try {
        Files.walk(root, 4).use { s ->
            s.anyMatch { p ->
                val n = p.fileName?.toString().orEmpty()
                (n.startsWith("build.gradle") || n == "pom.xml") && !p.toString().contains("/build/") &&
                    Regex("""plugin\.spring|kotlin-spring|allopen|all-open""").containsMatchIn(p.readText())
            }
        }
    } catch (e: Exception) { true }

    fun remedy(ruleId: String): String? = RiskRules.remedies[ruleId]

    /** 결과에 나온 규칙마다 한 번씩 권고를 보여 준다. */
    fun remedies(findings: List<Violation>): String = findings.map { it.ruleId }.distinct().sorted()
        .joinToString("\n") { "  $it → ${remedy(it)}" }

    fun describe(config: RiskConfig): String = RiskRules.all.joinToString("\n") {
        "  ${it.id} ${effective(config, it.id).padEnd(6)} ${it.title} (${it.spec})"
    }

    /** 위험 규칙 설정도 프로젝트 id 별로 저장한다: `~/.ilsim/projects/<id>/risk.json` */
    class Store(config: ConfigStore) {
        private val base: Path = config.location.parent.resolve("projects")
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        private fun file(projectId: String): Path {
            require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
            return base.resolve(projectId).resolve("risk.json")
        }

        fun load(projectId: String): RiskConfig =
            file(projectId).takeIf { it.exists() }?.let { json.decodeFromString<RiskConfig>(it.readText()) } ?: RiskConfig()

        fun setAi(projectId: String, ruleIds: Collection<String>, enabled: Boolean): RiskConfig {
            val unknown = ruleIds.filterNot { it in RiskRules.byId }
            require(unknown.isEmpty()) { "알 수 없는 규칙: ${unknown.joinToString()}" }
            val current = load(projectId)
            val updated = current.copy(aiReview = if (enabled) current.aiReview + ruleIds else current.aiReview - ruleIds.toSet())
            file(projectId).also { it.parent.createDirectories() }.writeText(json.encodeToString(updated))
            return updated
        }

        fun set(projectId: String, ruleId: String, severity: String): RiskConfig {
            require(ruleId in RiskRules.byId) { "알 수 없는 규칙: $ruleId (${RiskRules.all.joinToString { it.id }})" }
            require(severity in setOf("BLOCK", "REVIEW", "OFF")) { "심각도는 BLOCK | REVIEW | OFF 중 하나여야 합니다" }
            val updated = load(projectId).let { it.copy(overrides = it.overrides + (ruleId to severity)) }
            file(projectId).also { it.parent.createDirectories() }.writeText(json.encodeToString(updated))
            return updated
        }
    }
}
