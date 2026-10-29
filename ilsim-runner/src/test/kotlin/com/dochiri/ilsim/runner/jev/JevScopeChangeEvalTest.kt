package com.dochiri.ilsim.runner.jev

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JevScopeChangeEvalTest {
    private val corpus = Path.of("corpus/jev-scope-change")

    @Test
    fun `평가 사례의 범위 밖 표시는 모두 변경 묶음 하나 이상에 걸린다`() {
        val samples = JevScopeChangeEval.load(corpus)
        val labeled = samples.flatMap { s ->
            s.diffs.flatMap { (d, text) ->
                val changes = ScopeCheck.changes(text)
                assertTrue(d.outside.all { o -> changes.any { o in it.changed } }, "${s.name}/${d.id}")
                assertTrue(d.outsideFiles.all { f -> changes.any { it.path == f } }, "${s.name}/${d.id}")
                changes.map { JevScopeChangeEval.inside(d, it) }
            }
        }

        assertEquals(23, samples.size)
        assertEquals(110, labeled.size)
        assertEquals(30, labeled.count { !it })
        assertTrue(samples.all { s -> s.stage in setOf("TEST_CODE", "IMPLEMENTATION") && s.spec.isNotBlank() })
    }
}
