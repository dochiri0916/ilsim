package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Layout
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.profile.ProjectProfile
import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandardsTest {
    private val arch = object : DefaultArchitecture {
        override val id = "sample-layered"
        override val description = "테스트용"
        override fun model(projectName: String?, rootPrefix: String) = ArchitectureModel(
            projectName, 0, Layout(rootPrefix, "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ArchRule("leaf-unit:model", "leaf-unit", "model 은 의존하지 않는다", mapOf("unit" to listOf("model")), "BLOCK", Evidence(0, 0), approved = true)),
            emptyList(),
        )
    }
    private val conv = object : DefaultConvention {
        override val id = "sample-conv"
        override val description = "테스트용"
        override fun rules(profile: ProjectProfile) = listOf(ConventionRule("no-wildcard-import", "import", "와일드카드 import 금지"))
    }

    private fun setup(): Triple<ConfigStore, StandardsApplier, ProjectProfile> {
        val home = Files.createTempDirectory("ilsim-home")
        val store = ConfigStore(home.resolve(".ilsim/runner.json"))
        val applier = StandardsApplier(store, StandardsCatalog(listOf(arch), listOf(conv)))
        return Triple(store, applier, StackDetector.detect(Files.createTempDirectory("ilsim-p")))
    }

    @Test
    fun `내장 표준은 아직 비어 있다`() {
        assertTrue(StandardsCatalog().architectures.isEmpty() && StandardsCatalog().conventions.isEmpty())
    }

    @Test
    fun `기본 표준을 지정하면 프로젝트 전용 파일로 저장한다`() {
        val (store, applier, profile) = setup()
        applier.apply("p1", "demo", profile, ProjectStandards("sample-layered", "sample-conv", "com.demo"))
        val saved = ArchitectureStore(store).load("p1")!!
        assertEquals("DEFAULT:sample-layered", saved.source)
        assertEquals("com.demo", saved.layout.rootPrefix)
        assertEquals("sample-conv", applier.load("p1")!!.conventionId)
        assertEquals("no-wildcard-import", applier.loadConvention("p1")!!.rules.single().id)
        assertTrue(applier.loadConvention("p1")!!.rules.single().approved)
        assertEquals(null, ArchitectureStore(store).load("p2"))
    }

    @Test
    fun `알 수 없는 표준이나 루트 패키지 누락은 거부한다`() {
        val (_, applier, profile) = setup()
        assertFailsWith<IllegalArgumentException> { applier.apply("p1", null, profile, ProjectStandards("nope", null, "com.x")) }
        assertFailsWith<IllegalArgumentException> { applier.apply("p1", null, profile, ProjectStandards("sample-layered", null, null)) }
        assertFailsWith<IllegalArgumentException> { applier.apply("p1", null, profile, ProjectStandards(null, "nope", null)) }
    }
}
