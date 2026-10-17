package com.dochiri.ilsim.runner.profile

object Report {

    fun summary(p: ProjectProfile): String = buildString {
        appendLine("프로젝트: ${p.name} (${p.root})")
        appendLine("빌드: ${p.buildTool}${p.jvm.gradleWrapper?.let { " · Gradle $it" } ?: ""} · 모듈 ${p.modules.size}개${if (p.vcs != null) " · ${p.vcs}" else ""}")
        appendLine("언어: ${p.languages.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "-" }}" +
            (p.primaryLanguage?.let { " → 주 언어 $it" } ?: ""))
        appendLine(
            "버전: Kotlin ${p.jvm.kotlin ?: "-"} · JVM toolchain ${p.jvm.javaToolchain ?: "-"} · target ${p.jvm.jvmTarget ?: "-"}" +
                " · Spring Boot ${p.jvm.springBoot ?: "-"}",
        )
        appendLine()
        appendLine("모듈:")
        p.modules.forEach { m ->
            val langs = m.mainFiles.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "소스 없음" }
            val flags = listOfNotNull(if (m.springBootApplication) "SpringBootApplication" else null).joinToString()
            appendLine("  - ${m.name}: $langs, 테스트 ${m.testFiles}${if (flags.isNotEmpty()) " [$flags]" else ""}" +
                (if (m.projectDependencies.isNotEmpty()) " → ${m.projectDependencies.joinToString()}" else ""))
        }
        section("프레임워크·라이브러리", p.frameworks)
        section("테스트 도구", p.testing)
        section("기존 정적 분석 도구", p.analysisTools, emptyText = "없음")
        list("설정 파일", p.configFiles)
        list("인프라·CI", p.infrastructure)
        list("기존 아키텍처 테스트", p.architectureTests, emptyText = "없음")
        if (p.warnings.isNotEmpty()) {
            appendLine()
            appendLine("경고:")
            p.warnings.forEach { appendLine("  ! $it") }
        }
    }

    private fun StringBuilder.section(title: String, items: List<Detected>, emptyText: String = "-") {
        appendLine()
        appendLine("$title:")
        if (items.isEmpty()) appendLine("  $emptyText")
        items.forEach { d ->
            val scope = if (d.modules.size > 3) "${d.modules.size}개 모듈" else d.modules.joinToString()
            appendLine("  - ${d.label}${d.version?.let { " $it" } ?: ""}  [$scope]")
        }
    }

    private fun StringBuilder.list(title: String, items: List<String>, emptyText: String = "없음") {
        appendLine()
        appendLine("$title:")
        if (items.isEmpty()) appendLine("  $emptyText")
        items.forEach { appendLine("  - $it") }
    }
}
