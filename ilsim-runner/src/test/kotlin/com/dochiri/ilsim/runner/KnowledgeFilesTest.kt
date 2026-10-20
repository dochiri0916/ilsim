package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Layout
import com.dochiri.ilsim.runner.architecture.RuleApproval
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class KnowledgeFilesTest {

    @Test
    fun `잠금 안에서 읽고 고치고 쓰면 동시에 고쳐도 변경을 잃지 않는다`() {
        val dir = Files.createTempDirectory("knowledge")
        val f = dir.resolve("count.txt")
        KnowledgeFiles.write(f, "0")
        val pool = Executors.newFixedThreadPool(4)
        repeat(200) {
            pool.submit {
                KnowledgeFiles.locked(dir) { KnowledgeFiles.write(f, (f.readText().toInt() + 1).toString()) }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals("200", f.readText())
        assertEquals(listOf("count.txt"), Files.list(dir).map { it.fileName.toString() }.filter { it != KnowledgeFiles.LOCK }.toList(), "임시 파일이 남지 않는다")
    }

    @Test
    fun `같은 스레드가 다시 잠가도 막히지 않는다`() {
        val dir = Files.createTempDirectory("knowledge")
        assertEquals(1, KnowledgeFiles.locked(dir) { KnowledgeFiles.locked(dir) { 1 } })
    }

    @Test
    fun `AI 검토 중 사용자가 내린 결정은 검토 결과를 저장할 때 유지된다`() {
        fun rule(id: String) = ArchRule(id, "leaf-unit", "d", mapOf("unit" to listOf(id)), "REVIEW", Evidence(1, 1))
        val model = ArchitectureModel(
            "p", 3, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(rule("a"), rule("b")), emptyList(),
        )
        val reviewed = model.copy(rules = model.rules.map { it.copy(approved = true, decidedBy = "ai-review") })
        val saved = RuleApproval.reject(model, listOf("a"))

        val merged = RuleApproval.keepUserDecisions(saved, reviewed)
        assertEquals(listOf(false to true, true to false), merged.rules.map { it.approved to it.rejected })
        assertEquals(listOf("user", "ai-review"), merged.rules.map { it.decidedBy })
    }
}
