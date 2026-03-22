package com.example.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
final class HexagonalArchitectureTest {

    @ArchTest
    public static final ArchRule RULE_DOMAIN = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infrastructure..", "..presentation..");

    @ArchTest
    public static final ArchRule RULE_FRAME = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..",
                    "com.querydsl.."
            );

    @ArchTest
    public static final ArchRule RULE_APP = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..presentation..");

    @ArchTest
    public static final ArchRule RULE_WEB = noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

    private HexagonalArchitectureTest() {
    }

    @Test
    void archUnitRulesAreLoaded() {
        assertNotNull(RULE_DOMAIN, "아키텍처 룰은 null 이면 안 됩니다.");
    }

}
