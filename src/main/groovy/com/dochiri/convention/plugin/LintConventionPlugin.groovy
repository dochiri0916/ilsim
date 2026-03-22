package com.dochiri.convention.plugin

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.validator.DomainStaticFactoryValidator
import com.dochiri.convention.validator.EntityNamingConventionValidator
import com.dochiri.convention.validator.HexagonalArchitectureValidator
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.SourceSetContainer

class LintConventionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        HexagonalConventionExtension convention =
                project.extensions.create('hexagonalConvention', HexagonalConventionExtension)

        project.pluginManager.apply('checkstyle')
        project.pluginManager.apply('pmd')
        project.pluginManager.apply('com.github.spotbugs')

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
            task.reports { reports ->
                reports.xml.required = true
                reports.html.required = true
            }
        }

        project.extensions.configure(SpotBugsExtension) { SpotBugsExtension extension ->
            extension.toolVersion = '4.9.8'
            extension.effort = 'max'
            extension.reportLevel = 'low'
            extension.ignoreFailures = false
            extension.excludeFilter = spotbugsExclude.get().asFile
        }

        project.tasks.withType(SpotBugsTask).configureEach { SpotBugsTask task ->
            task.dependsOn(prepareLintConfig)
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

            project.tasks.named('check') { task ->
                task.dependsOn(checkstyleDomain)
                task.dependsOn(pmdDomain)
                task.dependsOn(spotbugsDomain)
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

        project.tasks.named('check') { task ->
            task.dependsOn(project.tasks.withType(SpotBugsTask))
            task.dependsOn(validateHexagonalArchitecture)
            task.dependsOn(validateEntityNamingConvention)
            task.dependsOn(validateDomainStaticFactoryConvention)
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
}
