package com.dochiri.ilsim.runner.profile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StackDetectorTest {

    private fun write(root: Path, rel: String, text: String) {
        val file = root.resolve(rel)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun sampleProject(): Path {
        val root = Files.createTempDirectory("ilsim-detect")
        write(root, "settings.gradle.kts", """
            rootProject.name = "sample"
            include("app", "infra")
            include("core-contract")
        """.trimIndent())
        write(root, "build.gradle.kts", """
            plugins {
                kotlin("jvm") version "2.3.21" apply false
                id("org.springframework.boot") version "4.0.0" apply false
            }
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "org.springframework.boot")
                extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
                dependencies {
                    if (!project.name.endsWith("-contract")) {
                        add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
                    }
                    add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5")
                    if (name == "app" || name.endsWith("-infra")) {
                        add("testImplementation", "org.testcontainers:testcontainers-mysql")
                    }
                }
            }
        """.trimIndent())
        write(root, "app/build.gradle.kts", """
            dependencies {
                implementation(project(":infra"))
                implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
                listOf("a").forEach { implementation(project(":${'$'}it")) }
            }
        """.trimIndent())
        write(root, "app/src/main/kotlin/demo/DemoApplication.kt", "@SpringBootApplication\nclass DemoApplication")
        write(root, "app/src/main/kotlin/demo/application/port/out/SavePort.kt", "interface SavePort")
        write(root, "app/src/main/kotlin/demo/application/port/in/UsePort.kt", "interface UsePort")
        write(root, "app/src/test/kotlin/demo/ArchTest.kt", "import com.tngtech.archunit.core.domain.JavaClasses\nclass ArchTest")
        write(root, "app/src/integrationTest/kotlin/demo/ItTest.kt", "class ItTest")
        write(root, "app/build/generated/Ignored.kt", "class Ignored")
        write(root, "infra/src/main/java/demo/Infra.java", "class Infra {}")
        write(root, "core-contract/src/main/kotlin/demo/Contract.kt", "interface Contract")
        write(root, ".editorconfig", "root = true")
        write(root, "Dockerfile", "FROM scratch")
        write(root, ".github/workflows/ci.yml", "name: ci")
        write(root, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://x/gradle-9.2.1-bin.zip")
        return root
    }

    @Test
    fun `모듈과 언어를 감지하고 out 패키지를 건너뛰지 않는다`() {
        val p = StackDetector.detect(sampleProject())
        assertEquals("sample", p.name)
        assertEquals("gradle-kotlin-dsl", p.buildTool)
        assertEquals(listOf("app", "infra", "core-contract"), p.modules.map { it.name })

        val app = p.modules.first { it.name == "app" }
        assertEquals(3, app.mainFiles["Kotlin"])
        assertEquals(2, app.testFiles)
        assertTrue(app.springBootApplication)
        assertEquals(listOf("infra"), app.projectDependencies)
        assertEquals(1, p.modules.first { it.name == "infra" }.mainFiles["Java"])
        assertEquals("Kotlin", p.primaryLanguage)
    }

    @Test
    fun `버전 정보와 래퍼 버전을 읽는다`() {
        val p = StackDetector.detect(sampleProject())
        assertEquals("2.3.21", p.jvm.kotlin)
        assertEquals("4.0.0", p.jvm.springBoot)
        assertEquals(21, p.jvm.javaToolchain)
        assertEquals("9.2.1", p.jvm.gradleWrapper)
    }

    @Test
    fun `모듈 이름 조건에 따라 공통 의존성의 적용 모듈이 달라진다`() {
        val p = StackDetector.detect(sampleProject())
        val web = p.frameworks.first { it.id == "spring-web-mvc" }
        assertEquals(listOf("app", "infra"), web.modules)
        val tc = p.testing.first { it.id == "testcontainers" }
        assertEquals(listOf("app"), tc.modules)
        assertEquals(listOf("app"), p.frameworks.first { it.id == "resilience4j" }.modules)
    }

    @Test
    fun `기존 분석 도구와 설정 파일과 인프라를 구분한다`() {
        val p = StackDetector.detect(sampleProject())
        assertEquals(listOf("app/src/test/kotlin/demo/ArchTest.kt"), p.architectureTests)
        assertTrue(".editorconfig" in p.configFiles)
        assertTrue("Dockerfile" in p.infrastructure)
        assertTrue(".github/workflows/ci.yml" in p.infrastructure)
        assertFalse(p.analysisTools.any { it.id == "detekt" })
    }

    @Test
    fun `build 하위 산출물은 소스로 세지 않는다`() {
        val p = StackDetector.detect(sampleProject())
        assertEquals(3, p.modules.first { it.name == "app" }.mainFiles["Kotlin"])
    }

    @Test
    fun `빌드 파일이 없으면 unknown 으로 경고한다`() {
        val dir = Files.createTempDirectory("ilsim-empty")
        val p = StackDetector.detect(dir)
        assertEquals("unknown", p.buildTool)
        assertNotNull(p.warnings.firstOrNull { it.contains("빌드 파일") })
    }

    @Test
    fun `버전 카탈로그 참조를 해석한다`() {
        val root = Files.createTempDirectory("ilsim-catalog")
        write(root, "settings.gradle.kts", "rootProject.name = \"cat\"")
        write(root, "gradle/libs.versions.toml", """
            [versions]
            detekt = "1.23.8"
            [libraries]
            archunit = { module = "com.tngtech.archunit:archunit", version = "1.4.1" }
            [plugins]
            detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
        """.trimIndent())
        write(root, "build.gradle.kts", """
            plugins { alias(libs.plugins.detekt) }
            dependencies { testImplementation(libs.archunit) }
        """.trimIndent())
        val p = StackDetector.detect(root)
        assertEquals("1.23.8", p.analysisTools.first { it.id == "detekt" }.version)
        assertEquals("1.4.1", p.analysisTools.first { it.id == "archunit" }.version)
    }

    @Test
    fun `해석하지 못하는 조건식은 경고로 남긴다`() {
        val (rest, blocks) = NameConditions.split("""if (name.endsWith("a") && name == "b" || x) { foo() }""")
        assertEquals(0, blocks.size)
        assertTrue(rest.contains("if ("))
    }
}
