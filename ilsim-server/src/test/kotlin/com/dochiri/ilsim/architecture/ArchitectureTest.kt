package com.dochiri.ilsim.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/** 헥사고날 규칙: Adapter → Application → Domain. 다른 모듈은 공개 `api` 로만 쓴다. */
@AnalyzeClasses(packages = ["com.dochiri.ilsim"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    private val frameworks = arrayOf("org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..", "javax.persistence..")

    @ArchTest
    val `도메인은 프레임워크와 바깥 계층을 모른다`: ArchRule = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..configuration..", *frameworks)

    @ArchTest
    val `애플리케이션은 어댑터와 Spring 을 모른다`: ArchRule = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..configuration..", "org.springframework..", "jakarta..", "tools.jackson..")

    @ArchTest
    val `인바운드와 아웃바운드 어댑터는 서로 쓰지 않는다`: ArchRule = noClasses().that().resideInAPackage("..adapter.out..")
        .should().dependOnClassesThat().resideInAPackage("..adapter.in..")

    @ArchTest
    val `인바운드 어댑터는 영속성 어댑터를 직접 호출하지 않는다`: ArchRule = noClasses().that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAPackage("..adapter.out..")

    @ArchTest
    val `컨텍스트 간에는 공개 api 로만 접근한다`: ArchRule = noClasses().that().resideInAPackage("com.dochiri.ilsim.task..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.dochiri.ilsim.auth..", "com.dochiri.ilsim.secret.domain..", "com.dochiri.ilsim.secret.application..",
            "com.dochiri.ilsim.secret.adapter..", "com.dochiri.ilsim.jev.application..", "com.dochiri.ilsim.jev.adapter..",
        )

    @ArchTest
    val `jev 는 secret 의 공개 api 만 쓴다`: ArchRule = noClasses().that().resideInAPackage("com.dochiri.ilsim.jev..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.dochiri.ilsim.task..", "com.dochiri.ilsim.auth..", "com.dochiri.ilsim.secret.domain..",
            "com.dochiri.ilsim.secret.application..", "com.dochiri.ilsim.secret.adapter..",
        )

    @ArchTest
    val `secret 과 auth 는 다른 컨텍스트를 모른다`: ArchRule = noClasses().that().resideInAnyPackage("com.dochiri.ilsim.secret..", "com.dochiri.ilsim.auth..")
        .should().dependOnClassesThat().resideInAnyPackage("com.dochiri.ilsim.task..", "com.dochiri.ilsim.jev..")

    @ArchTest
    val `컨텍스트 사이에 순환 참조가 없다`: ArchRule = slices().matching("com.dochiri.ilsim.(*)..").should().beFreeOfCycles()
}
