package io.github.dochiri0916.buildconvention.source.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType

final class KotlinContractSyntax {
    private KotlinContractSyntax() {
    }

    static boolean matches(List<? extends KtDeclaration> declarations, KtFile file, String contract) {
        KtFile expected = new KtPsiFactory(file.project, false).createFile('contract.kt', contract)
        return declarations.collect { canonical(it, file) } == expected.declarations.collect { canonical(it, expected) }
    }

    private static Object canonical(PsiElement node, KtFile file) {
        if (node instanceof PsiWhiteSpace || node instanceof PsiComment) return null
        if (node instanceof KtUserType && node.typeArguments.isEmpty()) {
            return ['type', KotlinSourceAstInspector.resolve(file, node.text)]
        }
        Map<String, String> names = ['JvmInline': 'kotlin.jvm.JvmInline', 'String': 'kotlin.String']
        file.importDirectives.findAll { !it.allUnder }.each { directive ->
            names[directive.aliasName ?: directive.importedFqName.shortName().asString()] = directive.importedFqName.asString()
        }
        file.declarations.findAll { it instanceof KtClassOrObject }.each { type ->
            names[type.name] = type.fqName.asString()
        }
        if (node instanceof KtNameReferenceExpression && names.containsKey(node.referencedName)) {
            return ['reference', names[node.referencedName]]
        }
        if (node instanceof KtDotQualifiedExpression && names.values().contains(node.text)) {
            return ['reference', node.text]
        }
        List<Object> children = []
        for (PsiElement child = node.firstChild; child != null; child = child.nextSibling) {
            Object value = canonical(child, file)
            if (value != null) children.add(value)
        }
        return children.isEmpty() ? [node.node.elementType.toString(), node.text] : [node.node.elementType.toString(), children]
    }
}
