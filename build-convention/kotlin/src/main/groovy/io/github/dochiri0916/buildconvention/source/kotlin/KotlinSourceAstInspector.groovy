package io.github.dochiri0916.buildconvention.source.kotlin

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

final class KotlinSourceAstInspector {
    private KotlinSourceAstInspector() {
    }

    static synchronized <T> T inspect(List<File> sources, Closure<T> analyze) {
        def disposable = Disposer.newDisposable('build-convention-kotlin')
        try {
            def environment = KotlinCoreEnvironment.createForProduction(disposable,
                    new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            KtPsiFactory factory = new KtPsiFactory(environment.project, false)
            List<Map> files = sources.collect { File source ->
                try {
                    KtFile ast = factory.createFile(source.name, source.getText('UTF-8'))
                    List<String> errors = nodes(ast, PsiErrorElement).collect { error -> error.errorDescription }
                    return [file: source, ast: ast, errors: errors]
                } catch (IOException | RuntimeException failure) {
                    return [file: source, ast: null, errors: [failure.message ?: failure.class.simpleName]]
                }
            }
            return analyze.call(files)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    static <T extends PsiElement> List<T> nodes(PsiElement root, Class<T> type) {
        if (root == null) return []
        List<T> result = PsiTreeUtil.collectElementsOfType(root, type).toList()
        if (type.isInstance(root) && !result.contains(root)) result.add(0, type.cast(root))
        return result
    }

    static String qualifiedName(KtClassOrObject type) {
        type.fqName?.asString()
    }

    static String resolve(KtFile file, String name) {
        String raw = (name ?: '').replaceAll(/[?<>].*$/, '').trim()
        def imported = file.importDirectives.find { directive ->
            !directive.allUnder && (directive.aliasName ?: directive.importedFqName?.shortName()?.asString()) == raw
        }
        if (imported != null) return imported.importedFqName.asString()
        if (raw.contains('.')) return raw
        if (raw == 'Math') return 'java.lang.Math'
        if (raw in ['String', 'Int', 'Long', 'Boolean', 'Any', 'Throwable', 'RuntimeException', 'IllegalArgumentException', 'ArithmeticException']) {
            return "kotlin.${raw}"
        }
        if (raw == 'JvmInline') return 'kotlin.jvm.JvmInline'
        return "${file.packageFqName.asString()}.${raw}"
    }
}
