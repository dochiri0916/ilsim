package com.example.coderiskanalyzer.production.parser;

import java.nio.file.Path;
import java.util.List;

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
                // An unreadable directory under this root must not abort the scan; calls into it stay unresolved
                // and are handled conservatively. Source discovery reports the unreadable path as SOURCE-002.
            }
        }
        for (Path jar : classpath.stream().distinct().toList()) {
            try {
                solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver(jar));
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("Could not read classpath JAR: " + jar, exception);
            }
        }
        return solver;
    }
}
