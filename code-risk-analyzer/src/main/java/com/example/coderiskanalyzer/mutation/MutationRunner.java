package com.example.coderiskanalyzer.mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs PIT through Gradle only when explicitly called by the mutation command. */
@Component
public class MutationRunner {
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String INIT_SCRIPT = """
            initscript {
                repositories { gradlePluginPortal(); mavenCentral() }
                dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0' }
            }
            allprojects { p ->
                p.plugins.withId('java') {
                    def targetProperties = new Properties()
                    new File(System.getProperty('codeRisk.targetMap')).withInputStream { targetProperties.load(it) }
                    def targetCount = targetProperties.getProperty('count', '0').toInteger()
                    def selectedProjects = (0..<targetCount).collect { targetProperties.getProperty("project.${it}") }
                    if (selectedProjects.isEmpty() || selectedProjects.contains(p.path)) p.afterEvaluate {
                        def pitWasConfigured = p.plugins.hasPlugin('info.solidsoft.pitest')
                        if (!pitWasConfigured) p.pluginManager.apply(info.solidsoft.gradle.pitest.PitestPlugin)
                        p.extensions.configure('pitest') { pit ->
                            pit.outputFormats.add('XML')
                            if (!pitWasConfigured) pit.pitestVersion = '1.30.0'
                            pit.reportDir = new File(System.getProperty('codeRisk.reportDir'))
                            pit.timestampedReports = false
                            if (!pitWasConfigured) pit.enableDefaultIncrementalAnalysis = false
                            def tests = p.configurations.findByName('testRuntimeClasspath')
                            if (tests != null && tests.resolvedConfiguration.resolvedArtifacts.any {
                                it.moduleVersion.id.group == 'org.junit.jupiter' || it.moduleVersion.id.group == 'org.junit.platform'
                            } && !pit.junit5PluginVersion.present) pit.junit5PluginVersion = '1.2.3'
                            def javaExtension = p.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension)
                            if (javaExtension != null && javaExtension.toolchain.languageVersion.present && !pit.jvmPath.present) {
                                def targetLanguageVersion = javaExtension.toolchain.languageVersion.get()
                                pit.jvmPath = p.javaToolchains.launcherFor { languageVersion = targetLanguageVersion }
                                        .get().executablePath.asFile
                            }
                            def index = selectedProjects.indexOf(p.path)
                            def classes = index < 0 ? '' : targetProperties.getProperty("classes.${index}", '').trim()
                            if (!classes.isEmpty()) pit.targetClasses = classes.split(',') as List
                            if (pit.targetTests.getOrElse([]).isEmpty()) pit.targetTests = ['*']
                        }
                    }
                }
            }
            gradle.projectsEvaluated {
                def targetProperties = new Properties()
                new File(System.getProperty('codeRisk.targetMap')).withInputStream { targetProperties.load(it) }
                def targetCount = targetProperties.getProperty('count', '0').toInteger()
                def selectedProjects = (0..<targetCount).collect { targetProperties.getProperty("project.${it}") }
                def pitestTasks = gradle.rootProject.allprojects.findAll { project ->
                    (selectedProjects.isEmpty() || selectedProjects.contains(project.path))
                            && project.plugins.hasPlugin('info.solidsoft.pitest')
                }.collect { project -> project.tasks.findByName('pitest') }.findAll { it != null }
                gradle.rootProject.tasks.register('codeRiskMutation') { dependsOn(pitestTasks) }
            }
            """;

    private final MutationReportParser reportParser;
    private final GradleProcessExecutor processExecutor;

    @Autowired
    public MutationRunner(MutationReportParser reportParser, GradleProcessExecutor processExecutor) {
        this.reportParser = reportParser;
        this.processExecutor = processExecutor;
    }

    public MutationRunner(MutationReportParser reportParser) {
        this(reportParser, new SystemGradleProcessExecutor());
    }

    public MutationExecutionResult run(Path rawProject, List<String> targetClasses) {
        return run(rawProject, targetClasses == null || targetClasses.isEmpty() ? java.util.Map.of()
                : java.util.Map.of(":", targetClasses));
    }

    public MutationExecutionResult run(Path rawProject, java.util.Map<String, List<String>> projectTargets) {
        return run(rawProject, new MutationExecutionRequest(projectTargets));
    }

    public MutationExecutionResult run(Path rawProject, MutationExecutionRequest request) {
        java.util.Map<String, List<String>> projectTargets = request.projectTargets();
        Path project = rawProject.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) return failed(MutationExecutionResult.Outcome.BUILD_FAILED, "Target project directory does not exist.", -1);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = wrapper(project, windows);
        if (wrapper == null && !hasGradleBuild(project)) return failed(
                MutationExecutionResult.Outcome.UNSUPPORTED_BUILD_SYSTEM, "No Gradle wrapper or Gradle build files were found.", -1);
        if (wrapper == null && !hasSystemGradle()) return failed(MutationExecutionResult.Outcome.UNSUPPORTED_BUILD_SYSTEM,
                "A Gradle build was found, but neither a wrapper nor an installed Gradle is available.", -1);
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("code-risk-pit-");
            Path reportDir = Files.createDirectory(temporary.resolve("reports"));
            Path initScript = temporary.resolve("pit.init.gradle");
            Files.writeString(initScript, INIT_SCRIPT);
            Path targetMap = temporary.resolve("targets.properties");
            java.util.Properties targetProperties = new java.util.Properties();
            targetProperties.setProperty("count", Integer.toString(projectTargets.size()));
            int targetIndex = 0;
            for (var entry : projectTargets.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                targetProperties.setProperty("project." + targetIndex, entry.getKey());
                targetProperties.setProperty("classes." + targetIndex, String.join(",", entry.getValue()));
                targetIndex++;
            }
            try (var output = Files.newOutputStream(targetMap)) { targetProperties.store(output, "temporary mutation scope"); }
            Path stdout = temporary.resolve("stdout.log");
            Path stderr = temporary.resolve("stderr.log");
            List<String> command = new ArrayList<>();
            if (windows && wrapper != null && isWindowsBatch(wrapper)) {
                Path wrapperJar = project.resolve("gradle/wrapper/gradle-wrapper.jar");
                if (!Files.isRegularFile(wrapperJar)) return failed(MutationExecutionResult.Outcome.BUILD_FAILED,
                        "The Windows Gradle wrapper JAR is missing.", -1);
                command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                command.add("-classpath"); command.add(wrapperJar.toAbsolutePath().toString());
                command.add("org.gradle.wrapper.GradleWrapperMain");
            } else command.add(wrapper == null ? "gradle" : wrapper.toAbsolutePath().toString());
            command.add("--no-daemon");
            command.add("--init-script"); command.add(initScript.toString());
            command.add("-DcodeRisk.reportDir=" + reportDir);
            command.add("-DcodeRisk.targetMap=" + targetMap);
            command.add("codeRiskMutation");
            GradleProcessExecutor.Result process = processExecutor.execute(command, project, TIMEOUT, stdout, stderr);
            if (process.timedOut()) {
                return failed(MutationExecutionResult.Outcome.TIMED_OUT, "Gradle/PIT exceeded the 30 minute timeout.", -1);
            }
            int exit = process.exitCode();
            Path xml = findXmlReport(reportDir);
            if (exit != 0) {
                String logs = tail(stderr, stdout);
                String lower = logs.toLowerCase(java.util.Locale.ROOT);
                MutationExecutionResult.Outcome outcome = lower.contains("needs a green suite") || lower.contains("green suite")
                        ? MutationExecutionResult.Outcome.TESTS_FAILED_BEFORE_MUTATION
                        : lower.contains("compilejava") || lower.contains("compilation failed") || lower.contains("compile error")
                        ? MutationExecutionResult.Outcome.BUILD_FAILED : MutationExecutionResult.Outcome.MUTATION_EXECUTION_FAILED;
                String diagnostic = switch (outcome) {
                    case TESTS_FAILED_BEFORE_MUTATION -> "The target test suite failed before PIT could measure mutations.";
                    case BUILD_FAILED -> "The target Gradle build failed before PIT could measure mutations.";
                    default -> "The target Gradle/PIT invocation failed.";
                };
                return failed(outcome, diagnostic + " Process exit code: " + exit, exit);
            }
            if (xml == null) return failed(MutationExecutionResult.Outcome.MUTATION_EXECUTION_FAILED,
                    "PIT completed without an XML mutation report.", exit);
            return new MutationExecutionResult(MutationExecutionResult.Outcome.MUTATION_COMPLETED,
                    reportParser.parse(xml), "", exit, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(MutationExecutionResult.Outcome.MUTATION_EXECUTION_FAILED, "Mutation execution was interrupted.", -1);
        } catch (IOException | RuntimeException exception) {
            return failed(MutationExecutionResult.Outcome.MUTATION_EXECUTION_FAILED,
                    "Could not run or parse PIT report: " + exception.getClass().getSimpleName(), -1);
        } finally {
            if (temporary != null) deleteTree(temporary);
        }
    }

    private Path wrapper(Path project, boolean windows) {
        Path unix = project.resolve("gradlew");
        Path batch = project.resolve("gradlew.bat");
        if (windows && Files.isRegularFile(batch)) return batch;
        if (!windows && Files.isRegularFile(unix)) return unix;
        return null;
    }

    private boolean isWindowsBatch(Path path) { return path.getFileName().toString().endsWith(".bat"); }

    private boolean hasGradleBuild(Path project) {
        return List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .stream().anyMatch(name -> Files.isRegularFile(project.resolve(name)));
    }

    private boolean hasSystemGradle() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory).resolve("gradle"))) return true;
        }
        return false;
    }

    private Path findXmlReport(Path reportDir) throws IOException {
        try (var paths = Files.walk(reportDir)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().equals("mutations.xml"))
                    .findFirst().orElse(null);
        }
    }

    private String tail(Path... logs) {
        StringBuilder result = new StringBuilder();
        for (Path log : logs) try {
            String content = Files.readString(log);
            if (content.length() > 2000) content = content.substring(content.length() - 2000);
            result.append(content).append('\n');
        } catch (IOException ignored) { }
        return result.toString().trim();
    }

    private MutationExecutionResult failed(MutationExecutionResult.Outcome outcome, String diagnostic, int exit) {
        return new MutationExecutionResult(outcome, List.of(), diagnostic, exit, null);
    }

    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
