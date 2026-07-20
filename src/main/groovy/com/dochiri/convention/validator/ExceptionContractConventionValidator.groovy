package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import org.gradle.api.Project

/** Context/layer exception cardinality rules extracted from the source architecture validator. */
final class ExceptionContractConventionValidator {
    private ExceptionContractConventionValidator() {
    }

    static List<String> validate(
            Project project,
            List<File> sourceFiles,
            AggregateBoundaryConventionValidator.Analysis analysis,
            HexagonalConventionExtension convention
    ) {
        if (!convention.enforceContextExceptionConsolidation) {
            return []
        }
        Map<String, List<String>> typesByContextAndLayer = [:].withDefault { [] }
        sourceFiles.each { File file ->
            JavaSourceAstInspector.Inspection inspection = analysis.inspectionFor(file)
            if (inspection == null || !inspection.valid) {
                return
            }
            String layer = exceptionLayer(inspection.packageName, convention)
            if (layer == null) {
                return
            }
            JavaSourceAstInspector.TypeModel type = inspection.primaryType()
            String base = layer == 'domain' ? 'DomainException' : 'ApplicationException'
            if (type.kind != 'CLASS'
                    || type.modifiers.contains('ABSTRACT')
                    || !type.simpleName.endsWith('Exception')
                    || simplify(type.superType) != base) {
                return
            }
            String marker = ".${layer}.exception"
            String context = inspection.packageName.substring(0, inspection.packageName.lastIndexOf(marker))
            typesByContextAndLayer["${context}:${layer}"].add(type.simpleName)
        }
        List<String> violations = []
        typesByContextAndLayer.each { String key, List<String> names ->
            if (names.size() > 1 && !convention.exceptionTypeSplitAllowlist.contains(key)) {
                String layer = key.substring(key.lastIndexOf(':') + 1)
                violations.add(
                        "${key} must declare one concrete ${layer} exception; found ${names.sort().join(', ')}. "
                                + "Use one context exception with static factories or add '${key}' to exceptionTypeSplitAllowlist."
                )
            }
        }
        return violations
    }

    private static String exceptionLayer(String packageName, HexagonalConventionExtension convention) {
        if (packageName.endsWith(".${convention.domainPackageSegment}.exception")) {
            return 'domain'
        }
        if (packageName.endsWith(".${convention.applicationPackageSegment}.exception")) {
            return 'application'
        }
        return null
    }

    private static String simplify(String type) {
        type == null ? null : type.tokenize('.').last()
    }
}
