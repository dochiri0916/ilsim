package com.dochiri.convention.validator

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.EvaluationResult
import org.gradle.api.Project

class ArchUnitArchitectureValidator {
    private static final Set<String> ALLOWED_APPLICATION_SPRING_TYPES = [
            'org.springframework.stereotype.Service',
            'org.springframework.transaction.annotation.Transactional'
    ] as Set
    private static final Set<String> APPLICATION_TECHNICAL_PACKAGE_PREFIXES = [
            'com.amazonaws.',
            'com.querydsl.',
            'feign.',
            'jakarta.persistence.',
            'java.sql.',
            'javax.persistence.',
            'okhttp3.',
            'org.apache.http.',
            'org.hibernate.',
            'org.springframework.',
            'retrofit2.',
            'software.amazon.awssdk.'
    ] as Set

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
        String inboundAdapterPackage = layerPackage(inboundAdapterPackageSegment(convention))
        String applicationServicePackage = "..${convention.applicationPackageSegment}..service.."
        String applicationPortOutPackage = "..${convention.applicationPackageSegment}..port.out.."

        return [
                noClasses()
                        .that().resideInAPackage(domainPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(applicationPackage, infrastructurePackage, inboundAdapterPackage)
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
                        .resideInAnyPackage(infrastructurePackage, inboundAdapterPackage)
                        .because('application must not depend on adapters')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(applicationPackage)
                        .should().dependOnClassesThat(forbiddenApplicationTechnicalType())
                        .because('application must not depend on technical framework types except @Service and @Transactional')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(inboundAdapterPackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(infrastructurePackage)
                        .because('adapter.in must use inbound ports instead of adapter.out directly')
                        .allowEmptyShould(true),
                noClasses()
                        .that().resideInAPackage(infrastructurePackage)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(inboundAdapterPackage)
                        .because('adapter.out must not depend on adapter.in')
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

    private static DescribedPredicate<JavaClass> forbiddenApplicationTechnicalType() {
        return new DescribedPredicate<JavaClass>('technical framework types not allowed in application') {
            @Override
            boolean test(JavaClass javaClass) {
                String className = javaClass.name
                if (ALLOWED_APPLICATION_SPRING_TYPES.contains(className)) {
                    return false
                }
                return APPLICATION_TECHNICAL_PACKAGE_PREFIXES.any { String prefix -> className.startsWith(prefix) }
            }
        }
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

    private static String inboundAdapterPackageSegment(HexagonalConventionExtension convention) {
        if (convention.presentationPackageSegment?.startsWith('adapter.in.')) {
            return 'adapter.in'
        }
        return convention.presentationPackageSegment
    }
}
