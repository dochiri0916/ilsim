package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 프로젝트에 기본 표준을 적용해 `~/.ilsim/projects/<id>/` 에 저장한다: standards.json, architecture.json, convention.json */
class StandardsApplier(config: ConfigStore, private val catalog: StandardsCatalog = StandardsCatalog()) {
    private val base: Path = config.location.parent.resolve("projects")
    private val architectures = ArchitectureStore(config)
    private val conventions = ConventionStore(config)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun dir(projectId: String): Path {
        require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
        return base.resolve(projectId)
    }

    fun load(projectId: String): ProjectStandards? =
        dir(projectId).resolve("standards.json").takeIf { it.exists() }?.let { json.decodeFromString<ProjectStandards>(it.readText()) }

    fun loadConvention(projectId: String): ConventionModel? = conventions.load(projectId)

    fun apply(projectId: String, projectName: String?, profile: ProjectProfile, selection: ProjectStandards): ProjectStandards {
        val arch = selection.architectureId?.let {
            catalog.architectures[it] ?: throw IllegalArgumentException("알 수 없는 기본 아키텍처: $it")
        }
        val conv = selection.conventionId?.let {
            catalog.conventions[it] ?: throw IllegalArgumentException("알 수 없는 기본 컨벤션: $it")
        }
        if (arch != null) require(!selection.rootPrefix.isNullOrBlank()) { "기본 아키텍처에는 루트 패키지(--root)가 필요합니다" }
        val d = dir(projectId).also { it.createDirectories() }
        if (arch != null) {
            architectures.save(projectId, arch.model(projectName, selection.rootPrefix!!).copy(source = "DEFAULT:${arch.id}"))
        }
        if (conv != null) {
            val rules = conv.rules(profile).map { it.copy(approved = true) }
            conventions.save(projectId, ConventionModel(projectName, 0, 0, emptyMap(), rules, source = "DEFAULT:${conv.id}"))
        }
        d.resolve("standards.json").writeText(json.encodeToString(selection))
        return selection
    }
}
