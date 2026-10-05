package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector

/** Web contracts are inspected as declarations, never annotation-looking comments. */
final class WebAdapterConventionValidator {
    static List<String> validate(String path, String packageName,
                                 JavaSourceAstInspector.TypeModel type, Set<String> imports) {
        List<String> violations = []
        if (!packageName.contains('.adapter.in.web')) return violations
        boolean request = type.simpleName.endsWith('Request')
        boolean response = type.simpleName.endsWith('Response')
        if (request || response) {
            violations.addAll(ImmutableCollectionConventionValidator.validate(path, type, true))
        }
        if (request) {
            ImmutableCollectionConventionValidator.stateFields(type).each { field ->
                def constraints = field.annotations.findAll { annotation ->
                    resolved(annotation, imports).startsWith('jakarta.validation.constraints.')
                }
                String simpleType = field.type.replaceFirst(/^java\.lang\./, '')
                Set<String> names = constraints.collect { it.simpleName }.toSet()
                boolean valid = !constraints.isEmpty()
                if (simpleType == 'String') valid = names.contains('NotBlank')
                if (ImmutableCollectionConventionValidator.isCollection(field.type)) valid = names.contains('NotEmpty')
                if (simpleType in ['byte', 'short', 'int', 'long', 'float', 'double',
                                   'Byte', 'Short', 'Integer', 'Long', 'Float', 'Double',
                                   'BigDecimal', 'BigInteger', 'java.math.BigDecimal', 'java.math.BigInteger']) {
                    valid = names.any { it in ['Min', 'Max', 'DecimalMin', 'DecimalMax', 'Positive', 'PositiveOrZero', 'Negative', 'NegativeOrZero'] }
                }
                if (!valid) violations.add("${path} web adapter Request field '${field.name}' must declare Jakarta input constraints (NotBlank/NotEmpty/numeric range)")
                if (constraints.any { !(it.arguments.get('message') =~ /[가-힣]/).find() }) {
                    violations.add("${path} web adapter Request field '${field.name}' constraints must explicitly explain the reason in Korean")
                }
            }
        }
        type.methods.each { method ->
            method.parameterAnnotations.eachWithIndex { annotations, index ->
                boolean body = annotations.any { resolved(it, imports) == 'org.springframework.web.bind.annotation.RequestBody' }
                if (body && !annotations.any { resolved(it, imports) == 'jakarta.validation.Valid' }) {
                    violations.add("${path} web adapter request body '${method.parameterNames[index]}' must declare @Valid @RequestBody")
                }
            }
            if (request && method.name in ['toCommand', 'toQuery']) {
                if (!method.modifiers.contains('PUBLIC') || method.modifiers.contains('STATIC')
                        || !method.returnType?.endsWith(method.name == 'toCommand' ? 'Command' : 'Query')) {
                    violations.add("${path} web adapter Request conversion must be a public instance toCommand/toQuery returning its application contract")
                }
                if (method.controlDepth > 0) violations.add("${path} web adapter Request conversion must use a separate Mapper for complex conversion")
            }
            if (response && method.name == 'from' && (!method.modifiers.contains('PUBLIC')
                    || !method.modifiers.contains('STATIC') || method.returnType != type.simpleName
                    || method.parameterTypes.size() != 1 || !isResultContract(method.parameterTypes.first(), imports))) {
                violations.add("${path} web adapter Response conversion must be public static from(final XxxResult result)")
            }
        }
        type.nestedTypes.each { violations.addAll(validate(path, packageName, it, imports)) }
        return violations
    }

    /** package-structure: 조회 결과 DTO(`CategorySummary` 등)도 `port.in.result`에 두므로 접미사 대신 패키지로 Result 계약을 판단한다. */
    private static boolean isResultContract(String parameterType, Set<String> imports) {
        if (parameterType.endsWith('Result')) return true
        String outer = parameterType.tokenize('.').find { it ==~ /[A-Z].*/ }
        String qualified = parameterType ==~ /[a-z].*/ ? parameterType : imports.find { it.endsWith('.' + outer) }
        return qualified != null && qualified.contains('.application.port.in.result.')
    }

    private static String resolved(JavaSourceAstInspector.AnnotationModel annotation, Set<String> imports) {
        if (annotation.qualifiedName.contains('.')) return annotation.qualifiedName
        String exact = imports.find { it.endsWith('.' + annotation.simpleName) }
        if (exact != null) return exact
        if (imports.contains('org.springframework.web.bind.annotation.*') && annotation.simpleName == 'RequestBody') {
            return 'org.springframework.web.bind.annotation.RequestBody'
        }
        return annotation.qualifiedName
    }
}
