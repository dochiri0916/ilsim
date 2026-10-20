package io.github.dochiri0916.buildconvention.validator.domain.vo

import io.github.dochiri0916.buildconvention.source.kotlin.KotlinContractSyntax
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

final class KotlinIdentifierRules {
    private KotlinIdentifierRules() {
    }

    static void validate(KtClassOrObject type, KtFile file, String path, List<String> violations) {
        if (!file.packageFqName.asString().endsWith('.domain.vo')) {
            violations.add("[ARCH-PACKAGE-TOPOLOGY] ${path} identifier VO must live directly in domain.vo")
        }
        String contract = """
            package ${file.packageFqName.asString()}
            import java.util.UUID
            @JvmInline
            value class ${type.name} private constructor(val value: UUID) {
                override fun toString(): String = value.toString()
                companion object {
                    fun of(value: UUID): ${type.name} = ${type.name}(value)
                }
            }
        """
        if (!type.name.endsWith('Id') || !KotlinContractSyntax.matches([type], file, contract)) {
            violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} identifier VO must be exactly @JvmInline value class ${type.name} private constructor(val value: UUID), with only toString and public of(value: UUID); validation, parsing, generation, extra state and factories are forbidden")
        }
    }
}
