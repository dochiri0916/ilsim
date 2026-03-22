package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets

class DomainStaticFactoryValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        if (!convention.enforceDomainStaticFactoryMethod) {
            return []
        }

        List<String> violations = []
        Set<String> classExceptions = new HashSet<>(convention.domainStaticFactoryExceptions ?: [])

        SourceInspector.collectMainSourceFiles(project).findAll { file ->
            file.name.endsWith('.java')
        }.each { File file ->
            String content = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(content)
            if (!SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                return
            }

            if (isInterfaceOrEnumOrRecord(content)) {
                return
            }

            String className = SourceInspector.extractClassName(content)
            if (className == null || classExceptions.contains(className)) {
                return
            }

            if (hasPublicConstructor(content, className)) {
                violations.add("${project.relativePath(file)} class '${className}' must not expose public constructor (use static factory)")
            }

            if (!hasPrivateNoArgConstructor(content, className)) {
                violations.add("${project.relativePath(file)} class '${className}' must declare private no-arg constructor")
            }

            if (hasArgumentConstructor(content, className)) {
                violations.add("${project.relativePath(file)} class '${className}' must not declare argument constructors (use static factory only)")
            }

            if (!hasPublicStaticFactoryMethod(content, className)) {
                violations.add("${project.relativePath(file)} class '${className}' must declare at least one public static factory method returning '${className}'")
            }
        }

        return violations
    }

    private static boolean isInterfaceOrEnumOrRecord(String source) {
        return (source =~ /(?m)\b(interface|enum|record)\b/).find()
    }

    private static boolean hasPublicConstructor(String source, String className) {
        String pattern = "(?m)^\\s*public\\s+${className}\\s*\\("
        return (source =~ pattern).find()
    }

    private static boolean hasPrivateNoArgConstructor(String source, String className) {
        String constructorPattern = "(?m)^\\s*private\\s+${className}\\s*\\(\\s*\\)"
        if ((source =~ constructorPattern).find()) {
            return true
        }

        String lombokPattern = "(?s)@\\s*NoArgsConstructor\\s*\\((.*?)\\)"
        def lombokMatcher = source =~ lombokPattern
        while (lombokMatcher.find()) {
            String args = lombokMatcher.group(1)
            if (args == null) {
                continue
            }
            if ((args =~ /access\s*=\s*AccessLevel\.PRIVATE/).find()) {
                return true
            }
        }
        return false
    }

    private static boolean hasArgumentConstructor(String source, String className) {
        String pattern = "(?m)^\\s*(public|protected|private)\\s+${className}\\s*\\(([^)]*)\\)"
        def matcher = source =~ pattern
        while (matcher.find()) {
            String args = matcher.group(2)?.trim()
            if (args != null && !args.isEmpty()) {
                return true
            }
        }
        return false
    }

    private static boolean hasPublicStaticFactoryMethod(String source, String className) {
        String pattern = "(?m)^\\s*public\\s+static(?:\\s+final)?\\s+${className}\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\("
        return (source =~ pattern).find()
    }
}
