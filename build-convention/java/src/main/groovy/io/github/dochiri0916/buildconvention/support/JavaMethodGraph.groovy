package io.github.dochiri0916.buildconvention.support

/** Follows local helper calls without letting recursion hide inspected operations. */
final class JavaMethodGraph {
    private JavaMethodGraph() {}

    static List<JavaSourceAstInspector.MethodModel> reachable(JavaSourceAstInspector.TypeModel type,
                                                             JavaSourceAstInspector.MethodModel method) {
        List<JavaSourceAstInspector.MethodModel> result = []
        visit(type, method, [] as Set, result)
        return result
    }

    static List<JavaSourceAstInspector.MethodModel> callees(JavaSourceAstInspector.TypeModel type,
                                                           List<JavaSourceAstInspector.InvocationModel> calls) {
        type.methods.findAll { method -> !method.constructor && calls.any { call ->
            call.arguments.size() == method.parameterTypes.size()
                    && call.selector in [method.name, 'this.' + method.name,
                                         type.simpleName + '.' + method.name, type.qualifiedName + '.' + method.name]
        } }
    }

    private static void visit(JavaSourceAstInspector.TypeModel type, JavaSourceAstInspector.MethodModel method,
                              Set visited, List<JavaSourceAstInspector.MethodModel> result) {
        if (!visited.add(method)) return
        result.add(method)
        callees(type, method.invocations).each { child -> visit(type, child, visited, result) }
    }
}
