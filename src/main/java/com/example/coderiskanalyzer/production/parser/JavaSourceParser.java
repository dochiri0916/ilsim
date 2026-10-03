package com.example.coderiskanalyzer.production.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.source.SourceFile;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.JavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import org.springframework.stereotype.Component;

@Component
public class JavaSourceParser {

    public List<ParsedSource> parse(List<SourceFile> files) {
        return parse(files, files.stream().map(SourceFile::root).distinct().toList());
    }

    /** Parse the requested files while keeping all discovered source roots available to symbol resolution. */
    public List<ParsedSource> parse(List<SourceFile> files, List<Path> sourceRoots) {
        return parse(files, sourceRoots, List.of());
    }

    public List<ParsedSource> parse(List<SourceFile> files, List<Path> sourceRoots, List<Path> classpath) {
        List<Path> roots = sourceRoots.stream().distinct().toList();
        CombinedTypeSolver typeSolver = new SymbolSolverFactory().create(roots, classpath);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE)
                .setAttributeComments(true)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(configuration);

        return files.stream().map(file -> parseOne(parser, file)).toList();
    }

    private ParsedSource parseOne(JavaParser parser, SourceFile sourceFile) {
        try {
            ParseResult<com.github.javaparser.ast.CompilationUnit> result = parser.parse(sourceFile.path());
            List<String> errors = result.getProblems().stream().map(Object::toString).toList();
            return new ParsedSource(sourceFile, result.getResult().orElse(null), errors);
        } catch (IOException | RuntimeException exception) {
            return new ParsedSource(sourceFile, null, List.of(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
        }
    }
}
