package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.profile.ProjectProfile
import kotlinx.serialization.Serializable

interface DefaultArchitecture {
    val id: String
    val description: String

    fun model(projectName: String?, rootPrefix: String): ArchitectureModel
}

interface DefaultConvention {
    val id: String
    val description: String
    fun rules(profile: ProjectProfile): List<ConventionRule>
}

@Serializable
data class ProjectStandards(
    val architectureId: String? = null,
    val conventionId: String? = null,
    val rootPrefix: String? = null,
)

class StandardsCatalog(
    architectures: List<DefaultArchitecture> = BuiltInStandards.architectures,
    conventions: List<DefaultConvention> = BuiltInStandards.conventions,
) {
    val architectures: Map<String, DefaultArchitecture> = architectures.associateBy { it.id }
    val conventions: Map<String, DefaultConvention> = conventions.associateBy { it.id }

    init {
        require(architectures.size == this.architectures.size) { "중복된 기본 아키텍처 id" }
        require(conventions.size == this.conventions.size) { "중복된 기본 컨벤션 id" }
    }
}

object BuiltInStandards {
    val architectures: List<DefaultArchitecture> = emptyList()
    val conventions: List<DefaultConvention> = emptyList()
}
