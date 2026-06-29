package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.EvaluationResult
import org.gradle.api.Project

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

class ArchUnitArchitectureValidator {
    static List<String> validate(
            Project project,
            HexagonalConventionExtension convention,
            Collection<File> classDirs
    ) {
        List<File> existingClassDirs = classDirs.findAll { File classDir ->
            classDir.exists() && classDir.directory
        }.toList()
        if (existingClassDirs.isEmpty()) {
            return []
        }

        JavaClasses importedClasses = new ClassFileImporter()
                .importPaths(existingClassDirs.collect { File classDir -> classDir.toPath() })

        return evaluateRules(importedClasses, buildRules(convention))
    }

    private static List<ArchRule> buildRules(HexagonalConventionExtension convention) {
        String domainPackage = layerPackage(convention.domainPackageSegment)
        String applicationPackage = layerPackage(convention.applicationPackageSegment)
        String infrastructurePackage = layerPackage(convention.infrastructurePackageSegment)
        String presentationPackage = layerPackage(convention.presentationPackageSegment)
        String applicationServicePackage = "..${convention.applicationPackageSegment}..service.."
        String applicationPortOutPackage = "..${convention.applicationPackageSegment}..port.out.."

        return [
                noClasses()
                        .that().resideInAPackage(domainPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(applicationPackage, infrastructurePackage, presentationPackage)
                        .because('domain must not depend on outer layers')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(domainPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(
                                'org.springframework..',
                                'jakarta.persistence..',
                                'javax.persistence..',
                                'com.querydsl..'
                        )
                        .because('domain must stay framework independent')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(applicationPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(infrastructurePackage, presentationPackage)
                        .because('application must not depend on adapters')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(presentationPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(infrastructurePackage)
                        .because('adapter.in.web must use inbound ports instead of adapter.out directly')
                        .allowEmptyShould(true),
                noClasses()
                        .that().haveSimpleNameEndingWith('Controller')
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(
                                infrastructurePackage,
                                applicationServicePackage,
                                applicationPortOutPackage
                        )
                        .because('controllers must depend on inbound UseCase ports only')
                        .allowEmptyShould(true)
        ]
    }

    private static List<String> evaluateRules(JavaClasses importedClasses, List<ArchRule> rules) {
        List<String> violations = []
        rules.each { ArchRule rule ->
            EvaluationResult result = rule.evaluate(importedClasses)
            if (result.hasViolation()) {
                result.failureReport.details.each { String detail ->
                    violations.add("${rule.description}: ${detail}")
                }
            }
        }
        return violations
    }

    private static String layerPackage(String packageSegment) {
        return "..${packageSegment}.."
    }
}
