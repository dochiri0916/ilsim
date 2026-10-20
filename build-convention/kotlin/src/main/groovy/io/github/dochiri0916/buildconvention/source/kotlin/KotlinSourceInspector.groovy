package io.github.dochiri0916.buildconvention.source.kotlin

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

final class KotlinSourceInspector {
    private KotlinSourceInspector() {
    }

    static Set<File> sourceDirs(Project project) {
        Set<File> roots = [project.file('src/main/kotlin')] as Set
        SourceSetContainer sources = project.extensions.findByType(SourceSetContainer)
        roots.addAll(sources?.findByName('main')?.allSource?.srcDirs ?: [])
        def kotlin = project.extensions.findByName('kotlin')
        if (kotlin != null && kotlin.hasProperty('sourceSets')) {
            roots.addAll(kotlin.sourceSets.findByName('main')?.kotlin?.srcDirs ?: [])
        }
        return roots
    }

    static List<File> collect(Project project, Collection<File> additionalRoots = []) {
        Set<File> files = [] as Set
        (sourceDirs(project) + additionalRoots).each { File root ->
            files.addAll(project.fileTree(root) { include '**/*.kt' }.files)
        }
        return files.toList().sort { file -> file.absolutePath }
    }
}
