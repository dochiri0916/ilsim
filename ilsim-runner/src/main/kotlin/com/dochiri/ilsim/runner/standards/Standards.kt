package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.profile.ProjectProfile
import kotlinx.serialization.Serializable

/**
 * 신규 프로젝트에 지정하는 기본 아키텍처. 기존 프로젝트의 분석(채굴)과 달리 규칙을 미리 정의해 두고 처음부터 강제한다.
 * 규칙 종류는 `ArchRule.kind` 를 그대로 쓰므로 검증기는 채굴한 모델과 같은 것을 사용한다.
 */
interface DefaultArchitecture {
    val id: String
    val description: String

    /** [rootPrefix] 아래에 이 표준의 레이아웃과 승인된 규칙을 채운 모델을 만든다. */
    fun model(projectName: String?, rootPrefix: String): ArchitectureModel
}

/** 신규 프로젝트에 지정하는 기본 코드 컨벤션. 스택(언어·프레임워크)에 따라 다른 규칙을 돌려줄 수 있다. */
interface DefaultConvention {
    val id: String
    val description: String
    fun rules(profile: ProjectProfile): List<ConventionRule>
}

/** 프로젝트에 지정된 표준. architecture.json 과 convention.json 은 이 선택으로 생성된다. */
@Serializable
data class ProjectStandards(
    val architectureId: String? = null,
    val conventionId: String? = null,
    val rootPrefix: String? = null,
)

/** 사용 가능한 기본 표준 목록. 새 표준은 [BuiltInStandards] 에 추가한다. */
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

/** 기본 아키텍처와 컨벤션을 여기에 등록한다. 아직 정의된 표준은 없다. */
object BuiltInStandards {
    val architectures: List<DefaultArchitecture> = emptyList()
    val conventions: List<DefaultConvention> = emptyList()
}
