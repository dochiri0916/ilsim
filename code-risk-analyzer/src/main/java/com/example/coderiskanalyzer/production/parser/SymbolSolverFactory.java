package com.example.coderiskanalyzer.production.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

public class SymbolSolverFactory {

    public CombinedTypeSolver create(List<Path> sourceRoots) {
        return create(sourceRoots, List.of());
    }

    public CombinedTypeSolver create(List<Path> sourceRoots, List<Path> classpath) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path root : sourceRoots) {
            try {
                solver.add(new JavaParserTypeSolver(root));
            } catch (java.io.UncheckedIOException unreadable) {
            }
        }
        for (Path entry : classpath.stream().distinct().toList()) {
            try {
                Path jar = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? packClasses(entry) : entry;
                solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver(jar));
            } catch (java.io.IOException | java.io.UncheckedIOException exception) {
                throw new IllegalArgumentException("Could not read classpath entry: " + entry, exception);
            }
        }
        return solver;
    }

    private Path packClasses(Path directory) throws IOException {
        Path jar = Files.createTempFile("code-risk-classes-", ".jar");
        jar.toFile().deleteOnExit();
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                output.putNextEntry(new JarEntry(directory.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
