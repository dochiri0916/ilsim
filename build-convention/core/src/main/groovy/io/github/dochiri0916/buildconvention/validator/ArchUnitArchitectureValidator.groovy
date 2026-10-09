package io.github.dochiri0916.buildconvention.validator

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.support.TechnicalDependencyCatalog
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.EvaluationResult
import org.gradle.api.Project

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

        // ArchUnit only logs class files it cannot parse (for example a newer Java version) and then
        // checks the rest, which would let those classes pass unchecked.
        List<String> unreadable = (compiledClassNames(existingClassDirs) - importedClasses.collect { it.name }).sort()
        if (!unreadable.isEmpty()) {
            return ["ArchUnit could not read compiled classes; update the bundled bytecode reader for this Java version: ${unreadable.join(', ')}".toString()]
        }

        return evaluateRules(importedClasses, buildRules(convention))
    }

    private static Set<String> compiledClassNames(List<File> classDirs) {
        Set<String> names = [] as Set
        classDirs.each { File classDir ->
            classDir.eachFileRecurse { File file ->
                if (file.file && file.name.endsWith('.class') && !(file.name in ['module-info.class', 'package-info.class'])) {
                    String relative = classDir.toPath().relativize(file.toPath()).toString()
                    names.add(relative.substring(0, relative.length() - '.class'.length()).replace(File.separator, '.'))
                }
            }
        }
        return names
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
                        .resideInAnyPackage(TechnicalDependencyCatalog.DOMAIN_FORBIDDEN_ARCHUNIT_PACKAGES as String[])
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
                return TechnicalDependencyCatalog.isForbiddenInApplication(javaClass.name)
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
