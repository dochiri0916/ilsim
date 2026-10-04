package com.example.coderiskanalyzer.production.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.example.coderiskanalyzer.mutation.MutationCommand;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigException;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;

/** Bridges bootRun/jar arguments to the same scan command used by Spring Shell. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ScanApplicationRunner implements ApplicationRunner {

    private final ScanCommand scanCommand;
    private final CliExitCode exitCode;
    private final RulesCommand rulesCommand;
    private final MutationCommand mutationCommand;
    private final AnalyzerConfigLoader configLoader;
    private final com.example.coderiskanalyzer.impact.ImpactCommand impactCommand;

    public ScanApplicationRunner(ScanCommand scanCommand, CliExitCode exitCode, RulesCommand rulesCommand,
                                 MutationCommand mutationCommand, AnalyzerConfigLoader configLoader,
                                 com.example.coderiskanalyzer.impact.ImpactCommand impactCommand) {
        this.impactCommand = impactCommand;
        this.scanCommand = scanCommand;
        this.exitCode = exitCode;
        this.rulesCommand = rulesCommand;
        this.mutationCommand = mutationCommand;
        this.configLoader = configLoader;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] args = arguments.getSourceArgs();
        if (args.length == 0) {
            System.out.println("Usage: requirements <input.json> [options] | prompt <input.json> [--rules file] | prompt --change <text> --files <a,b> | route --change <text> --files <a,b> | scan <project-path> [options] | mutation <project-path> [--files-file file] (executes target build/tests) | rules [rule-id] | validators <project-path> | impact <project-path> [--files-file f] [--depth n] [--compare before.json] [--output f]");
            exitCode.set(2);
            return;
        }
        if (args[0].equals("route")) {
            var response = new com.example.coderiskanalyzer.requirements.PromptCommand()
                    .route(args, com.example.coderiskanalyzer.requirements.ChangeRouter::jev);
            System.out.println(response.output());
            exitCode.set(response.exitCode());
            return;
        }
        if (args[0].equals("prompt")) {
            var response = new com.example.coderiskanalyzer.requirements.PromptCommand().execute(args);
            System.out.println(response.output());
            exitCode.set(response.exitCode());
            return;
        }
        if (args[0].equals("requirements")) {
            var response = new com.example.coderiskanalyzer.requirements.RequirementsCommand().execute(args);
            System.out.println(response.output());
            exitCode.set(response.exitCode());
            return;
        }
        if (args[0].equals("baseline")) {
            runBaseline(args);
            return;
        }
        if (args[0].equals("rules")) {
            if (args.length > 2) {
                System.out.println("Error: rules accepts at most one rule ID");
                exitCode.set(2);
                return;
            }
            String ruleId = args.length > 1 ? args[1] : "";
            String output = rulesCommand.rules(ruleId);
            System.out.println(output);
            if (output.startsWith("Error:")) exitCode.set(2);
            return;
        }
        if (args[0].equals("validators")) {
            runValidators(args);
            return;
        }
        if (args[0].equals("impact")) {
            runImpact(args);
            return;
        }
        if (args[0].equals("mutation")) {
            if (args.length < 2 || args[1].startsWith("--")) {
                System.out.println("Error: mutation requires a target project path. This runs the target Gradle build and tests.");
                exitCode.set(2);
                return;
            }
            String optionError = validateOptions(args, 2, java.util.Set.of("files-file"), java.util.Set.of());
            if (optionError != null) {
                System.out.println("Error: " + optionError);
                exitCode.set(2);
                return;
            }
            System.err.println("Dynamic mutation verification will execute the target Gradle build and tests.");
            MutationCommand.Response response = mutationCommand.verify(args[1], option(args, "files-file", ""));
            System.out.println(response.output());
            exitCode.set(response.exitCode());
            return;
        }
        if (!args[0].equals("scan")) {
            if (args[0].equals("help")) return;
            System.out.println("Error: unknown command: " + args[0]);
            exitCode.set(2);
            return;
        }
        if (args.length < 2 || args[1].startsWith("--")) {
            System.out.println("Error: scan requires a target project path");
            exitCode.set(2);
            return;
        }
        String optionError = validateOptions(args, 2,
                java.util.Set.of("format", "output", "jev", "fail-on", "baseline", "files-file", "verbose", "execution-evidence"),
                java.util.Set.of("jev", "verbose"));
        if (optionError != null) {
            System.out.println("Error: " + optionError);
            exitCode.set(2);
            return;
        }
        String format = option(args, "format", "");
        String output = option(args, "output", "");
        String jev = option(args, "jev", "false");
        var response = scanCommand.scanForCli(new ScanRequest(args[1], format, output, jev,
                option(args, "fail-on", ""), option(args, "baseline", ""),
                option(args, "files-file", ""), Boolean.parseBoolean(option(args, "verbose", "false")), option(args, "execution-evidence", "")));
        System.out.println(response.output());
        response.diagnostics().forEach(diagnostic -> System.err.println("%s %s: %s".formatted(
                diagnostic.severity(), diagnostic.code(), diagnostic.message())));
        exitCode.set(response.exitCode());
    }

    /** Transitive callers and tests of the changed files, and the declared-contract snapshot or its comparison. */
    private void runImpact(String[] args) {
        if (args.length < 2 || args[1].startsWith("--")) {
            System.out.println("Error: impact requires a target project path");
            exitCode.set(2);
            return;
        }
        String optionError = validateOptions(args, 2, java.util.Set.of("files-file", "depth", "compare", "output"), java.util.Set.of());
        if (optionError != null) {
            System.out.println("Error: " + optionError);
            exitCode.set(2);
            return;
        }
        int depth;
        try {
            depth = Integer.parseInt(option(args, "depth", String.valueOf(com.example.coderiskanalyzer.impact.ImpactCommand.DEFAULT_DEPTH)));
        } catch (NumberFormatException exception) {
            System.out.println("Error: --depth must be a number");
            exitCode.set(2);
            return;
        }
        var response = impactCommand.run(args[1], option(args, "files-file", ""), depth, option(args, "compare", ""),
                option(args, "output", ""));
        System.out.println(response.output());
        exitCode.set(response.exitCode());
    }

    /** Prints the validated validators setting of .code-risk.yml as JSON (null: not configured). */
    private void runValidators(String[] args) {
        if (args.length != 2 || args[1].startsWith("--")) {
            System.out.println("Error: validators requires exactly one target project path");
            exitCode.set(2);
            return;
        }
        java.nio.file.Path project = java.nio.file.Path.of(args[1]).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(project)) {
            System.out.println("Error: project path is not a directory");
            exitCode.set(2);
            return;
        }
        try {
            var validators = configLoader.load(project).validators();
            System.out.println("{\"architecture\":%s,\"convention\":%s}".formatted(validators.architecture(),
                    validators.convention()));
        } catch (AnalyzerConfigException exception) {
            System.out.println("Error: " + exception.getMessage());
            exitCode.set(2);
        }
    }

    private String option(String[] args, String name, String defaultValue) {
        String prefix = "--" + name + "=";
        for (int index = 0; index < args.length; index++) {
            if (args[index].startsWith(prefix)) return args[index].substring(prefix.length());
            if (args[index].equals("--" + name)) {
                if (name.equals("verbose") && index + 1 < args.length
                        && (args[index + 1].equalsIgnoreCase("true") || args[index + 1].equalsIgnoreCase("false"))) {
                    return args[index + 1];
                }
                if (name.equals("verbose")) return "true";
                if (index + 1 < args.length && !args[index + 1].startsWith("--")) return args[index + 1];
                if (name.equals("jev")) return "true";
            }
        }
        return defaultValue;
    }

    private void runBaseline(String[] args) {
        if (args.length < 3 || !args[1].equals("create")) {
            System.out.println("Usage: baseline create <project-path> [--output .code-risk-baseline.json]");
            exitCode.set(2);
            return;
        }
        String optionError = validateOptions(args, 3, java.util.Set.of("output"), java.util.Set.of());
        if (optionError != null) {
            System.out.println("Error: " + optionError);
            exitCode.set(2);
            return;
        }
        String output = option(args, "output", ".code-risk-baseline.json");
        try {
            scanCommand.createBaseline(args[2], output);
            System.out.println("Baseline written to " + output);
        } catch (Exception exception) {
            System.out.println("Error: baseline creation failed");
            exitCode.set(2);
        }
    }

    private String validateOptions(String[] args, int start, java.util.Set<String> allowed,
                                   java.util.Set<String> booleanOptions) {
        for (int index = start; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--")) return "unexpected argument: " + token;
            String body = token.substring(2);
            int equals = body.indexOf('=');
            String name = equals < 0 ? body : body.substring(0, equals);
            if (!allowed.contains(name)) return "unknown option: --" + name;
            if (equals >= 0) {
                String value = body.substring(equals + 1);
                if (value.isBlank()) return "--" + name + " requires a value";
                if (booleanOptions.contains(name) && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    return "--" + name + " must be true or false";
                }
                continue;
            }
            if (booleanOptions.contains(name)) {
                if (index + 1 < args.length && !args[index + 1].startsWith("--")
                        && (args[index + 1].equalsIgnoreCase("true") || args[index + 1].equalsIgnoreCase("false"))) index++;
                continue;
            }
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) return "--" + name + " requires a value";
            index++;
        }
        return null;
    }
}
