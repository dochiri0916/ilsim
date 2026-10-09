package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.support.SourceTypeIndex
import org.gradle.api.Project

import java.util.regex.Pattern

/** Common contracts apply to ordinary interfaces and global configuration as well as ports. */
final class DeclarationConventionValidator {
    static List<String> validate(Project project, SourceTypeIndex index) {
        List<String> violations = []
        index.entries.values().each { entry ->
            def type = entry.type
            String path = project.relativePath(entry.inspection.file)
            if (type.annotations.any { index.resolve(it.qualifiedName, entry) ==
                    'org.springframework.boot.context.properties.ConfigurationProperties' } && type.kind != 'RECORD') {
                violations.add("${path} code convention @ConfigurationProperties type '${type.simpleName}' must be a record")
            }
            if (type.kind != 'INTERFACE' || type.modifiers.contains('SEALED')) return
            Map methods = [:]
            boolean complete = collectMethods(entry, index, [:], methods, [] as Set)
            if (complete && methods.values().count { it } == 1
                    && !type.annotations.any { index.resolve(it.qualifiedName, entry) == 'java.lang.FunctionalInterface' }) {
                violations.add("${path} code convention interface '${type.simpleName}' with one inherited or declared abstract method must declare @FunctionalInterface")
            }
        }
        return violations
    }

    private static boolean collectMethods(Map entry, SourceTypeIndex index, Map bindings, Map methods, Set visited) {
        String visit = entry.name + bindings.toString()
        if (!visited.add(visit)) return true
        boolean complete = true
        entry.type.implementedTypes.each { syntax ->
            String parentName = index.resolve(syntax, entry)
            Map parent = index.entries[parentName]
            if (parent) {
                String arguments = syntax.contains('<') ? syntax.substring(syntax.indexOf('<') + 1, syntax.lastIndexOf('>')) : ''
                List<String> values = typeArguments(arguments).collect { qualify(substitute(it, bindings), index, entry) }
                Map inherited = [:]
                parent.type.typeParameters.eachWithIndex { parameter, int position ->
                    inherited[parameter] = position < values.size() && values[position] ? values[position] : 'java.lang.Object'
                }
                complete &= collectMethods(parent, index, inherited, methods, visited)
            } else {
                // JDK interfaces are safe to inspect; never load project/dependency classes.
                try {
                    if (!parentName.startsWith('java.')) {
                        complete = false
                        return
                    }
                    Class parentClass = DeclarationConventionValidator.classLoader.loadClass(parentName)
                    List<String> values = syntax.contains('<') ? typeArguments(syntax.substring(syntax.indexOf('<') + 1, syntax.lastIndexOf('>'))) : []
                    Map externalBindings = [:]
                    parentClass.typeParameters.eachWithIndex { parameter, int position ->
                        externalBindings[parameter.name] = position < values.size() ? qualify(substitute(values[position], bindings), index, entry) : 'java.lang.Object'
                    }
                    parentClass.methods.findAll { method -> !java.lang.reflect.Modifier.isStatic(method.modifiers) }.each { method ->
                        String signature = method.name + '(' + method.genericParameterTypes.collect {
                            index.resolve(substitute(it.typeName, externalBindings), entry)
                        }.join(',') + ')'
                        if (!objectMethod(signature)) methods[signature] = java.lang.reflect.Modifier.isAbstract(method.modifiers)
                    }
                } catch (ClassNotFoundException ignored) {
                    complete = false
                }
            }
        }
        entry.type.methods.findAll { !it.constructor && !it.modifiers.contains('STATIC') && !it.modifiers.contains('PRIVATE') }.each { method ->
            String signature = method.name + '(' + method.parameterTypes.collect {
                index.resolve(substitute(it, bindings), entry) + (it.endsWith('[]') ? '[]' : '')
            }.join(',') + ')'
            if (!objectMethod(signature)) methods[signature] = !method.hasBody
        }
        return complete
    }

    private static String substitute(String syntax, Map bindings) {
        String result = syntax
        bindings.each { parameter, value -> result = result.replaceAll(/\b${Pattern.quote(parameter)}\b/, value) }
        return result
    }

    private static String qualify(String syntax, SourceTypeIndex index, Map entry) {
        syntax.replaceAll(/(?<![\w.])[A-Z][\w]*/) { token -> index.resolve(token, entry) }
    }

    private static List<String> typeArguments(String syntax) {
        List<String> arguments = []
        int depth = 0
        int start = 0
        for (int position = 0; position < syntax.length(); position++) {
            char symbol = syntax.charAt(position)
            if (symbol == '<' as char) depth++
            if (symbol == '>' as char) depth--
            if (symbol == ',' as char && depth == 0) {
                arguments.add(syntax.substring(start, position).trim())
                start = position + 1
            }
        }
        if (start < syntax.length()) arguments.add(syntax.substring(start).trim())
        return arguments
    }

    private static boolean objectMethod(String signature) {
        signature in ['equals(java.lang.Object)', 'hashCode()', 'toString()']
    }
}
