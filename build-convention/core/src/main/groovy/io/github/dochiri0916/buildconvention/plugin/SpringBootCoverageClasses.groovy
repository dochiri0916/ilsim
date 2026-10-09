package io.github.dochiri0916.buildconvention.plugin

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

class SpringBootCoverageClasses {
    private static final String BOOT_ANNOTATION = 'Lorg/springframework/boot/autoconfigure/SpringBootApplication;'

    static FileCollection filtered(Project project, SourceSet sourceSet) {
        return project.files(project.provider {
            sourceSet.output.classesDirs.collect { File directory ->
                Set<String> excluded = []
                if (directory.isDirectory()) {
                    project.fileTree(directory).matching { include '**/*.class' }.files.each { File file ->
                        if (isSpringBootApplication(file)) {
                            excluded.add(directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char))
                        }
                    }
                }
                project.fileTree(directory) { exclude excluded as String[] }
            }
        })
    }

    private static boolean isSpringBootApplication(File file) {
        boolean[] found = [false]
        file.withInputStream { input ->
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor == BOOT_ANNOTATION) {
                        found[0] = true
                    }
                    return null
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        }
        return found[0]
    }
}
