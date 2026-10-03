package com.example.coderiskanalyzer.requirements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Usage: prompt &lt;input.json&gt; [--rules file] | prompt --change text --files a,b [--rules file].
 * Reads and validates input only; runs no tools.
 */
public final class PromptCommand {
    private static final String USAGE = "Usage: prompt <input.json> [--rules file] | prompt --change <text> --files <a,b> [--rules file]";
    private static final int MAX_CHANGE_LENGTH = 1000;
    private final RequirementsInputValidator validator = new RequirementsInputValidator();

    public RequirementsCommand.Response execute(String[] args) {
        try {
            if (args.length < 2) throw new IllegalArgumentException(USAGE);
            boolean light = args[1].startsWith("--");
            Map<String, String> options = options(args, light ? 1 : 2, light ? Set.of("change", "files", "rules") : Set.of("rules"));
            String rules = options.get("rules");
            if (light) {
                if (!options.containsKey("change") || !options.containsKey("files")) throw new IllegalArgumentException(USAGE);
                return ok(new AgentPromptGenerator().generateChange(options.get("change"), files(options.get("files")), rules));
            }
            var input = validator.validate(Files.readString(Path.of(args[1])));
            return ok(new AgentPromptGenerator().generate(input, rules));
        } catch (IllegalArgumentException exception) {
            return new RequirementsCommand.Response("Error: " + exception.getMessage(), 2);
        } catch (java.io.IOException exception) {
            return new RequirementsCommand.Response("Error: could not read prompt input", 2);
        }
    }

    /** Usage: route --change text --files a,b. One JEV call; prints the route and the next command. */
    public RequirementsCommand.Response route(String[] args, java.util.function.Function<String, ChangeRouter.Classifier> classifierFor) {
        try {
            Map<String, String> options = options(args, 1, Set.of("change", "files"));
            if (args.length < 2 || options.size() != 2) throw new IllegalArgumentException("Usage: route --change <text> --files <a,b>");
            List<String> files = files(options.get("files"));
            String key = System.getenv("TYPESAFE_API_KEY");
            var decision = new ChangeRouter().route(options.get("change"), files,
                    key == null || key.isBlank() ? null : classifierFor.apply(key));
            String next = decision.route() == ChangeRouter.Route.LIGHT
                    ? "prompt --change <text> --files <a,b>"
                    : "write a requirements input (CODE_CHANGE) and run: prompt <input.json>";
            return ok("route: " + decision.route() + "\nprobability: " + (decision.probability() == null ? "n/a" : decision.probability())
                    + "\nreason: " + decision.reason() + "\nnext: " + next);
        } catch (IllegalArgumentException exception) {
            return new RequirementsCommand.Response("Error: " + exception.getMessage(), 2);
        }
    }

    private static RequirementsCommand.Response ok(String prompt) {
        return new RequirementsCommand.Response(prompt, 0);
    }

    private static Map<String, String> options(String[] args, int from, Set<String> allowed) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = from; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);
            if (args[i + 1].isBlank() || result.putIfAbsent(args[i].substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException("Empty or repeated prompt option");
        }
        if (result.containsKey("change") && result.get("change").length() > MAX_CHANGE_LENGTH)
            throw new IllegalArgumentException("--change must be at most " + MAX_CHANGE_LENGTH + " characters");
        return result;
    }

    private static List<String> files(String value) {
        List<String> files = new ArrayList<>();
        for (String file : value.split(",", -1)) {
            String trimmed = file.trim();
            Path path = Path.of(trimmed.isEmpty() ? "?" : trimmed);
            if (trimmed.isEmpty() || path.isAbsolute() || trimmed.contains("..") || files.contains(trimmed))
                throw new IllegalArgumentException("--files requires distinct relative paths without '..'");
            files.add(trimmed);
        }
        return files;
    }
}
