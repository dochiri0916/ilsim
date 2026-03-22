package com.dochiri.convention.support

import org.gradle.api.Project

class SourceInspector {
    static List<File> collectMainSourceFiles(Project project) {
        List<File> files = []
        File javaDir = project.file('src/main/java')
        if (javaDir.exists()) {
            files.addAll(project.fileTree(javaDir) {
                include '**/*.java'
            }.files)
        }

        File groovyDir = project.file('src/main/groovy')
        if (groovyDir.exists()) {
            files.addAll(project.fileTree(groovyDir) {
                include '**/*.groovy'
            }.files)
        }
        return files
    }

    static String extractPackageName(String source) {
        def matcher = source =~ /(?m)^\s*package\s+([\w.]+)\s*;/
        return matcher.find() ? matcher.group(1) : ''
    }

    static List<String> extractImports(String source) {
        List<String> imports = []
        def matcher = source =~ /(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/
        while (matcher.find()) {
            imports.add(matcher.group(1))
        }
        return imports
    }

    static boolean isInLayer(String value, String segment) {
        if (value == null || value.isBlank() || segment == null || segment.isBlank()) {
            return false
        }
        return value == segment || value.endsWith(".${segment}") || value.contains(".${segment}.")
    }

    static boolean isEntityClass(String source) {
        return (source =~ /(?m)@\s*(?:jakarta\.persistence\.|javax\.persistence\.)?Entity\b/).find()
    }

    static String extractClassName(String source) {
        def matcher = source =~ /(?m)\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\b/
        return matcher.find() ? matcher.group(1) : null
    }

    static String extractTableName(String source) {
        def tableMatcher = source =~ /(?s)@\s*(?:jakarta\.persistence\.|javax\.persistence\.)?Table\s*\((.*?)\)/
        if (!tableMatcher.find()) {
            return null
        }
        String tableArgs = tableMatcher.group(1)
        def nameMatcher = tableArgs =~ /name\s*=\s*['"]([A-Za-z0-9_]+)['"]/
        return nameMatcher.find() ? nameMatcher.group(1) : null
    }
}
