package com.dochiri.ilsim.runner.architecture

object Introduced {
    fun filter(now: List<Violation>, before: List<Violation>, nowLines: (String) -> List<String>?, beforeLines: (String) -> List<String>?): List<Violation> {
        val current = memo(nowLines)
        val previous = memo(beforeLines)
        fun key(v: Violation, lines: List<String>?) = Triple(v.ruleId, v.file, lines?.getOrNull(v.line - 1)?.trim())
        val left = before.groupingBy { key(it, previous(it.file)) }.eachCount().toMutableMap()
        return now.filter { v ->
            val k = key(v, current(v.file))
            val n = left[k] ?: 0
            if (n > 0) left[k] = n - 1
            n == 0
        }
    }

    fun frozen(violations: List<Violation>): Map<String, Int> = violations.groupingBy { it.ruleId }.eachCount().toSortedMap()

    fun trend(now: Map<String, Int>, before: Map<String, Int>?): String {
        val total = now.values.sum()
        val head = "기존 위반 동결: 승인 규칙 ${now.size}개에 ${total}건(작업 제출 때는 새 위반만 막는다)"
        if (before == null) return head
        val resolved = before.entries.sumOf { (id, n) -> (n - (now[id] ?: 0)).coerceAtLeast(0) }
        val added = now.entries.sumOf { (id, n) -> (n - (before[id] ?: 0)).coerceAtLeast(0) }
        return "$head, 지난 분석 ${before.values.sum()}건에서 해소 ${resolved}건·늘어남 ${added}건"
    }

    private fun memo(read: (String) -> List<String>?): (String) -> List<String>? {
        val cache = HashMap<String, List<String>?>()
        return { path -> cache.getOrPut(path) { read(path) } }
    }
}
