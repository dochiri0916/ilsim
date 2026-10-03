package com.example.coderiskanalyzer.production.source;

import java.nio.file.Path;

public record SourceFile(Path root, Path path) {

    public Path relativePath() {
        return root.relativize(path);
    }
}
