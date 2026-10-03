package com.example.coderiskanalyzer.production.source;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.util.AntPathMatcher;
import org.springframework.stereotype.Component;

@Component
public class SourceDiscoveryService {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", "build", "target", "out", "node_modules", "generated", "generated-sources");

    private final SourceRootDetector rootDetector;

    public SourceDiscoveryService(SourceRootDetector rootDetector) {
        this.rootDetector = rootDetector;
    }

    public List<SourceFile> discover(Path projectRoot) {
        return discover(projectRoot, false);
    }

    public List<SourceFile> discover(Path projectRoot, boolean includeTests) {
        return discover(projectRoot, includeTests, List.of(), List.of());
    }

    public List<SourceFile> discover(Path projectRoot, boolean includeTests,
                                     List<String> configuredRoots, List<String> excludes) {
        return discover(projectRoot, includeTests, configuredRoots, excludes, new ArrayList<>());
    }

    /** Like {@link #discover(Path, boolean, List, List)}, additionally reporting entries that could not be read. */
    public List<SourceFile> discover(Path projectRoot, boolean includeTests,
                                     List<String> configuredRoots, List<String> excludes, List<Path> unreadable) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        List<SourceFile> files = new ArrayList<>();
        List<Path> roots;
        if (configuredRoots.isEmpty()) {
            roots = rootDetector.detect(normalizedRoot, includeTests);
        } else {
            roots = configuredRoots.stream().map(value -> configuredRoot(normalizedRoot, value)).toList();
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) throw new SourceDiscoveryException("Configured source root is not a directory: " + root);
            walk(root, root, files, unreadable);
        }
        AntPathMatcher matcher = new AntPathMatcher("/");
        return files.stream()
                .distinct()
                .filter(file -> {
                    String relative = normalizedRoot.relativize(file.path()).toString().replace('\\', '/');
                    return excludes.stream().noneMatch(pattern -> matcher.match(pattern, relative));
                })
                .sorted(Comparator.comparing(file -> file.path().toString()))
                .toList();
    }

    private Path configuredRoot(Path projectRoot, String configured) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) throw new SourceDiscoveryException("Configured source roots must be relative to the project root");
        Path resolved = projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) throw new SourceDiscoveryException("Configured source root escapes the project root");
        return resolved;
    }

    private void walk(Path root, Path current, List<SourceFile> files, List<Path> unreadable) {
        // Inside a real Java source root, directory names are package names, not build output.
        boolean javaSourceRoot = root.endsWith(Path.of("src", "main", "java")) || root.endsWith(Path.of("src", "test", "java"));
        try {
            Files.walkFileTree(current, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!javaSourceRoot && !dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        files.add(new SourceFile(root, file.toAbsolutePath().normalize()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    unreadable.add(file.toAbsolutePath().normalize());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new SourceDiscoveryException("Could not discover Java sources under " + current, e);
        }
    }
}
