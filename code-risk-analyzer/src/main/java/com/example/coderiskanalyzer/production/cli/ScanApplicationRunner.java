package com.example.coderiskanalyzer.production.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Bridges bootRun/jar arguments to the same scan command used by Spring Shell. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ScanApplicationRunner implements ApplicationRunner {

    private final ScanCommand scanCommand;
    private final CliExitCode exitCode;
    private final RulesCommand rulesCommand;

    public ScanApplicationRunner(ScanCommand scanCommand, CliExitCode exitCode, RulesCommand rulesCommand) {
        this.scanCommand = scanCommand;
        this.exitCode = exitCode;
        this.rulesCommand = rulesCommand;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] args = arguments.getSourceArgs();
        if (args.length == 0) {
            System.out.println("Usage: scan <project-path> [options] | baseline create <project-path> [--output file] | rules [rule-id] [--spec] [--format console|json]");
            exitCode.set(2);
            return;
        }
        if (args[0].equals("baseline")) {
            runBaseline(args);
            return;
        }
        if (args[0].equals("rules")) {
            runRules(args);
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

    private void runRules(String[] args) {
        boolean hasId = args.length > 1 && !args[1].startsWith("--");
        String error = validateOptions(args, hasId ? 2 : 1, java.util.Set.of("spec", "format"), java.util.Set.of("spec"));
        if (error != null) {
            System.out.println("Error: " + error);
            exitCode.set(2);
            return;
        }
        boolean spec = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--spec=")) spec = Boolean.parseBoolean(args[i].substring("--spec=".length()));
            if (args[i].equals("--spec")) spec = i + 1 >= args.length || args[i + 1].startsWith("--")
                    || Boolean.parseBoolean(args[i + 1]);
        }
        String output = rulesCommand.rules(hasId ? args[1] : "", spec, option(args, "format", "console"));
        System.out.println(output);
        if (output.startsWith("Error:")) exitCode.set(2);
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
