package io.github.dochiri0916.buildconvention.validator.adapter.identifier

import io.github.dochiri0916.buildconvention.source.kotlin.KotlinContractSyntax
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceAstInspector
import org.gradle.api.Project
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Closed source contracts for the inbound UUID guard and outbound UUID-v7 generator/port chain. */
final class KotlinIdentifierBoundaryRules {
    private static final String CREATOR = 'com.github.f4b6a3.uuid.UuidCreator'
    private static final Set<String> EXTERNAL_TYPES = ['java.util.UUID', CREATOR, 'kotlin.jvm.JvmInline',
                                                       'kotlin.String', 'kotlin.IllegalArgumentException',
                                                       'org.springframework.stereotype.Component'] as Set

    private KotlinIdentifierBoundaryRules() {
    }

    static void validate(Project project, List<Map> inspections, Map<String, KtClassOrObject> types,
                         List<String> violations) {
        List<KtClassOrObject> ids = types.values().findAll { type ->
            type.containingKtFile.packageFqName.asString().tokenize('.').contains('domain') && type.name?.endsWith('Id')
        }
        List<Map> parsed = inspections.findAll { it.errors.isEmpty() }
        List<KtClassOrObject> owned = ids.findAll { !it.name.endsWith('ReferenceId') }
        Map<KtFile, String> paths = parsed.collectEntries { [(it.ast): project.relativePath(it.file)] }
        Set<String> roots = ids.collect { type -> type.containingKtFile.packageFqName.asString().split(/\.domain(?:\.|$)/)[0] }.toSet()
        roots.each { String root ->
            List<Map> helpers = parsed.findAll { inspection ->
                inspection.ast.packageFqName.asString() == "${root}.adapter.in" &&
                        inspection.ast.declarations.any { it instanceof KtNamedFunction && it.name == 'requireV7' }
            }
            if (helpers.size() != 1) {
                violations.add("[ARCH-WEB-ADAPTER] ${root} identifiers require exactly one adapter.in UUID.requireV7(field: String) guard")
            }
            helpers.each { validateInbound(it.ast, project.relativePath(it.file), violations) }
        }
        owned.each { type ->
            String root = type.containingKtFile.packageFqName.asString().split(/\.domain(?:\.|$)/)[0]
            String port = "${root}.application.port.out.${type.name}Generator"
            String generator = "${root}.adapter.out.identifier.UuidV7${type.name}Generator"
            validateTypeContract(paths, types[port], port, paths[type.containingKtFile], portContract(root, type.name), violations)
            validateTypeContract(paths, types[generator], generator, paths[type.containingKtFile], generatorContract(root, type.name), violations)
        }
        parsed.each { inspection ->
            KtFile file = inspection.ast
            String path = project.relativePath(inspection.file)
            String pkg = file.packageFqName.asString()
            boolean inbound = pkg.endsWith('.adapter.in') || pkg.contains('.adapter.in.')
            KotlinSourceAstInspector.nodes(file, KtTypeAlias).each { alias ->
                if (alias.fqName?.asString() in EXTERNAL_TYPES) {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} identifier contract types must not be shadowed by type aliases")
                }
                String target = KotlinSourceAstInspector.resolve(file, identifierReceiver(alias.getTypeReference()?.text))
                if (ids.contains(types[target]) || pkg.tokenize('.').contains('domain') &&
                        (alias.name.endsWith('Id') || target == 'java.util.UUID')) {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} identifier aliases cannot replace the required value class")
                }
            }
            file.declarations.findAll { it instanceof KtClassOrObject }.each { type ->
                String name = type.fqName?.asString()
                if (name in EXTERNAL_TYPES) violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} identifier contract types must not be shadowed by source declarations")
                if (pkg.endsWith('.adapter.out.identifier') && !owned.any { id ->
                    name == "${id.containingKtFile.packageFqName.asString().split(/\.domain(?:\.|$)/)[0]}.adapter.out.identifier.UuidV7${id.name}Generator"
                }) violations.add("[ARCH-PORT-CONTRACT] ${path} identifier adapter must implement the matching UuidV7{Domain}IdGenerator contract")
                if (pkg.endsWith('.application.port.out') && type.name?.endsWith('IdGenerator') && !owned.any { id ->
                    name == "${id.containingKtFile.packageFqName.asString().split(/\.domain(?:\.|$)/)[0]}.application.port.out.${id.name}Generator"
                }) violations.add("[ARCH-PORT-CONTRACT] ${path} identifier generator ports are allowed only for locally owned identifiers")
            }
            KotlinSourceAstInspector.nodes(file, KtNamedFunction).each { function ->
                if (function.name == 'requireV7' && (!roots.any { pkg == "${it}.adapter.in" } || function.parent != file)) {
                    violations.add("[ARCH-WEB-ADAPTER] ${path} UUID.requireV7 must be the top-level guard in adapter.in")
                }
                def reference = function.receiverTypeReference
                String receiver = reference == null ? null : identifierReceiver(reference.text)
                if (receiver != null && ids.contains(types[KotlinSourceAstInspector.resolve(file, receiver)])) {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} identifier extensions cannot add methods or factories to the closed VO contract")
                }
            }
            if (pkg.endsWith('.adapter.out.identifier') && file.declarations.any { !(it instanceof KtClassOrObject) }) {
                violations.add("[ARCH-PORT-CONTRACT] ${path} identifier generation must use the required adapter class, not top-level declarations")
            }
            file.importDirectives.each { directive ->
                String imported = directive.importedFqName?.asString()
                if (imported?.startsWith('com.github.f4b6a3.uuid') && !pkg.endsWith('.adapter.out.identifier')) {
                    violations.add("[ARCH-DOMAIN-DEPENDENCY] ${path} UUID Creator is allowed only in adapter.out.identifier")
                }
            }
            KotlinSourceAstInspector.nodes(file, KtCallExpression).each { call ->
                String name = call.calleeExpression?.text
                String resolved = KotlinSourceAstInspector.resolve(file, name)
                def parent = call.parent
                String owner = parent instanceof KtDotQualifiedExpression ?
                        KotlinSourceAstInspector.resolve(file, parent.receiverExpression.text) : null
                String invocation = owner == null ? resolved : "${owner}.${name}"
                if ((name == 'requireV7' || resolved.endsWith('.requireV7')) && !inbound) {
                    violations.add("[ARCH-WEB-ADAPTER] ${path} UUID.requireV7 is allowed only at the inbound adapter boundary")
                }
                if (name in ['randomUUID', 'nameUUIDFromBytes'] || resolved in ['java.util.UUID',
                        'java.util.UUID.randomUUID', 'java.util.UUID.nameUUIDFromBytes'] || invocation == 'java.util.UUID') {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} UUID generation must use UuidCreator.getTimeOrderedEpoch through the identifier adapter")
                }
                if ((owner == CREATOR || resolved.startsWith("${CREATOR}.")) && !pkg.endsWith('.adapter.out.identifier')) {
                    violations.add("[ARCH-DOMAIN-DEPENDENCY] ${path} UUID generation belongs only to adapter.out.identifier")
                }
            }
        }
    }

    private static String identifierReceiver(String name) {
        name?.replaceAll(/\s+/, '')?.replaceFirst(/\?$/, '')?.replaceFirst(/\.Companion$/, '')
    }

    private static void validateInbound(KtFile file, String path, List<String> violations) {
        def exception = file.declarations.find { it instanceof KtClassOrObject && it.name == 'InvalidUuidV7Exception' }
        def parent = exception?.superTypeListEntries?.find { it instanceof KtSuperTypeCallEntry }
        def argument = parent?.valueArguments?.find { true }?.argumentExpression
        String message = argument instanceof KtStringTemplateExpression ? argument.text : '"UUIDv7 형식이 아닙니다. field=$field"'
        boolean includesField = argument instanceof KtStringTemplateExpression &&
                argument.entries.any { it instanceof KtStringTemplateEntryWithExpression && it.expression?.text == 'field' } &&
                argument.entries.every { !(it instanceof KtStringTemplateEntryWithExpression) || it.expression?.text == 'field' }
        String contract = """
            ${file.packageDirective.text}
            import java.util.UUID
            private const val UUID_V7 = 7
            private const val RFC_VARIANT = 2
            class InvalidUuidV7Exception(val field: String, val value: UUID) : IllegalArgumentException(${message})
            fun UUID.requireV7(field: String): UUID {
                if (version() != UUID_V7 || variant() != RFC_VARIANT) throw InvalidUuidV7Exception(field, this)
                return this
            }
        """
        if (!includesField || !KotlinContractSyntax.matches(file.declarations, file, contract)) {
            violations.add("[ARCH-WEB-ADAPTER] ${path} UUID.requireV7 must directly reject version != 7 OR variant != 2 with InvalidUuidV7Exception(field, this), then return this; only the private constants and immutable exception contract are allowed")
        }
    }

    private static void validateTypeContract(Map<KtFile, String> paths, KtClassOrObject type, String name,
                                             String requiredBy, String contract, List<String> violations) {
        if (type == null) {
            violations.add("[ARCH-PORT-CONTRACT] ${requiredBy} ${name} is required for every locally owned identifier")
        } else if (!KotlinContractSyntax.matches(type.containingKtFile.declarations, type.containingKtFile, contract)) {
            violations.add("[ARCH-PORT-CONTRACT] ${paths[type.containingKtFile]} ${name} must use only the matching generate(): {Domain}Id port and @Component adapter returning {Domain}Id.of(UuidCreator.getTimeOrderedEpoch())")
        }
    }

    private static String portContract(String root, String id) {
        """
            package ${root}.application.port.out
            import ${root}.domain.vo.${id}
            interface ${id}Generator { fun generate(): ${id} }
        """
    }

    private static String generatorContract(String root, String id) {
        """
            package ${root}.adapter.out.identifier
            import ${root}.application.port.out.${id}Generator
            import ${root}.domain.vo.${id}
            import com.github.f4b6a3.uuid.UuidCreator
            import org.springframework.stereotype.Component
            @Component
            class UuidV7${id}Generator : ${id}Generator {
                override fun generate(): ${id} = ${id}.of(UuidCreator.getTimeOrderedEpoch())
            }
        """
    }
}
