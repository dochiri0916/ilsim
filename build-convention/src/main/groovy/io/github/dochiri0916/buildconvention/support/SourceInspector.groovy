package io.github.dochiri0916.buildconvention.support

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

class SourceInspector {
    static List<File> collectMainSourceFiles(Project project) {
        Set<File> files = new LinkedHashSet<>()
        sourceDirs(project, 'main', 'src/main/java', 'src/main/groovy').each { File dir ->
            files.addAll(project.fileTree(dir) {
                include '**/*.java'
                include '**/*.groovy'
            }.files)
        }
        return files.toList()
    }

    static List<File> collectTestJavaFiles(Project project) {
        Set<File> files = new LinkedHashSet<>()
        Set<File> dirs = new LinkedHashSet<>()
        dirs.addAll(sourceDirs(project, null, 'src/test/java'))
        dirs.each { File dir ->
            files.addAll(project.fileTree(dir) { include '**/*.java' }.files)
        }
        return files.toList()
    }

    /**
     * Source directories of the real source sets so custom srcDir entries cannot hide code from validation.
     * With a null sourceSetName, every source set except main is returned.
     */
    static Set<File> sourceDirs(Project project, String sourceSetName, String... defaults) {
        Set<File> dirs = new LinkedHashSet<>()
        defaults.each { String path -> dirs.add(project.file(path)) }
        SourceSetContainer sourceSets = project.extensions.findByType(SourceSetContainer)
        sourceSets?.each { SourceSet sourceSet ->
            boolean selected = sourceSetName == null ? sourceSet.name != 'main' : sourceSet.name == sourceSetName
            if (selected) {
                dirs.addAll(sourceSet.allJava.srcDirs)
                dirs.addAll(sourceSet.allSource.srcDirs.findAll { File dir -> dir.name == 'groovy' })
            }
        }
        return dirs.findAll { File dir -> dir.exists() }.toSet()
    }

    static String extractPackageName(String source) {
        def matcher = source =~ /(?m)^\s*package\s+([\w.]+)\s*;?\s*$/
        return matcher.find() ? matcher.group(1) : ''
    }

    static List<String> extractImports(String source) {
        List<String> imports = []
        def matcher = source =~ /(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;?\s*$/
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
