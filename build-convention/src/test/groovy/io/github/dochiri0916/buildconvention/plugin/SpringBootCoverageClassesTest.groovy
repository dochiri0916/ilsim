package io.github.dochiri0916.buildconvention.plugin

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.ToolProvider

class SpringBootCoverageClassesTest {
    @TempDir
    File tempDir

    @Test
    void filtersOnlyAnnotatedClass() {
        File sourceDirectory = new File(tempDir, 'sources')
        File classesDirectory = new File(tempDir, 'classes')
        sourceDirectory.mkdirs()
        classesDirectory.mkdirs()
        writeSource(sourceDirectory, 'org/springframework/boot/autoconfigure/SpringBootApplication.java', '''
                package org.springframework.boot.autoconfigure;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.CLASS)
                public @interface SpringBootApplication {}
                ''')
        writeSource(sourceDirectory, 'example/SampleApplication.java', '''
                package example;
                public class SampleApplication { public int value() { return 1; } }
                ''')
        writeSource(sourceDirectory, 'example/BootApplication.java', '''
                package example;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class BootApplication { public static void main(String[] args) {} }
                ''')
        writeSource(sourceDirectory, 'example/application/service/PaymentService.java', '''
                package example.application.service;
                public class PaymentService { public int value() { return 2; } }
                ''')
        def javaFiles = []
        sourceDirectory.eachFileRecurse { File file -> if (file.name.endsWith('.java')) javaFiles.add(file.absolutePath) }
        assert ToolProvider.systemJavaCompiler.run(null, null, null,
                *(['-d', classesDirectory.absolutePath] + javaFiles)) == 0

        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        project.pluginManager.apply('java')
        def main = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer).getByName('main')
        main.output.classesDirs.setFrom(classesDirectory)
        Set<String> names = SpringBootCoverageClasses.filtered(project, main).files*.name as Set
        assert !names.contains('BootApplication.class')
        assert names.contains('SampleApplication.class')
        assert names.contains('PaymentService.class')
    }

    private static void writeSource(File root, String path, String content) {
        File file = new File(root, path)
        file.parentFile.mkdirs()
        file.text = content.stripIndent().trim()
    }
}
