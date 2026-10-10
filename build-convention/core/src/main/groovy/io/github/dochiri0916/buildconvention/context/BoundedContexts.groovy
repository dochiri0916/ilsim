package io.github.dochiri0916.buildconvention.context

/**
 * Resolves the bounded context of a package or type name.
 *
 * A context is the first package segment under the application root package, so a grouped context such as
 * {@code catalog.category} and {@code catalog.product} belongs to the single context {@code catalog}: its modules
 * share one model and may use each other directly, while other contexts still need integration ports.
 * Without a known root, the segment just before the first layer segment is used.
 */
final class BoundedContexts {
    private static final List<String> LAYER_SEGMENTS = ['domain', 'application', 'adapter'].asImmutable()
    private static final ThreadLocal<Set<String>> ROOTS = ThreadLocal.withInitial { [] as Set<String> }

    private BoundedContexts() {
    }

    static <T> T withRoots(Set<String> roots, Closure<T> action) {
        Set<String> previous = ROOTS.get()
        ROOTS.set((roots ?: [] as Set<String>).findAll { String root -> root != null && !root.isBlank() }.toSet())
        try {
            return action.call()
        } finally {
            ROOTS.set(previous)
        }
    }

    static String contextOf(String name, String layerSegment = null) {
        if (name == null || name.isBlank()) {
            return null
        }
        List<String> segments = name.tokenize('.')
        List<String> layers = layerSegment == null ? LAYER_SEGMENTS : [layerSegment.tokenize('.').first()]
        int layerIndex = segments.findIndexOf { String segment -> layers.contains(segment) }
        if (layerIndex <= 0) {
            return null
        }
        String root = ROOTS.get()
                .findAll { String candidate -> name.startsWith("${candidate}.") }
                .max { String candidate -> candidate.length() }
        if (root != null) {
            int rootSize = root.tokenize('.').size()
            if (layerIndex > rootSize) {
                return segments[rootSize]
            }
        }
        return segments[layerIndex - 1]
    }
}
