package com.dochiri.ilsim.runner.architecture

internal data class Loc(val slice: String?, val unit: String, val rolePath: String)

internal class Locator(val layout: Layout) {
    private val root = layout.rootPrefix.split('.').filter { it.isNotEmpty() }

    fun locate(pkg: String): Loc {
        val segs = pkg.split('.').filter { it.isNotEmpty() }
        if (segs.size < root.size || segs.take(root.size) != root) return Loc(null, "(outside)", pkg)
        val rel = segs.drop(root.size)
        if (rel.isEmpty()) return Loc(null, "(root)", "")
        val c = rel[0]
        if (layout.mode == "SLICED") {
            if (c in layout.slices) {
                val layer = rel.getOrNull(1)
                return if (layer != null && layer in layout.layerNames) Loc(c, layer, rel.drop(1).joinToString("."))
                else Loc(c, "(slice-root)", rel.drop(1).joinToString("."))
            }
            return Loc(null, "shared:$c", rel.joinToString("."))
        }
        return Loc(null, c, rel.joinToString("."))
    }
}

internal object LayoutInference {

    fun infer(packages: Collection<String>): Layout {
        val all = packages.map { it.split('.').filter(String::isNotEmpty) }.filter { it.isNotEmpty() }
        if (all.isEmpty()) return Layout("", "UNKNOWN")
        var lcp = all.first()
        for (p in all) lcp = lcp.zip(p).takeWhile { (a, b) -> a == b }.map { it.first }
        val root = lcp.joinToString(".")

        val grandChildren = linkedMapOf<String, MutableSet<String>>()
        all.map { it.drop(lcp.size) }.filter { it.isNotEmpty() }.forEach { rel ->
            val set = grandChildren.getOrPut(rel[0]) { linkedSetOf() }
            rel.getOrNull(1)?.let(set::add)
        }

        val counts = grandChildren.values.flatten().groupingBy { it }.eachCount()
        val layerNames = counts.filterValues { it >= 2 }.keys
        val slices = grandChildren.filter { (_, names) -> names.any { it in layerNames } }.keys
        return if (slices.size >= 2) {
            Layout(root, "SLICED", slices.sorted(), layerNames.sorted())
        } else {
            Layout(root, if (grandChildren.isEmpty()) "UNKNOWN" else "FLAT")
        }
    }
}

internal object Graphs {

    fun cycles(edges: Collection<Pair<String, String>>): List<Set<String>> {
        val adj = edges.groupBy({ it.first }, { it.second })
        val nodes = (edges.map { it.first } + edges.map { it.second }).toSet()
        var counter = 0
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<Set<String>>()

        fun visit(v: String) {
            index[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack += v
            for (w in adj[v].orEmpty()) {
                if (w !in index) { visit(w); low[v] = minOf(low[v]!!, low[w]!!) }
                else if (w in onStack) low[v] = minOf(low[v]!!, index[w]!!)
            }
            if (low[v] == index[v]) {
                val comp = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeLast(); onStack -= w; comp += w
                    if (w == v) break
                }
                if (comp.size > 1) result += comp
            }
        }
        nodes.sorted().forEach { if (it !in index) visit(it) }
        return result
    }

    fun order(nodes: Collection<String>, edges: Collection<Pair<String, String>>): List<String>? {
        val indeg = nodes.associateWith { 0 }.toMutableMap()
        edges.forEach { (_, to) -> indeg[to] = (indeg[to] ?: 0) + 1 }
        val ready = ArrayDeque(indeg.filterValues { it == 0 }.keys.sorted())
        val out = mutableListOf<String>()
        val remaining = edges.toMutableList()
        while (ready.isNotEmpty()) {
            val n = ready.removeFirst()
            out += n
            val next = remaining.filter { it.first == n }
            remaining.removeAll(next.toSet())
            next.forEach { (_, to) ->
                indeg[to] = indeg[to]!! - 1
                if (indeg[to] == 0) ready.addLast(to)
            }
        }
        return if (out.size == nodes.size) out else null
    }

    fun levels(nodes: Collection<String>, edges: Collection<Pair<String, String>>): List<List<String>>? {
        val order = order(nodes, edges) ?: return null
        val level = nodes.associateWith { 0 }.toMutableMap()
        for (n in order) edges.filter { it.first == n }.forEach { (_, to) -> level[to] = maxOf(level[to]!!, level[n]!! + 1) }
        return level.entries.groupBy({ it.value }, { it.key }).toSortedMap().values.map { it.sorted() }
    }
}
