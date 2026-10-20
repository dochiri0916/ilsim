package io.github.dochiri0916.buildconvention.source.java

import com.sun.source.tree.AnnotationTree
import com.sun.source.tree.AssignmentTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.IfTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import java.nio.charset.StandardCharsets
import javax.lang.model.element.Modifier
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

class JavaSourceAstInspector {

    static Inspection inspect(File file) {
        return inspectAll([file]).get(normalizedPath(file))
    }

    static Inspection inspectSource(String fileName, String source) {
        File logicalFile = new File(fileName)
        JavaCompiler compiler = ToolProvider.systemJavaCompiler
        if (compiler == null) return Inspection.failed(logicalFile, ['the system Java compiler is unavailable'])
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>()
        StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)
        try {
            def input = new TextSource(fileName, source)
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, ['-proc:none'], null, [input])
            List<CompilationUnitTree> units = task.parse().toList()
            List<String> errors = diagnostics.diagnostics.findAll { it.kind == Diagnostic.Kind.ERROR }
                    .collect { "line ${it.lineNumber}: ${it.getMessage(Locale.ROOT)}" }
            if (!errors.empty) return Inspection.failed(logicalFile, errors)
            if (units.empty) return Inspection.failed(logicalFile, ['no compilation unit was parsed'])
            return inspectCompilationUnit(logicalFile, units.first(), Trees.instance(task).sourcePositions, source)
        } catch (RuntimeException exception) {
            return Inspection.failed(logicalFile, [exception.message ?: exception.class.simpleName])
        } finally {
            manager.close()
        }
    }

    private static final class TextSource extends SimpleJavaFileObject {
        private final String text

        TextSource(String fileName, String text) {
            super(new URI('memory', null, '/' + fileName.replace('\\', '/'), null), JavaFileObject.Kind.SOURCE)
            this.text = text
        }

        @Override
        CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text
        }
    }

    static Map<String, Inspection> inspectAll(Collection<File> files) {
        List<File> sourceFiles = files.collect { file -> file.absoluteFile }.unique { file ->
            normalizedPath(file)
        }
        if (sourceFiles.isEmpty()) {
            return [:]
        }
        JavaCompiler compiler = ToolProvider.systemJavaCompiler
        if (compiler == null) {
            return sourceFiles.collectEntries { file ->
                [(normalizedPath(file)): Inspection.failed(file, ['the system Java compiler is unavailable'])]
            }
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>()
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8
        )
        try {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(sourceFiles)
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    ['-proc:none'],
                    null,
                    sources
            )
            List<CompilationUnitTree> units = task.parse().toList()
            SourcePositions sourcePositions = Trees.instance(task).sourcePositions
            Map<String, CompilationUnitTree> unitsByPath = units.collectEntries { unit ->
                [(normalizedUri(unit.sourceFile.toUri())): unit]
            }
            Map<String, List<String>> errorsByPath = [:].withDefault { [] }
            diagnostics.diagnostics.findAll { diagnostic ->
                diagnostic.kind == Diagnostic.Kind.ERROR && diagnostic.source != null
            }.each { diagnostic ->
                String sourcePath = normalizedUri(diagnostic.source.toUri())
                errorsByPath.get(sourcePath).add(
                        "line ${diagnostic.lineNumber}: ${diagnostic.getMessage(Locale.ROOT)}"
                )
            }
            return sourceFiles.collectEntries { file ->
                String sourcePath = normalizedPath(file)
                List<String> errors = errorsByPath.get(sourcePath)
                CompilationUnitTree unit = unitsByPath.get(sourcePath)
                Inspection inspection
                if (errors != null && !errors.isEmpty()) {
                    inspection = Inspection.failed(file, errors)
                } else if (unit == null) {
                    inspection = Inspection.failed(file, ['no compilation unit was parsed'])
                } else {
                    inspection = inspectCompilationUnit(file, unit, sourcePositions)
                }
                [(sourcePath): inspection]
            }
        } catch (RuntimeException exception) {
            return sourceFiles.collectEntries { file ->
                [(normalizedPath(file)): Inspection.failed(
                        file,
                        [exception.message ?: exception.class.simpleName]
                )]
            }
        } finally {
            fileManager.close()
        }
    }

    private static Inspection inspectCompilationUnit(
            File file,
            CompilationUnitTree unit,
            SourcePositions sourcePositions,
            String snapshot = null
    ) {
        String source = snapshot == null ? file.getText(StandardCharsets.UTF_8.name()) : snapshot
        String packageName = unit.packageName?.toString() ?: ''
        Set<String> allImports = unit.imports
                .collect { imported -> imported.qualifiedIdentifier.toString() }
                .toSet()
        Set<String> imports = unit.imports
                .findAll { imported -> !imported.static }
                .collect { imported -> imported.qualifiedIdentifier.toString() }
                .toSet()
        List<TypeModel> types = unit.typeDecls
                .findAll { declaration -> declaration instanceof ClassTree }
                .collect { declaration ->
                    inspectType(packageName, (ClassTree) declaration, unit, sourcePositions, source)
                }
        if (types.isEmpty()) {
            return Inspection.failed(file, ['no top-level type declaration was found'])
        }
        return new Inspection(file, packageName, imports, allImports, types, [])
    }

    private static TypeModel inspectType(
            String packageName,
            ClassTree type,
            CompilationUnitTree unit,
            SourcePositions sourcePositions,
            String source
    ) {
        String simpleName = type.simpleName.toString()
        List<AnnotationModel> annotations = type.modifiers.annotations.collect { annotation ->
            inspectAnnotation(annotation)
        }
        List<FieldModel> allFields = type.members
                .findAll { member -> member instanceof VariableTree }
                .collect { member -> (VariableTree) member }
                .findAll { member -> member.type != null }
                .collect { member -> inspectField(member) }
        boolean recordType = type.kind.name() == 'RECORD'
        List<FieldModel> recordComponents = recordType
                ? allFields.findAll { field -> !field.staticField }
                : []
        List<FieldModel> fields = recordType
                ? allFields.findAll { field -> field.staticField }
                : allFields
        Set<String> memberTypes = allFields
                .findAll { field -> !field.staticField }
                .collect { field -> field.type }
                .toSet()
        List<MethodModel> methods = type.members
                .findAll { member -> member instanceof MethodTree }
                .collect { member -> (MethodTree) member }
                .collect { method ->
                    new MethodModel(
                            method.name.toString(),
                            method.returnType == null ? null : normalizeAstType(method.returnType.toString()),
                            method.parameters.collect { parameter -> normalizeAstType(parameter.type.toString()) },
                            method.modifiers.flags.collect { modifier -> modifier.name() }.toSet(),
                            method.modifiers.annotations.collect { annotation -> inspectAnnotation(annotation) },
                            extractMethodHeader(unit, sourcePositions, source, method),
                            extractMethodBody(unit, sourcePositions, source, method),
                            method.returnType == null,
                            method.body != null,
                            method.parameters.collect { parameter -> parameter.name.toString() },
                            method.parameters.collect { parameter -> parameter.modifiers.flags.contains(Modifier.FINAL) },
                            inspectControlFlow(method),
                            method.body?.toString(),
                            method.parameters.collect { parameter -> parameter.modifiers.annotations.collect { annotation -> inspectAnnotation(annotation) } },
                            inspectInvocations(method.body),
                            inspectCatches(method)
                    )
        }
        String qualifiedName = packageName.isBlank() ? simpleName : "${packageName}.${simpleName}"
        Set<String> modifiers = type.modifiers.flags.collect { modifier -> modifier.name() }.toSet()
        List<String> implementedTypes = type.implementsClause.collect { implementedType ->
            normalizeAstType(implementedType.toString())
        }
        String superType = type.extendsClause == null
                ? null
                : normalizeAstType(type.extendsClause.toString())
        List<TypeModel> nestedTypes = type.members
                .findAll { member -> member instanceof ClassTree }
                .collect { member ->
                    inspectType(packageName, (ClassTree) member, unit, sourcePositions, source)
                }
        Set<String> qualifiedTypeReferences = inspectQualifiedTypeReferences(type)
        return new TypeModel(
                qualifiedName,
                simpleName,
                type.kind.name(),
                modifiers,
                annotations,
                fields,
                recordComponents,
                memberTypes,
                methods,
                implementedTypes,
                superType,
                nestedTypes,
                qualifiedTypeReferences,
                type.permitsClause.collect { permittedType -> normalizeAstType(permittedType.toString()) },
                type.typeParameters.collect { parameter -> parameter.name.toString() },
                type.typeParameters.collectEntries { parameter ->
                    [(parameter.name.toString()): parameter.bounds.empty ? 'java.lang.Object' : normalizeAstType(parameter.bounds.first().toString())]
                }
        )
    }

    private static List<InvocationModel> inspectInvocations(Tree body) {
        List<InvocationModel> calls = []
        if (body == null) return calls
        new TreeScanner<Void, Void>() {
            @Override
            Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                calls.add(new InvocationModel(invocation.methodSelect.toString(),
                        invocation.arguments.collect { it.toString() },
                        invocation.arguments.collect { it instanceof LiteralTree ? ((LiteralTree) it).value : null }))
                return super.visitMethodInvocation(invocation, unused)
            }
        }.scan(body, null)
        return calls
    }

    private static List<CatchModel> inspectCatches(MethodTree method) {
        List<CatchModel> catches = []
        if (method.body == null) return catches
        new TreeScanner<Void, Void>() {
            @Override
            Void visitCatch(CatchTree caught, Void unused) {
                catches.add(new CatchModel(normalizeAstType(caught.parameter.type.toString()),
                        caught.parameter.name.toString(), caught.block.toString(), inspectInvocations(caught.block)))
                return super.visitCatch(caught, unused)
            }
        }.scan(method.body, null)
        return catches
    }

    private static Map<String, Object> inspectControlFlow(MethodTree method) {
        Map<String, Object> facts = [depth: 0, hasElse: false]
        new TreeScanner<Void, Integer>() {
            @Override
            Void scan(Tree tree, Integer depth) {
                if (tree == null) {
                    return null
                }
                int nestedDepth = depth
                if (tree.kind.name() in ['IF', 'FOR_LOOP', 'ENHANCED_FOR_LOOP', 'WHILE_LOOP',
                                         'DO_WHILE_LOOP', 'SWITCH', 'SWITCH_EXPRESSION', 'TRY', 'SYNCHRONIZED']) {
                    nestedDepth++
                    facts.depth = Math.max((int) facts.depth, nestedDepth)
                }
                if (tree instanceof IfTree && ((IfTree) tree).elseStatement != null) {
                    facts.hasElse = true
                }
                return super.scan(tree, nestedDepth)
            }
        }.scan(method.body, 0)
        return facts
    }

    private static Set<String> inspectQualifiedTypeReferences(ClassTree type) {
        Set<String> references = new LinkedHashSet<>()
        new TreeScanner<Void, Set<String>>() {
            @Override
            Void visitMemberSelect(MemberSelectTree node, Set<String> collectedReferences) {
                String typeReference = qualifiedTypeReference(node.toString())
                if (typeReference != null) {
                    collectedReferences.add(typeReference)
                }
                return super.visitMemberSelect(node, collectedReferences)
            }
        }.scan(type, references)
        return references
    }

    private static String qualifiedTypeReference(String expression) {
        List<String> segments = expression.replaceAll(/\s+/, '').tokenize('.')
        int typeSegmentIndex = segments.findIndexOf { segment ->
            !segment.isEmpty() && Character.isUpperCase(segment.charAt(0))
        }
        if (typeSegmentIndex <= 0) {
            return null
        }
        List<String> typeSegments = segments.take(typeSegmentIndex + 1)
        if (!typeSegments.every { segment -> isJavaIdentifier(segment) }) {
            return null
        }
        return typeSegments.join('.')
    }

    private static boolean isJavaIdentifier(String segment) {
        if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
            return false
        }
        return segment.substring(1).every { character ->
            Character.isJavaIdentifierPart((char) character)
        }
    }

    private static String extractMethodBody(
            CompilationUnitTree unit,
            SourcePositions sourcePositions,
            String source,
            MethodTree method
    ) {
        if (method.body == null) {
            return ''
        }
        long bodyStart = sourcePositions.getStartPosition(unit, method.body)
        long bodyEnd = sourcePositions.getEndPosition(unit, method.body)
        if (bodyStart < 0 || bodyEnd <= bodyStart + 1 || bodyEnd > source.length()) {
            return method.body.toString()
        }
        return source.substring((int) bodyStart + 1, (int) bodyEnd - 1)
    }

    private static String extractMethodHeader(
            CompilationUnitTree unit,
            SourcePositions sourcePositions,
            String source,
            MethodTree method
    ) {
        long methodStart = sourcePositions.getStartPosition(unit, method)
        long headerEnd = method.body == null
                ? sourcePositions.getEndPosition(unit, method)
                : sourcePositions.getStartPosition(unit, method.body)
        if (methodStart < 0 || headerEnd <= methodStart || headerEnd > source.length()) {
            return ''
        }
        return source.substring((int) methodStart, (int) headerEnd)
    }

    private static AnnotationModel inspectAnnotation(AnnotationTree annotation) {
        Map<String, String> arguments = new LinkedHashMap<>()
        Set<String> literalArguments = new LinkedHashSet<>()
        annotation.arguments.each { argument ->
            if (argument instanceof AssignmentTree) {
                AssignmentTree assignment = (AssignmentTree) argument
                arguments.put(assignment.variable.toString(), annotationValue(assignment.expression))
                if (assignment.expression instanceof LiteralTree) literalArguments.add(assignment.variable.toString())
            } else {
                arguments.put('value', annotationValue(argument))
                if (argument instanceof LiteralTree) literalArguments.add('value')
            }
        }
        List<AnnotationModel> nested = []
        new TreeScanner<Void, Void>() {
            @Override
            Void visitAnnotation(AnnotationTree child, Void unused) {
                nested.add(inspectAnnotation(child))
                return null
            }
        }.scan(annotation.arguments, null)
        return new AnnotationModel(annotation.annotationType.toString(), arguments, nested, literalArguments)
    }

    private static String annotationValue(Object expression) {
        if (expression instanceof LiteralTree) {
            Object value = ((LiteralTree) expression).value
            return value == null ? 'null' : value.toString()
        }
        return expression.toString()
    }

    private static FieldModel inspectField(VariableTree field) {
        return new FieldModel(
                field.name.toString(),
                normalizeAstType(field.type.toString()),
                field.modifiers.flags.contains(Modifier.STATIC),
                field.modifiers.flags.contains(Modifier.FINAL),
                field.modifiers.flags.collect { modifier -> modifier.name() }.toSet(),
                field.modifiers.annotations.collect { annotation -> inspectAnnotation(annotation) },
                field.initializer?.toString(),
                field.initializer instanceof LiteralTree ? ((LiteralTree) field.initializer).value : null
        )
    }

    private static String normalizeAstType(String type) {
        return type
                .replaceAll(/@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*(?:\s*\([^)]*\))?\s*/, '')
                .replaceAll(/\s+/, ' ')
                .trim()
    }

    private static String normalizedPath(File file) {
        return file.absoluteFile.toPath().normalize().toString()
    }

    private static String normalizedUri(URI uri) {
        return new File(uri).absoluteFile.toPath().normalize().toString()
    }

    static final class Inspection {
        final File file
        final String packageName
        final Set<String> imports
        final Set<String> allImports
        final List<TypeModel> types
        final List<String> errors

        private Inspection(
                File file,
                String packageName,
                Set<String> imports,
                Set<String> allImports,
                List<TypeModel> types,
                List<String> errors
        ) {
            this.file = file
            this.packageName = packageName
            this.imports = Collections.unmodifiableSet(new LinkedHashSet<>(imports))
            this.allImports = Collections.unmodifiableSet(new LinkedHashSet<>(allImports))
            this.types = Collections.unmodifiableList(new ArrayList<>(types))
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors))
        }

        static Inspection failed(File file, List<String> errors) {
            return new Inspection(file, '', [] as Set, [] as Set, [], errors)
        }

        boolean isValid() {
            return errors.isEmpty()
        }

        TypeModel primaryType() {
            String expectedName = file.name.replaceFirst(/\.java$/, '')
            return types.find { type -> type.simpleName == expectedName } ?: types.first()
        }
    }

    static final class TypeModel {
        final String qualifiedName
        final String simpleName
        final String kind
        final Set<String> modifiers
        final List<AnnotationModel> annotations
        final List<FieldModel> fields
        final List<FieldModel> recordComponents
        final Set<String> memberTypes
        final List<MethodModel> methods
        final List<String> implementedTypes
        final String superType
        final List<TypeModel> nestedTypes
        final Set<String> qualifiedTypeReferences
        final List<String> permittedTypes
        final List<String> typeParameters
        final Map<String, String> typeParameterBounds

        private TypeModel(
                String qualifiedName,
                String simpleName,
                String kind,
                Set<String> modifiers,
                List<AnnotationModel> annotations,
                List<FieldModel> fields,
                List<FieldModel> recordComponents,
                Set<String> memberTypes,
                List<MethodModel> methods,
                List<String> implementedTypes,
                String superType,
                List<TypeModel> nestedTypes,
                Set<String> qualifiedTypeReferences,
                List<String> permittedTypes,
                List<String> typeParameters,
                Map<String, String> typeParameterBounds
        ) {
            this.qualifiedName = qualifiedName
            this.simpleName = simpleName
            this.kind = kind
            this.modifiers = Collections.unmodifiableSet(new LinkedHashSet<>(modifiers))
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations))
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields))
            this.recordComponents = Collections.unmodifiableList(new ArrayList<>(recordComponents))
            this.memberTypes = Collections.unmodifiableSet(new LinkedHashSet<>(memberTypes))
            this.methods = Collections.unmodifiableList(new ArrayList<>(methods))
            this.implementedTypes = Collections.unmodifiableList(new ArrayList<>(implementedTypes))
            this.superType = superType
            this.permittedTypes = Collections.unmodifiableList(new ArrayList<>(permittedTypes))
            this.typeParameters = Collections.unmodifiableList(new ArrayList<>(typeParameters))
            this.typeParameterBounds = Collections.unmodifiableMap(new LinkedHashMap<>(typeParameterBounds))
            this.nestedTypes = Collections.unmodifiableList(new ArrayList<>(nestedTypes))
            this.qualifiedTypeReferences = Collections.unmodifiableSet(
                    new LinkedHashSet<>(qualifiedTypeReferences)
            )
        }

        AnnotationModel annotation(String simpleName) {
            return annotations.find { annotation -> annotation.simpleName == simpleName }
        }
    }

    static final class AnnotationModel {
        final String qualifiedName
        final String simpleName
        final Map<String, String> arguments
        final List<AnnotationModel> nestedAnnotations
        final Set<String> literalArguments

        private AnnotationModel(String qualifiedName, Map<String, String> arguments, List<AnnotationModel> nestedAnnotations, Set<String> literalArguments) {
            this.qualifiedName = qualifiedName.replaceAll(/\s+/, '')
            this.simpleName = this.qualifiedName.substring(this.qualifiedName.lastIndexOf('.') + 1)
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments))
            this.nestedAnnotations = Collections.unmodifiableList(new ArrayList<>(nestedAnnotations))
            this.literalArguments = Collections.unmodifiableSet(new LinkedHashSet<>(literalArguments))
        }
    }

    static final class FieldModel {
        final String initializer
        final Object literalInitializer
        final String name
        final String type
        final boolean staticField
        final boolean finalField
        final Set<String> modifiers
        final List<AnnotationModel> annotations

        private FieldModel(
                String name,
                String type,
                boolean staticField,
                boolean finalField,
                Set<String> modifiers,
                List<AnnotationModel> annotations,
                String initializer,
                Object literalInitializer
        ) {
            this.initializer = initializer
            this.literalInitializer = literalInitializer
            this.name = name
            this.type = type
            this.staticField = staticField
            this.finalField = finalField
            this.modifiers = Collections.unmodifiableSet(new LinkedHashSet<>(modifiers))
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations))
        }

        AnnotationModel annotation(String simpleName) {
            return annotations.find { annotation -> annotation.simpleName == simpleName }
        }
    }

    static final class InvocationModel {
        final String selector
        final List<String> arguments
        final List<Object> literalArguments

        private InvocationModel(String selector, List<String> arguments, List<Object> literalArguments) {
            this.selector = selector
            this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments))
            this.literalArguments = Collections.unmodifiableList(new ArrayList<>(literalArguments))
        }
    }

    static final class CatchModel {
        final String parameterType
        final String parameterName
        final String codeBody
        final List<InvocationModel> invocations

        private CatchModel(String parameterType, String parameterName, String codeBody, List<InvocationModel> invocations) {
            this.parameterType = parameterType
            this.parameterName = parameterName
            this.codeBody = codeBody
            this.invocations = Collections.unmodifiableList(new ArrayList<>(invocations))
        }
    }

    static final class MethodModel {
        final String name
        final String returnType
        final List<String> parameterTypes
        final Set<String> modifiers
        final List<AnnotationModel> annotations
        final String declarationHeader
        final String body
        final String codeBody
        final boolean constructor
        final boolean hasBody
        final List<String> parameterNames
        final List<List<AnnotationModel>> parameterAnnotations
        final List<Boolean> finalParameters
        final int controlDepth
        final List<InvocationModel> invocations
        final List<CatchModel> catches
        final boolean hasElse

        private MethodModel(
                String name,
                String returnType,
                List<String> parameterTypes,
                Set<String> modifiers,
                List<AnnotationModel> annotations,
                String declarationHeader,
                String body,
                boolean constructor,
                boolean hasBody,
                List<String> parameterNames,
                List<Boolean> finalParameters,
                Map<String, Object> controlFlow,
                String codeBody,
                List<List<AnnotationModel>> parameterAnnotations,
                List<InvocationModel> invocations,
                List<CatchModel> catches
        ) {
            this.name = name
            this.returnType = returnType
            this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes))
            this.modifiers = Collections.unmodifiableSet(new LinkedHashSet<>(modifiers))
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations))
            this.declarationHeader = declarationHeader
            this.body = body
            this.codeBody = codeBody
            this.parameterAnnotations = Collections.unmodifiableList(parameterAnnotations.collect { parameterItems -> Collections.unmodifiableList(new ArrayList<>(parameterItems)) })
            this.constructor = constructor
            this.hasBody = hasBody
            this.parameterNames = Collections.unmodifiableList(new ArrayList<>(parameterNames))
            this.finalParameters = Collections.unmodifiableList(new ArrayList<>(finalParameters))
            this.controlDepth = (int) controlFlow.depth
            this.hasElse = (boolean) controlFlow.hasElse
            this.invocations = Collections.unmodifiableList(new ArrayList<>(invocations))
            this.catches = Collections.unmodifiableList(new ArrayList<>(catches))
        }

        AnnotationModel annotation(String simpleName) {
            return annotations.find { annotation -> annotation.simpleName == simpleName }
        }
    }
}
