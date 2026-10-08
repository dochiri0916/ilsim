package io.github.dochiri0916.buildconvention.support

/** Resolves declarations, including nested types and inherited members, without loading application code. */
final class SourceTypeIndex {
    final Map<String, Map> entries = [:]

    SourceTypeIndex(Collection<JavaSourceAstInspector.Inspection> inspections) {
        inspections.findAll { it.valid }.each { inspection ->
            inspection.types.each { add(it, inspection, inspection.packageName) }
        }
    }

    private void add(JavaSourceAstInspector.TypeModel type, JavaSourceAstInspector.Inspection inspection, String owner) {
        String name = owner + '.' + type.simpleName
        entries[name] = [type: type, inspection: inspection, name: name]
        type.nestedTypes.each { add(it, inspection, name) }
    }

    String resolve(String syntax, Map entry, Set<String> variables = [] as Set) {
        String name = syntax?.replaceAll(/<.*>/, '')?.replaceAll(/\[\]/, '')?.trim()
        if (!name) return ''
        if (entry.type.typeParameterBounds.containsKey(name)) {
            if (!variables.add(name)) return 'java.lang.Object'
            String bound = entry.type.typeParameterBounds[name]
            return bound == name ? 'java.lang.Object' : resolve(bound, entry, variables)
        }
        if (name in ['void', 'boolean', 'byte', 'short', 'int', 'long', 'float', 'double', 'char']
                || name ==~ /[a-z][\w]*(?:\.[\w]+)+/) return name
        if (entries.containsKey(name) || name.startsWith('java.')) return name
        String outer = name.tokenize('.').first()
        String imported = entry.inspection.imports.find { it.endsWith('.' + outer) }
        if (imported) return imported + name.substring(outer.length())
        String owner = entry.name
        while (owner.contains('.')) {
            String candidate = owner + '.' + name
            if (entries.containsKey(candidate)) return candidate
            owner = owner.substring(0, owner.lastIndexOf('.'))
        }
        String local = entry.inspection.packageName + '.' + name
        if (entries.containsKey(local)) return local
        String wildcard = entry.inspection.imports.find {
            it.endsWith('.*') && entries.containsKey(it.substring(0, it.length() - 1) + name)
        }
        if (wildcard) return wildcard.substring(0, wildcard.length() - 1) + name
        if (name in ['Object', 'String', 'Boolean', 'Integer', 'Long', 'Double', 'Float', 'Number', 'CharSequence', 'Runnable', 'FunctionalInterface']) {
            return 'java.lang.' + name
        }
        return local
    }

    List<Map> hierarchy(Map entry, Set<String> visited = [] as Set) {
        if (!visited.add(entry.name)) return []
        List<Map> result = [entry]
        ([entry.type.superType] + entry.type.implementedTypes).findAll().each { parent ->
            Map found = entries[resolve(parent, entry)]
            if (found) result.addAll(hierarchy(found, visited))
        }
        return result
    }
}
