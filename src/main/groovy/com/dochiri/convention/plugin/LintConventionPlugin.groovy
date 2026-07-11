package com.dochiri.convention.plugin

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.validator.ArchitectureConventionValidator
import com.dochiri.convention.validator.ArchUnitArchitectureValidator
import com.dochiri.convention.validator.ChangedCodeCoverageValidator
import com.dochiri.convention.validator.DomainStaticFactoryValidator
import com.dochiri.convention.validator.EntityNamingConventionValidator
import com.dochiri.convention.validator.HexagonalArchitectureValidator
import com.dochiri.convention.validator.MigrationConventionValidator
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

class LintConventionPlugin implements Plugin<Project> {
    private static final String LOMBOK_DEPENDENCY = 'org.projectlombok:lombok:1.18.36'
    private static final List<String> REQUIRED_CHECK_TASK_NAMES = [
            'checkstyleDomain',
            'pmdDomain',
            'spotbugsDomain',
            'validateArchUnitArchitecture',
            'validateChangedCodeCoverage',
            'jacocoTestCoverageVerification',
            'validateHexagonalArchitecture',
            'validateEntityNamingConvention',
            'validateDomainStaticFactoryConvention',
            'validateArchitectureConventions',
            'validateClaudeConventions',
            'validateMigrationConventions',
            'validateJavaVersionConvention'
    ].asImmutable()
    private static final List<String> REQUIRED_TRUE_CONVENTION_FLAGS = [
            'enforceDomainEntitySeparation',
            'enforceDomainStaticFactoryMethod',
            'requireTableAnnotation'
    ].asImmutable()
    private static final Map<String, BigDecimal> MINIMUM_COVERAGE_VALUES = [
            overallLineCoverageMinimum        : 0.85G,
            overallBranchCoverageMinimum      : 0.80G,
            domainLineCoverageMinimum         : 0.95G,
            domainBranchCoverageMinimum       : 0.90G,
            applicationLineCoverageMinimum    : 0.90G,
            applicationBranchCoverageMinimum  : 0.85G,
            infrastructureLineCoverageMinimum : 0.80G,
            infrastructureBranchCoverageMinimum: 0.70G,
            inboundAdapterLineCoverageMinimum : 0.80G,
            inboundAdapterBranchCoverageMinimum: 0.70G,
            changedLineCoverageMinimum        : 0.90G,
            changedBranchCoverageMinimum      : 0.85G
    ].asImmutable()
    private static final Map<String, Integer> MINIMUM_SCORE_VALUES = [
            mutationScoreMinimum: 80,
            testStrengthMinimum: 85
    ].asImmutable()
    private static final Map<String, String> REQUIRED_PACKAGE_SEGMENT_VALUES = [
            domainPackageSegment        : 'domain',
            applicationPackageSegment   : 'application',
            infrastructurePackageSegment: 'adapter.out',
            presentationPackageSegment  : 'adapter.in.web'
    ].asImmutable()

    @Override
    void apply(Project project) {
        HexagonalConventionExtension convention =
                project.extensions.create('hexagonalConvention', HexagonalConventionExtension)

        configureJava21Convention(project)
        configureLombokConvention(project)

        project.pluginManager.apply('checkstyle')
        project.pluginManager.apply('pmd')
        project.pluginManager.apply('com.github.spotbugs')
        project.pluginManager.apply('jacoco')

        def checkstyleConfig = project.layout.buildDirectory.file('lint-convention/checkstyle/checkstyle.xml')
        def checkstyleDomainConfig = project.layout.buildDirectory.file('lint-convention/checkstyle/checkstyle-domain.xml')
        def pmdRuleset = project.layout.buildDirectory.file('lint-convention/pmd/ruleset.xml')
        def pmdDomainRuleset = project.layout.buildDirectory.file('lint-convention/pmd/ruleset-domain.xml')
        def spotbugsExclude = project.layout.buildDirectory.file('lint-convention/spotbugs/exclude.xml')
        def spotbugsDomainInclude = project.layout.buildDirectory.file('lint-convention/spotbugs/include-domain.xml')

        def prepareLintConfig = project.tasks.register('prepareLintConfig') { task ->
            task.group = 'verification'
            task.description = 'Prepares lint configuration files for convention plugin.'
            task.outputs.files(
                    checkstyleConfig,
                    checkstyleDomainConfig,
                    pmdRuleset,
                    pmdDomainRuleset,
                    spotbugsExclude,
                    spotbugsDomainInclude
            )
            task.doLast {
                materializeResource('/lint/checkstyle.xml', checkstyleConfig.get().asFile)
                materializeResource('/lint/checkstyle-domain.xml', checkstyleDomainConfig.get().asFile)
                materializeResource('/lint/pmd-ruleset.xml', pmdRuleset.get().asFile)
                materializeResource('/lint/pmd-domain-ruleset.xml', pmdDomainRuleset.get().asFile)
                materializeResource('/lint/spotbugs-exclude.xml', spotbugsExclude.get().asFile)
                materializeResource('/lint/spotbugs-domain-include.xml', spotbugsDomainInclude.get().asFile)
            }
        }

        project.extensions.configure(CheckstyleExtension) { CheckstyleExtension extension ->
            extension.toolVersion = '10.21.4'
            extension.configFile = checkstyleConfig.get().asFile
            extension.ignoreFailures = false
        }

        project.tasks.withType(Checkstyle).configureEach { Checkstyle task ->
            task.dependsOn(prepareLintConfig)
            if (task.name != 'checkstyleDomain') {
                task.configFile = checkstyleConfig.get().asFile
            }
            task.reports { reports ->
                reports.xml.required = true
                reports.html.required = true
            }
        }

        project.extensions.configure(PmdExtension) { PmdExtension extension ->
            extension.toolVersion = '7.17.0'
            extension.consoleOutput = true
            extension.ignoreFailures = false
            extension.ruleSets = []
            extension.ruleSetFiles = project.files(pmdRuleset.get().asFile)
        }

        project.tasks.withType(Pmd).configureEach { Pmd task ->
            task.dependsOn(prepareLintConfig)
            if (task.name != 'pmdDomain') {
                task.ruleSets = []
                task.ruleSetFiles = project.files(pmdRuleset.get().asFile)
            }
            task.reports { reports ->
                reports.xml.required = true
                reports.html.required = true
            }
        }

        project.extensions.configure(SpotBugsExtension) { SpotBugsExtension extension ->
            extension.toolVersion = '4.9.8'
            extension.effort = Effort.valueOf('MAX')
            extension.reportLevel = Confidence.valueOf('LOW')
            extension.ignoreFailures = false
            extension.excludeFilter = spotbugsExclude.get().asFile
        }

        project.tasks.withType(SpotBugsTask).configureEach { SpotBugsTask task ->
            task.dependsOn(prepareLintConfig)
            if (task.name != 'spotbugsDomain') {
                task.excludeFilter.set(spotbugsExclude)
            }
            task.reports { reports ->
                def htmlReport = reports.findByName('html')
                if (htmlReport != null) {
                    htmlReport.required = true
                }
            }
        }

        SourceSetContainer sourceSets = project.extensions.findByType(SourceSetContainer)
        if (sourceSets != null) {
            def mainSourceSet = sourceSets.named('main')

            project.extensions.configure(JacocoPluginExtension) { JacocoPluginExtension extension ->
                extension.toolVersion = '0.8.12'
            }

            def jacocoTestReport = project.tasks.named('jacocoTestReport', JacocoReport) { JacocoReport task ->
                task.dependsOn(project.tasks.named('test'))
                task.reports { reports ->
                    reports.xml.required = true
                    reports.html.required = true
                    reports.csv.required = false
                }
            }

            def jacocoTestCoverageVerification =
                    project.tasks.named('jacocoTestCoverageVerification', JacocoCoverageVerification) {
                        JacocoCoverageVerification task ->
                            task.dependsOn(project.tasks.named('test'))
                    }

            def changedCoverageBaseRef = project.providers.gradleProperty('changedCoverageBaseRef')
            def validateChangedCodeCoverage = project.tasks.register('validateChangedCodeCoverage') { task ->
                task.group = 'verification'
                task.description = 'Validates changed production code coverage from JaCoCo XML and git diff.'
                task.dependsOn(jacocoTestReport)
                task.inputs.file(project.layout.buildDirectory.file('reports/jacoco/test/jacocoTestReport.xml'))
                task.inputs.property('changedCoverageBaseRef', changedCoverageBaseRef.orElse('auto'))
                task.doLast {
                    File jacocoXmlReport = project.layout.buildDirectory
                            .file('reports/jacoco/test/jacocoTestReport.xml')
                            .get()
                            .asFile
                    String resolvedBaseRef = resolveChangedCoverageBaseRef(project, changedCoverageBaseRef.orNull)
                    if (resolvedBaseRef == null) {
                        throw new GradleException(
                                'Changed code coverage requires a Git base ref. '
                                        + 'Set -PchangedCoverageBaseRef=origin/main.'
                        )
                    }
                    List<String> violations = ChangedCodeCoverageValidator.validate(
                            project,
                            convention,
                            jacocoXmlReport,
                            resolvedBaseRef
                    )
                    if (!violations.isEmpty()) {
                        throw new GradleException("Changed code coverage violations:\n - ${violations.join('\n - ')}")
                    }
                }
            }

            def validatePitMutationGate = project.tasks.register('validatePitMutationGate') { task ->
                task.group = 'verification'
                task.description = 'Runs the PIT mutation gate when the info.solidsoft.pitest plugin is applied.'
                task.doLast {
                    if (!project.plugins.hasPlugin('info.solidsoft.pitest')) {
                        throw new GradleException(
                                'PIT mutation gate requires applying the info.solidsoft.pitest plugin in the target project.'
                        )
                    }
                }
            }

            project.tasks.withType(Test).configureEach { Test task ->
                task.useJUnitPlatform()
                task.ignoreFailures = false
                task.finalizedBy(jacocoTestReport)
            }

            def checkstyleDomain = project.tasks.register('checkstyleDomain', Checkstyle) { Checkstyle task ->
                task.group = 'verification'
                task.description = 'Runs Checkstyle for domain layer sources only.'
                task.dependsOn(prepareLintConfig)
                task.configFile = checkstyleDomainConfig.get().asFile
                task.classpath = project.files()
                task.source = project.fileTree(project.file('src/main/java')) {
                    include "**/${convention.domainPackageSegment}/**/*.java"
                }
                task.reports { reports ->
                    reports.xml.required = true
                    reports.html.required = true
                }
            }

            def pmdDomain = project.tasks.register('pmdDomain', Pmd) { Pmd task ->
                task.group = 'verification'
                task.description = 'Runs PMD for domain layer sources only.'
                task.dependsOn(prepareLintConfig)
                task.ruleSets = []
                task.ruleSetFiles = project.files(pmdDomainRuleset.get().asFile)
                task.ignoreFailures = false
                task.consoleOutput = true
                task.classpath = mainSourceSet.get().compileClasspath
                task.source = project.fileTree(project.file('src/main/java')) {
                    include "**/${convention.domainPackageSegment}/**/*.java"
                }
                task.reports { reports ->
                    reports.xml.required = true
                    reports.html.required = true
                }
            }

            def spotbugsDomain = project.tasks.register('spotbugsDomain', SpotBugsTask) { SpotBugsTask task ->
                task.group = 'verification'
                task.description = 'Runs SpotBugs for compiled domain classes only.'
                task.dependsOn(project.tasks.named('classes'))
                task.includeFilter.set(spotbugsDomainInclude)
                task.sourceDirs.setFrom(mainSourceSet.get().allSource.srcDirs)
                task.classDirs.setFrom(mainSourceSet.get().output.classesDirs)
                task.auxClassPaths.setFrom(mainSourceSet.get().runtimeClasspath)
                task.reports { reports ->
                    def htmlReport = reports.findByName('html')
                    if (htmlReport != null) {
                        htmlReport.required = true
                    }
                }
            }

            def validateArchUnitArchitecture = project.tasks.register('validateArchUnitArchitecture') { task ->
                task.group = 'verification'
                task.description = 'Validates hexagonal architecture dependency direction with ArchUnit.'
                task.dependsOn(project.tasks.named('classes'))
                task.inputs.files(mainSourceSet.get().output.classesDirs)
                task.doLast {
                    List<String> violations = ArchUnitArchitectureValidator.validate(
                            project,
                            convention,
                            mainSourceSet.get().output.classesDirs.files
                    )
                    if (!violations.isEmpty()) {
                        throw new GradleException("ArchUnit architecture violations:\n - ${violations.join('\n - ')}")
                    }
                }
            }

            project.tasks.named('check') { task ->
                task.dependsOn(checkstyleDomain)
                task.dependsOn(pmdDomain)
                task.dependsOn(spotbugsDomain)
                task.dependsOn(validateArchUnitArchitecture)
                task.dependsOn(jacocoTestReport)
                task.dependsOn(jacocoTestCoverageVerification)
                task.dependsOn(validateChangedCodeCoverage)
            }

            project.afterEvaluate {
                String domainSegment = convention.domainPackageSegment
                checkstyleDomain.configure { Checkstyle task ->
                    task.source = project.fileTree(project.file('src/main/java')) {
                        include "**/${domainSegment}/**/*.java"
                    }
                }
                pmdDomain.configure { Pmd task ->
                    task.source = project.fileTree(project.file('src/main/java')) {
                        include "**/${domainSegment}/**/*.java"
                    }
                }
                jacocoTestCoverageVerification.configure { JacocoCoverageVerification task ->
                    configureCoverageRules(task, convention)
                }
                if (project.plugins.hasPlugin('info.solidsoft.pitest')) {
                    configurePitMutationGate(project, convention)
                    validatePitMutationGate.configure { task ->
                        task.dependsOn(project.tasks.named('pitest'))
                    }
                    project.tasks.named('check') { task ->
                        task.dependsOn(validatePitMutationGate)
                    }
                } else if (convention.enforcePitOnCheck) {
                    project.tasks.named('check') { task ->
                        task.dependsOn(validatePitMutationGate)
                    }
                }
            }
        }

        def validateHexagonalArchitecture = project.tasks.register('validateHexagonalArchitecture') { task ->
            task.group = 'verification'
            task.description = 'Validates hexagonal architecture dependency direction.'
            task.inputs.files(project.fileTree(project.projectDir) {
                include 'src/main/java/**/*.java'
                include 'src/main/groovy/**/*.groovy'
            })
            task.doLast {
                List<String> violations = HexagonalArchitectureValidator.validate(project, convention)
                if (!violations.isEmpty()) {
                    throw new GradleException("Hexagonal architecture violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        def validateEntityNamingConvention = project.tasks.register('validateEntityNamingConvention') { task ->
            task.group = 'verification'
            task.description = 'Validates domain/entity separation and naming convention: entity singular, table plural.'
            task.inputs.files(project.fileTree(project.projectDir) {
                include 'src/main/java/**/*.java'
            })
            task.doLast {
                List<String> violations = EntityNamingConventionValidator.validate(project, convention)
                if (!violations.isEmpty()) {
                    throw new GradleException("Entity/table naming violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        def validateDomainStaticFactoryConvention = project.tasks.register('validateDomainStaticFactoryConvention') { task ->
            task.group = 'verification'
            task.description = 'Validates static factory convention for domain classes.'
            task.inputs.files(project.fileTree(project.projectDir) {
                include 'src/main/java/**/*.java'
            })
            task.doLast {
                List<String> violations = DomainStaticFactoryValidator.validate(project, convention)
                if (!violations.isEmpty()) {
                    throw new GradleException("Domain static factory violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        def validateArchitectureConventions = project.tasks.register('validateArchitectureConventions') { task ->
            task.group = 'verification'
            task.description = 'Validates AGENTS.md architecture, DDD, adapter, and test conventions.'
            task.inputs.files(project.fileTree(project.projectDir) {
                include 'src/main/java/**/*.java'
                include 'src/test/java/**/*.java'
                include 'src/main/resources/**/messages*.properties'
            })
            task.doLast {
                List<String> violations = ArchitectureConventionValidator.validate(project, convention)
                if (!violations.isEmpty()) {
                    throw new GradleException("Architecture convention violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        def validateClaudeConventions = project.tasks.register('validateClaudeConventions') { task ->
            task.group = 'verification'
            task.description = 'Deprecated alias for validateArchitectureConventions.'
            task.dependsOn(validateArchitectureConventions)
        }

        def validateMigrationConventions = project.tasks.register('validateMigrationConventions') { task ->
            task.group = 'verification'
            task.description = 'Validates Flyway/Liquibase SQL migration identifier and reference rules.'
            task.inputs.files(project.fileTree(project.projectDir) {
                include 'src/main/resources/**/*.sql'
            })
            task.doLast {
                List<String> violations = MigrationConventionValidator.validate(project)
                if (!violations.isEmpty()) {
                    throw new GradleException("Migration convention violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        def validateJavaVersionConvention = project.tasks.register('validateJavaVersionConvention') { task ->
            task.group = 'verification'
            task.description = 'Validates Java 21+ toolchain and compiler release conventions.'
            task.doLast {
                List<String> violations = validateJavaVersion(project)
                if (!violations.isEmpty()) {
                    throw new GradleException("Java version convention violations:\n - ${violations.join('\n - ')}")
                }
            }
        }

        project.tasks.named('check') { task ->
            task.dependsOn(project.tasks.withType(SpotBugsTask))
            task.dependsOn(validateHexagonalArchitecture)
            task.dependsOn(validateEntityNamingConvention)
            task.dependsOn(validateDomainStaticFactoryConvention)
            task.dependsOn(validateArchitectureConventions)
            task.dependsOn(validateClaudeConventions)
            task.dependsOn(validateMigrationConventions)
            task.dependsOn(validateJavaVersionConvention)
            task.doFirst {
                validateRequiredCheckTasks(project, convention)
            }
        }

        project.gradle.taskGraph.whenReady { graph ->
            def checkTask = project.tasks.findByName('check')
            if (checkTask != null && graph.hasTask(checkTask)) {
                validateRequiredCheckTasks(project, convention)
            }
        }
    }

    private static void validateRequiredCheckTasks(Project project, HexagonalConventionExtension convention) {
        Set<String> qualityTaskNames = project.tasks.matching { task ->
            task instanceof Checkstyle || task instanceof Pmd || task instanceof SpotBugsTask
        }.collect { task -> task.name }.toSet()
        Set<String> protectedTaskNames = (REQUIRED_CHECK_TASK_NAMES + qualityTaskNames + [
                'check',
                'test',
                'jacocoTestReport',
                'jacocoTestCoverageVerification'
        ]).toSet()
        Set<String> onlyIfProtectedTaskNames = protectedTaskNames - [
                'jacocoTestReport',
                'jacocoTestCoverageVerification'
        ]

        List<String> excludedTasks = protectedTaskNames.findAll { String taskName ->
            isTaskExcluded(project, taskName)
        }.sort()

        List<String> disabledTasks = protectedTaskNames.findAll { String taskName ->
            def task = project.tasks.findByName(taskName)
            task != null && !task.enabled
        }.sort()
        List<String> disabledQualityTasks = qualityTaskNames.findAll { String taskName ->
            def task = project.tasks.findByName(taskName)
            task != null && !task.enabled
        }.sort()

        List<String> ignoredFailureTasks = []
        project.tasks.withType(Test).each { Test task ->
            if (task.ignoreFailures) {
                ignoredFailureTasks.add(task.name)
            }
        }
        project.tasks.withType(Checkstyle).each { Checkstyle task ->
            if (task.ignoreFailures) {
                ignoredFailureTasks.add(task.name)
            }
        }
        project.tasks.withType(Pmd).each { Pmd task ->
            if (task.ignoreFailures) {
                ignoredFailureTasks.add(task.name)
            }
        }
        project.tasks.withType(SpotBugsTask).each { SpotBugsTask task ->
            if (task.ignoreFailures) {
                ignoredFailureTasks.add(task.name)
            }
        }

        List<String> relaxedConventionFlags = REQUIRED_TRUE_CONVENTION_FLAGS.findAll { String flagName ->
            convention.hasProperty(flagName) && convention."${flagName}" == false
        }
        List<String> relaxedCoverageMinimums = MINIMUM_COVERAGE_VALUES.findAll { String flagName, BigDecimal minimum ->
            convention.hasProperty(flagName) && convention."${flagName}" < minimum
        }.collect { String flagName, BigDecimal minimum ->
            "${flagName}=${convention."${flagName}"} < ${minimum}"
        }
        List<String> relaxedScoreMinimums = MINIMUM_SCORE_VALUES.findAll { String flagName, Integer minimum ->
            convention.hasProperty(flagName) && convention."${flagName}" < minimum
        }.collect { String flagName, Integer minimum ->
            "${flagName}=${convention."${flagName}"} < ${minimum}"
        }
        List<String> modifiedPackageSegments = REQUIRED_PACKAGE_SEGMENT_VALUES.findAll {
            String propertyName, String requiredValue ->
                convention.hasProperty(propertyName) && convention."${propertyName}" != requiredValue
        }.collect { String propertyName, String requiredValue ->
            "${propertyName}=${convention."${propertyName}"} != ${requiredValue}"
        }
        List<String> unsatisfiedOnlyIfTasks = onlyIfProtectedTaskNames.findAll { String taskName ->
            def task = project.tasks.findByName(taskName)
            task != null && task.enabled && !isOnlyIfSatisfied(task)
        }.sort()
        List<String> missingCheckDependencies = findMissingCheckDependencies(project, protectedTaskNames - ['check'])
        List<String> modifiedTestConfigurations = findModifiedTestConfigurations(project)
        List<String> emptySourceTasks = findEmptySourceTasks(project, convention)
        List<String> missingQualitySourceFiles = findMissingQualitySourceFiles(project, convention)
        List<String> emptyCoverageTasks = findEmptyCoverageTasks(project)
        List<String> modifiedCoverageInputs = findModifiedCoverageInputs(project)
        List<String> modifiedBytecodeAnalysisInputs = findModifiedBytecodeAnalysisInputs(project)
        List<String> modifiedQualityConfigurations = findModifiedQualityConfigurations(project)

        if (excludedTasks.isEmpty()
                && disabledTasks.isEmpty()
                && disabledQualityTasks.isEmpty()
                && ignoredFailureTasks.isEmpty()
                && relaxedConventionFlags.isEmpty()
                && relaxedCoverageMinimums.isEmpty()
                && relaxedScoreMinimums.isEmpty()
                && modifiedPackageSegments.isEmpty()
                && unsatisfiedOnlyIfTasks.isEmpty()
                && missingCheckDependencies.isEmpty()
                && modifiedTestConfigurations.isEmpty()
                && emptySourceTasks.isEmpty()
                && missingQualitySourceFiles.isEmpty()
                && emptyCoverageTasks.isEmpty()
                && modifiedCoverageInputs.isEmpty()
                && modifiedBytecodeAnalysisInputs.isEmpty()
                && modifiedQualityConfigurations.isEmpty()) {
            return
        }

        List<String> violations = []
        if (!excludedTasks.isEmpty()) {
            violations.add("-x excluded tasks: ${excludedTasks.join(', ')}")
        }
        if (!disabledTasks.isEmpty()) {
            violations.add("disabled tasks: ${disabledTasks.join(', ')}")
        }
        if (!disabledQualityTasks.isEmpty()) {
            violations.add("disabled quality tasks: ${disabledQualityTasks.join(', ')}")
        }
        if (!ignoredFailureTasks.isEmpty()) {
            violations.add("ignoreFailures=true tasks: ${ignoredFailureTasks.join(', ')}")
        }
        if (!relaxedConventionFlags.isEmpty()) {
            violations.add("relaxed convention flags: ${relaxedConventionFlags.join(', ')}")
        }
        if (!relaxedCoverageMinimums.isEmpty()) {
            violations.add("relaxed coverage minimums: ${relaxedCoverageMinimums.join(', ')}")
        }
        if (!relaxedScoreMinimums.isEmpty()) {
            violations.add("relaxed mutation score minimums: ${relaxedScoreMinimums.join(', ')}")
        }
        if (!modifiedPackageSegments.isEmpty()) {
            violations.add("modified package segments: ${modifiedPackageSegments.join(', ')}")
        }
        if (!unsatisfiedOnlyIfTasks.isEmpty()) {
            violations.add("onlyIf-skipped tasks: ${unsatisfiedOnlyIfTasks.join(', ')}")
        }
        if (!missingCheckDependencies.isEmpty()) {
            violations.add("check missing dependencies: ${missingCheckDependencies.join(', ')}")
        }
        if (!modifiedTestConfigurations.isEmpty()) {
            violations.add("modified test execution configurations: ${modifiedTestConfigurations.join(', ')}")
        }
        if (!emptySourceTasks.isEmpty()) {
            violations.add("empty source tasks: ${emptySourceTasks.join(', ')}")
        }
        if (!missingQualitySourceFiles.isEmpty()) {
            violations.add("quality tasks missing source files: ${missingQualitySourceFiles.join('; ')}")
        }
        if (!emptyCoverageTasks.isEmpty()) {
            violations.add("empty coverage tasks: ${emptyCoverageTasks.join(', ')}")
        }
        if (!modifiedCoverageInputs.isEmpty()) {
            violations.add("modified coverage inputs: ${modifiedCoverageInputs.join(', ')}")
        }
        if (!modifiedBytecodeAnalysisInputs.isEmpty()) {
            violations.add("modified bytecode analysis inputs: ${modifiedBytecodeAnalysisInputs.join(', ')}")
        }
        if (!modifiedQualityConfigurations.isEmpty()) {
            violations.add("modified quality rule configurations: ${modifiedQualityConfigurations.join(', ')}")
        }

        throw new GradleException(
                'Build convention verification must not be disabled. '
                        + "${violations.join('; ')}. "
                        + 'Fix the violations instead of disabling convention checks.'
        )
    }

    private static boolean isTaskExcluded(Project project, String taskName) {
        project.gradle.startParameter.excludedTaskNames.any { String excludedTaskName ->
            excludedTaskName == taskName || excludedTaskName.endsWith(":${taskName}")
        }
    }

    private static boolean isOnlyIfSatisfied(def task) {
        try {
            return task.onlyIf.isSatisfiedBy(task)
        } catch (Exception ignored) {
            return true
        }
    }

    private static List<String> findMissingCheckDependencies(Project project, Set<String> expectedTaskNames) {
        def checkTask = project.tasks.findByName('check')
        if (checkTask == null) {
            return ['check']
        }

        Set<String> dependencyNames = checkTask.taskDependencies
                .getDependencies(checkTask)
                .collect { task -> task.name }
                .toSet()

        expectedTaskNames.findAll { String taskName ->
            project.tasks.findByName(taskName) != null && !dependencyNames.contains(taskName)
        }.sort()
    }

    private static List<String> findModifiedTestConfigurations(Project project) {
        List<String> violations = []
        project.tasks.withType(Test).each { Test task ->
            if (task.includes != null && !task.includes.isEmpty()) {
                violations.add("${task.name}.includes=${task.includes}")
            }
            if (task.excludes != null && !task.excludes.isEmpty()) {
                violations.add("${task.name}.excludes=${task.excludes}")
            }
            if (task.filter.includePatterns != null && !task.filter.includePatterns.isEmpty()) {
                violations.add("${task.name}.filter.includePatterns=${task.filter.includePatterns}")
            }
            if (task.filter.excludePatterns != null && !task.filter.excludePatterns.isEmpty()) {
                violations.add("${task.name}.filter.excludePatterns=${task.filter.excludePatterns}")
            }
            JacocoTaskExtension jacoco = task.extensions.findByType(JacocoTaskExtension)
            if (jacoco != null && !jacoco.enabled) {
                violations.add("${task.name}.jacoco.enabled=false")
            }
        }
        return violations.sort()
    }

    private static List<String> findEmptySourceTasks(Project project, HexagonalConventionExtension convention) {
        List<File> mainJavaFiles = javaFiles(project, 'src/main/java')
        List<File> testJavaFiles = javaFiles(project, 'src/test/java')
        List<File> domainJavaFiles = mainJavaFiles.findAll { File file ->
            hasPathSegment(file, convention.domainPackageSegment)
        }

        List<String> emptySourceTasks = []
        if (!mainJavaFiles.isEmpty()) {
            emptySourceTasks.addAll(emptySourceTaskNames(project, ['checkstyleMain', 'pmdMain']))
        }
        if (!testJavaFiles.isEmpty()) {
            emptySourceTasks.addAll(emptySourceTaskNames(project, ['checkstyleTest', 'pmdTest']))
        }
        if (!domainJavaFiles.isEmpty()) {
            emptySourceTasks.addAll(emptySourceTaskNames(project, ['checkstyleDomain', 'pmdDomain']))
        }
        return emptySourceTasks.sort()
    }

    private static List<String> emptySourceTaskNames(Project project, List<String> taskNames) {
        taskNames.findAll { String taskName ->
            def task = project.tasks.findByName(taskName)
            task != null && task.enabled && !hasTaskSourceFiles(task)
        }
    }

    private static List<String> findMissingQualitySourceFiles(
            Project project,
            HexagonalConventionExtension convention
    ) {
        List<File> mainJavaFiles = javaFiles(project, 'src/main/java')
        List<File> testJavaFiles = javaFiles(project, 'src/test/java')
        List<File> domainJavaFiles = mainJavaFiles.findAll { File file ->
            hasPathSegment(file, convention.domainPackageSegment)
        }

        Map<String, List<File>> expectedSourceFiles = [
                checkstyleMain  : mainJavaFiles,
                pmdMain         : mainJavaFiles,
                checkstyleTest  : testJavaFiles,
                pmdTest         : testJavaFiles,
                checkstyleDomain: domainJavaFiles,
                pmdDomain       : domainJavaFiles
        ]

        List<String> violations = []
        expectedSourceFiles.each { String taskName, List<File> expectedFiles ->
            if (expectedFiles.isEmpty()) {
                return
            }
            def task = project.tasks.findByName(taskName)
            if (task == null || !task.enabled) {
                return
            }

            List<File> actualFiles = task.source.files.findAll { File file -> file.isFile() }.toList()
            List<File> missingFiles = missingExpectedFiles(expectedFiles, actualFiles)
            if (!missingFiles.isEmpty()) {
                violations.add("${taskName}: ${limitedRelativeFiles(project, missingFiles)}")
            }
        }
        return violations.sort()
    }

    private static boolean hasTaskSourceFiles(def task) {
        try {
            return !task.source.files.findAll { File file -> file.isFile() }.isEmpty()
        } catch (Exception ignored) {
            return true
        }
    }

    private static List<String> findEmptyCoverageTasks(Project project) {
        if (javaFiles(project, 'src/main/java').isEmpty()) {
            return []
        }

        def verificationTask = project.tasks.findByName('jacocoTestCoverageVerification')
        if (verificationTask == null || !verificationTask.enabled) {
            return []
        }

        try {
            boolean compiledClassesExist = project.file('build/classes/java/main').exists()
                    || project.file('build/classes/groovy/main').exists()
            if (compiledClassesExist
                    && verificationTask.classDirectories.files.findAll { File file -> file.exists() }.isEmpty()) {
                return ['jacocoTestCoverageVerification']
            }
        } catch (Exception ignored) {
            return []
        }
        return []
    }

    private static List<String> findModifiedCoverageInputs(Project project) {
        SourceSetContainer sourceSets = project.extensions.findByType(SourceSetContainer)
        def mainSourceSet = sourceSets?.findByName('main')
        if (mainSourceSet == null) {
            return []
        }

        Set<File> expectedClassDirs = existingFiles(mainSourceSet.output.classesDirs.files)
        Set<File> expectedExecutionDataFiles = expectedJacocoExecutionDataFiles(project)

        List<String> violations = []
        ['jacocoTestReport', 'jacocoTestCoverageVerification'].each { String taskName ->
            def task = project.tasks.findByName(taskName)
            if (task == null || !task.enabled) {
                return
            }

            if (!expectedClassDirs.isEmpty()) {
                List<File> missingClassFiles = missingCoverageClassFiles(project, expectedClassDirs, task)
                if (!missingClassFiles.isEmpty()) {
                    violations.add(
                            "${taskName}.classDirectories narrowed coverage output, "
                                    + "missing ${limitedRelativeFiles(project, missingClassFiles)}"
                    )
                }
            }

            Set<File> actualExecutionDataFiles = executionDataFiles(task)
            List<File> missingExecutionData = missingExpectedFiles(expectedExecutionDataFiles, actualExecutionDataFiles)
            if (!missingExecutionData.isEmpty()) {
                violations.add("${taskName}.executionData missing ${limitedRelativeFiles(project, missingExecutionData)}")
            }
        }
        return violations.sort()
    }

    private static List<String> findModifiedBytecodeAnalysisInputs(Project project) {
        SourceSetContainer sourceSets = project.extensions.findByType(SourceSetContainer)
        def mainSourceSet = sourceSets?.findByName('main')
        def testSourceSet = sourceSets?.findByName('test')
        if (mainSourceSet == null) {
            return []
        }

        List<String> violations = []
        [
                spotbugsMain  : mainSourceSet,
                spotbugsDomain: mainSourceSet,
                spotbugsTest  : testSourceSet
        ].each { String taskName, def sourceSet ->
            if (sourceSet == null) {
                return
            }
            def task = project.tasks.findByName(taskName)
            if (task == null || !task.enabled) {
                return
            }

            Set<File> expectedClassDirs = existingFiles(sourceSet.output.classesDirs.files)
            if (!expectedClassDirs.isEmpty()) {
                List<File> missingClassDirs = missingExpectedFiles(expectedClassDirs, task.classDirs.files)
                if (!missingClassDirs.isEmpty()) {
                    violations.add("${taskName}.classDirs missing ${limitedRelativeFiles(project, missingClassDirs)}")
                }
            }

            List<File> missingSourceDirs = missingExpectedFiles(sourceSet.allSource.srcDirs, task.sourceDirs.files)
            if (!missingSourceDirs.isEmpty()) {
                violations.add("${taskName}.sourceDirs missing ${limitedRelativeFiles(project, missingSourceDirs)}")
            }
        }
        return violations.sort()
    }

    private static List<String> findModifiedQualityConfigurations(Project project) {
        File expectedCheckstyleConfig = lintConfigFile(project, 'checkstyle/checkstyle.xml')
        File expectedCheckstyleDomainConfig = lintConfigFile(project, 'checkstyle/checkstyle-domain.xml')
        File expectedPmdRuleset = lintConfigFile(project, 'pmd/ruleset.xml')
        File expectedPmdDomainRuleset = lintConfigFile(project, 'pmd/ruleset-domain.xml')
        File expectedSpotBugsExclude = lintConfigFile(project, 'spotbugs/exclude.xml')
        File expectedSpotBugsDomainInclude = lintConfigFile(project, 'spotbugs/include-domain.xml')

        List<String> violations = []

        project.tasks.withType(Checkstyle).each { Checkstyle task ->
            File expectedConfig = task.name == 'checkstyleDomain'
                    ? expectedCheckstyleDomainConfig
                    : expectedCheckstyleConfig
            if (!sameFile(task.configFile, expectedConfig)) {
                violations.add("${task.name}.configFile=${relativeFile(project, task.configFile)}")
            }
        }

        project.tasks.withType(Pmd).each { Pmd task ->
            File expectedRuleset = task.name == 'pmdDomain' ? expectedPmdDomainRuleset : expectedPmdRuleset
            if (task.ruleSets != null && !task.ruleSets.isEmpty()) {
                violations.add("${task.name}.ruleSets=${task.ruleSets}")
            }
            Set<File> actualRulesetFiles = task.ruleSetFiles == null
                    ? [] as Set<File>
                    : task.ruleSetFiles.files.findAll { File file -> file != null }.toSet()
            if (!sameFileSet(actualRulesetFiles, [expectedRuleset] as Set<File>)) {
                violations.add("${task.name}.ruleSetFiles=${relativeFiles(project, actualRulesetFiles)}")
            }
        }

        project.tasks.withType(SpotBugsTask).each { SpotBugsTask task ->
            if (task.name == 'spotbugsDomain') {
                File actualInclude = regularFilePropertyFile(task.includeFilter)
                if (!sameFile(actualInclude, expectedSpotBugsDomainInclude)) {
                    violations.add("${task.name}.includeFilter=${relativeFile(project, actualInclude)}")
                }
            } else {
                File actualExclude = regularFilePropertyFile(task.excludeFilter)
                if (!sameFile(actualExclude, expectedSpotBugsExclude)) {
                    violations.add("${task.name}.excludeFilter=${relativeFile(project, actualExclude)}")
                }
            }
        }

        return violations.sort()
    }

    private static File lintConfigFile(Project project, String relativePath) {
        project.layout.buildDirectory.file("lint-convention/${relativePath}").get().asFile
    }

    private static boolean sameFileSet(Set<File> actualFiles, Set<File> expectedFiles) {
        actualFiles.collect { File file -> canonicalPath(file) }.toSet() ==
                expectedFiles.collect { File file -> canonicalPath(file) }.toSet()
    }

    private static boolean sameFile(File actualFile, File expectedFile) {
        actualFile != null && expectedFile != null && canonicalPath(actualFile) == canonicalPath(expectedFile)
    }

    private static String canonicalPath(File file) {
        try {
            return file.canonicalPath
        } catch (IOException ignored) {
            return file.absolutePath
        }
    }

    private static String relativeFiles(Project project, Set<File> files) {
        if (files.isEmpty()) {
            return '<empty>'
        }
        files.collect { File file -> relativeFile(project, file) }.sort().join('|')
    }

    private static String relativeFile(Project project, File file) {
        if (file == null) {
            return '<empty>'
        }
        project.relativePath(file)
    }

    private static File regularFilePropertyFile(def regularFileProperty) {
        try {
            return regularFileProperty.asFile.orNull as File
        } catch (Exception ignored) {
            // Fall through for Gradle APIs that expose RegularFile instead of Provider<File>.
        }
        try {
            def regularFile = regularFileProperty.orNull
            return regularFile == null ? null : regularFile.asFile as File
        } catch (Exception ignored) {
            return null
        }
    }

    private static Set<File> expectedJacocoExecutionDataFiles(Project project) {
        Set<File> files = [] as Set<File>
        project.tasks.withType(Test).each { Test task ->
            JacocoTaskExtension jacoco = task.extensions.findByType(JacocoTaskExtension)
            if (jacoco != null && jacoco.destinationFile != null) {
                files.add(jacoco.destinationFile)
            }
        }
        return files
    }

    private static Set<File> executionDataFiles(def task) {
        try {
            return task.executionData.files.findAll { File file -> file != null }.toSet()
        } catch (Exception ignored) {
            return [] as Set<File>
        }
    }

    private static List<File> missingExpectedFiles(Collection<File> expectedFiles, Collection<File> actualFiles) {
        Set<String> actualPaths = actualFiles
                .findAll { File file -> file != null }
                .collect { File file -> canonicalPath(file) }
                .toSet()
        expectedFiles
                .findAll { File file -> file != null && !actualPaths.contains(canonicalPath(file)) }
                .sort { File left, File right -> canonicalPath(left) <=> canonicalPath(right) }
    }

    private static List<File> missingCoverageClassFiles(Project project, Collection<File> expectedClassDirs, def task) {
        Set<File> expectedClassFiles = classFilesInDirs(project, expectedClassDirs)
        if (expectedClassFiles.isEmpty()) {
            return []
        }
        Set<File> actualClassFiles = classFilesInFileCollection(task.classDirectories)
        missingExpectedFiles(expectedClassFiles, actualClassFiles)
    }

    private static Set<File> classFilesInDirs(Project project, Collection<File> dirs) {
        dirs.collectMany { File dir ->
            if (dir == null || !dir.exists()) {
                return []
            }
            project.fileTree(dir) {
                include '**/*.class'
            }.files
        }.toSet()
    }

    private static Set<File> classFilesInFileCollection(def fileCollection) {
        try {
            return fileCollection.asFileTree.files
                    .findAll { File file -> file != null && file.isFile() && file.name.endsWith('.class') }
                    .toSet()
        } catch (Exception ignored) {
            return [] as Set<File>
        }
    }

    private static Set<File> existingFiles(Collection<File> files) {
        files.findAll { File file -> file != null && file.exists() }.toSet()
    }

    private static String limitedRelativeFiles(Project project, Collection<File> files) {
        List<String> relativePaths = files
                .collect { File file -> relativeFile(project, file) }
                .sort()
        int limit = 5
        String visiblePaths = relativePaths.take(limit).join(', ')
        if (relativePaths.size() <= limit) {
            return visiblePaths
        }
        return "${visiblePaths}, ... +${relativePaths.size() - limit} more"
    }

    private static List<File> javaFiles(Project project, String relativePath) {
        File directory = project.file(relativePath)
        if (!directory.exists()) {
            return []
        }
        project.fileTree(directory) {
            include '**/*.java'
        }.files.findAll { File file -> file.isFile() }.toList()
    }

    private static boolean hasPathSegment(File file, String segment) {
        file.path.split(/[\\\/]/).contains(segment)
    }

    private static void configureJava21Convention(Project project) {
        project.pluginManager.withPlugin('java') {
            JavaPluginExtension javaExtension = project.extensions.findByType(JavaPluginExtension)
            if (javaExtension != null) {
                javaExtension.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                javaExtension.sourceCompatibility = JavaVersion.VERSION_21
                javaExtension.targetCompatibility = JavaVersion.VERSION_21
            }
            project.tasks.withType(JavaCompile).configureEach { JavaCompile task ->
                task.options.release.set(21)
            }
        }
    }

    private static void configureLombokConvention(Project project) {
        project.pluginManager.withPlugin('java') {
            [
                    'compileOnly',
                    'annotationProcessor',
                    'testCompileOnly',
                    'testAnnotationProcessor'
            ].each { String configurationName ->
                if (project.configurations.findByName(configurationName) != null) {
                    project.dependencies.add(configurationName, LOMBOK_DEPENDENCY)
                }
            }
        }
    }

    private static List<String> validateJavaVersion(Project project) {
        if (!project.plugins.hasPlugin('java')) {
            return []
        }

        List<String> violations = []
        JavaPluginExtension javaExtension = project.extensions.findByType(JavaPluginExtension)
        if (javaExtension != null) {
            JavaLanguageVersion toolchainVersion = javaExtension.toolchain.languageVersion.orNull
            if (toolchainVersion == null || toolchainVersion.asInt() < 21) {
                violations.add('java.toolchain.languageVersion must be Java 21 or higher')
            }
            if (javaExtension.sourceCompatibility != null
                    && javaExtension.sourceCompatibility < JavaVersion.VERSION_21) {
                violations.add("sourceCompatibility must be Java 21 or higher, not ${javaExtension.sourceCompatibility}")
            }
            if (javaExtension.targetCompatibility != null
                    && javaExtension.targetCompatibility < JavaVersion.VERSION_21) {
                violations.add("targetCompatibility must be Java 21 or higher, not ${javaExtension.targetCompatibility}")
            }
        }

        project.tasks.withType(JavaCompile).each { JavaCompile task ->
            Integer release = task.options.release.orNull
            if (release == null || release < 21) {
                violations.add("${task.path} options.release must be 21 or higher")
            }
        }
        return violations
    }

    private static String resolveChangedCoverageBaseRef(Project project, String configuredBaseRef) {
        if (configuredBaseRef != null && !configuredBaseRef.isBlank()) {
            return configuredBaseRef
        }

        ['origin/main', 'origin/master', 'main', 'master'].find { String candidate ->
            gitRefExists(project, candidate)
        }
    }

    private static boolean gitRefExists(Project project, String ref) {
        try {
            Process process = new ProcessBuilder([
                    'git',
                    '-C',
                    project.projectDir.absolutePath,
                    'rev-parse',
                    '--verify',
                    ref
            ])
                    .directory(project.projectDir)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            return process.waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    private static void materializeResource(String resourcePath, File outputFile) {
        outputFile.parentFile.mkdirs()
        InputStream resourceStream = LintConventionPlugin.class.getResourceAsStream(resourcePath)
        if (resourceStream == null) {
            throw new GradleException("Cannot find lint resource: ${resourcePath}")
        }
        resourceStream.withCloseable { input ->
            outputFile.withOutputStream { output ->
                output << input
            }
        }
    }

    private static List<String> layerClassPattern(String packageSegment) {
        return ["*.${packageSegment}.*"]
    }

    private static String inboundAdapterPackageSegment(HexagonalConventionExtension convention) {
        if (convention.presentationPackageSegment?.startsWith('adapter.in.')) {
            return 'adapter.in'
        }
        return convention.presentationPackageSegment
    }

    private static void configureCoverageRules(
            JacocoCoverageVerification task,
            HexagonalConventionExtension convention
    ) {
        task.violationRules { rules ->
            rules.rule {
                coverageLimit(delegate, 'LINE', convention.overallLineCoverageMinimum, 0.85)
                coverageLimit(delegate, 'BRANCH', convention.overallBranchCoverageMinimum, 0.80)
            }
            rules.rule {
                element = 'CLASS'
                includes = layerClassPattern(convention.domainPackageSegment)
                coverageLimit(delegate, 'LINE', convention.domainLineCoverageMinimum, 0.95)
                coverageLimit(delegate, 'BRANCH', convention.domainBranchCoverageMinimum, 0.90)
            }
            rules.rule {
                element = 'CLASS'
                includes = layerClassPattern(convention.applicationPackageSegment)
                coverageLimit(delegate, 'LINE', convention.applicationLineCoverageMinimum, 0.90)
                coverageLimit(delegate, 'BRANCH', convention.applicationBranchCoverageMinimum, 0.85)
            }
            rules.rule {
                element = 'CLASS'
                includes = layerClassPattern(convention.infrastructurePackageSegment)
                coverageLimit(delegate, 'LINE', convention.infrastructureLineCoverageMinimum, 0.80)
                coverageLimit(delegate, 'BRANCH', convention.infrastructureBranchCoverageMinimum, 0.70)
            }
            rules.rule {
                element = 'CLASS'
                includes = layerClassPattern(inboundAdapterPackageSegment(convention))
                coverageLimit(delegate, 'LINE', convention.inboundAdapterLineCoverageMinimum, 0.80)
                coverageLimit(delegate, 'BRANCH', convention.inboundAdapterBranchCoverageMinimum, 0.70)
            }
        }
    }

    private static void coverageLimit(
            Object rule,
            String counterName,
            BigDecimal configuredMinimum,
            BigDecimal baselineMinimum
    ) {
        rule.limit {
            counter = counterName
            value = 'COVEREDRATIO'
            minimum = coverageMinimum(configuredMinimum, baselineMinimum)
        }
    }

    private static BigDecimal coverageMinimum(BigDecimal configuredMinimum, BigDecimal baselineMinimum) {
        if (configuredMinimum == null) {
            return baselineMinimum
        }
        return configuredMinimum < baselineMinimum ? baselineMinimum : configuredMinimum
    }

    private static void configurePitMutationGate(Project project, HexagonalConventionExtension convention) {
        def pitestExtension = project.extensions.findByName('pitest')
        if (pitestExtension == null) {
            return
        }
        pitestExtension.targetClasses = [
                "*.${convention.domainPackageSegment}.*".toString(),
                "*.${convention.applicationPackageSegment}.*".toString()
        ]
        pitestExtension.targetTests = ['*Test', '*Tests']
        pitestExtension.mutationThreshold = minimumInt(convention.mutationScoreMinimum, 80)
        pitestExtension.testStrengthThreshold = minimumInt(convention.testStrengthMinimum, 85)
        pitestExtension.outputFormats = ['XML', 'HTML']
        pitestExtension.timestampedReports = false
    }

    private static int minimumInt(int configuredMinimum, int baselineMinimum) {
        return configuredMinimum < baselineMinimum ? baselineMinimum : configuredMinimum
    }
}
