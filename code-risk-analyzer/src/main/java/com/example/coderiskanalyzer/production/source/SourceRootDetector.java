package com.example.coderiskanalyzer.production.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class SourceRootDetector {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", "build", "target", "out", "node_modules", "generated", "generated-sources");

    public List<Path> detect(Path projectRoot, boolean includeTests) {
        List<Path> roots = new ArrayList<>();
        addIfDirectory(roots, projectRoot.resolve("src/main/java"));
        if (includeTests) {
            addIfDirectory(roots, projectRoot.resolve("src/test/java"));
        }

        try {
            Files.walkFileTree(projectRoot, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 5,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(Path path,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (!path.equals(projectRoot) && !containsExcludedDirectory(projectRoot.relativize(path))
                                    && isModuleSourceRoot(projectRoot, path, includeTests)) addIfDirectory(roots, path);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path path, java.io.IOException failure) {
                            // Unreadable entries are reported by SourceDiscoveryService when it walks the source roots.
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (java.io.IOException e) {
            throw new SourceDiscoveryException("Could not inspect project source roots", e);
        }

        return roots.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private boolean containsExcludedDirectory(Path relative) {
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isModuleSourceRoot(Path projectRoot, Path path, boolean includeTests) {
        Path relative = projectRoot.relativize(path);
        int depth = relative.getNameCount();
        if (depth < 4 || depth > 5) {
            return false;
        }
        String sourceSet = includeTests && path.endsWith(Path.of("src", "test", "java"))
                ? "test"
                : "main";
        return path.endsWith(Path.of("src", sourceSet, "java"));
    }

    private void addIfDirectory(List<Path> roots, Path path) {
        if (Files.isDirectory(path)) {
            roots.add(path.toAbsolutePath().normalize());
        }
    }
}
