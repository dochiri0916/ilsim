package com.dochiri.convention.support

import com.sun.source.tree.AnnotationTree
import com.sun.source.tree.AssignmentTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees

import javax.lang.model.element.Modifier
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import java.nio.charset.StandardCharsets

class JavaSourceAstInspector {

    static Inspection inspect(File file) {
        return inspectAll([file]).get(normalizedPath(file))
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
            SourcePositions sourcePositions
    ) {
        String source = file.getText(StandardCharsets.UTF_8.name())
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
                            method.body != null
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
                qualifiedTypeReferences
        )
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
        annotation.arguments.each { argument ->
            if (argument instanceof AssignmentTree) {
                AssignmentTree assignment = (AssignmentTree) argument
                arguments.put(assignment.variable.toString(), annotationValue(assignment.expression))
            } else {
                arguments.put('value', annotationValue(argument))
            }
        }
        return new AnnotationModel(annotation.annotationType.toString(), arguments)
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
                field.modifiers.annotations.collect { annotation -> inspectAnnotation(annotation) }
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
                Set<String> qualifiedTypeReferences
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

        private AnnotationModel(String qualifiedName, Map<String, String> arguments) {
            this.qualifiedName = qualifiedName.replaceAll(/\s+/, '')
            this.simpleName = this.qualifiedName.substring(this.qualifiedName.lastIndexOf('.') + 1)
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments))
        }
    }

    static final class FieldModel {
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
                List<AnnotationModel> annotations
        ) {
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

    static final class MethodModel {
        final String name
        final String returnType
        final List<String> parameterTypes
        final Set<String> modifiers
        final List<AnnotationModel> annotations
        final String declarationHeader
        final String body
        final boolean constructor
        final boolean hasBody

        private MethodModel(
                String name,
                String returnType,
                List<String> parameterTypes,
                Set<String> modifiers,
                List<AnnotationModel> annotations,
                String declarationHeader,
                String body,
                boolean constructor,
                boolean hasBody
        ) {
            this.name = name
            this.returnType = returnType
            this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes))
            this.modifiers = Collections.unmodifiableSet(new LinkedHashSet<>(modifiers))
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations))
            this.declarationHeader = declarationHeader
            this.body = body
            this.constructor = constructor
            this.hasBody = hasBody
        }

        AnnotationModel annotation(String simpleName) {
            return annotations.find { annotation -> annotation.simpleName == simpleName }
        }
    }
}
