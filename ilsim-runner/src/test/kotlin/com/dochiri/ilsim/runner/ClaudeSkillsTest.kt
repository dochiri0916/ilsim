package com.dochiri.ilsim.runner

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeSkillsTest {

    @Test
    fun `등록한 스킬만 설치하고 지운 스킬은 정리하며 다른 스킬은 건드리지 않는다`() {
        val root = Files.createTempDirectory("ilsim-skills")
        root.resolve("synced/keep").createDirectories()
        root.resolve("mine/SKILL.md").also { it.parent.createDirectories() }.writeText("직접 만든 스킬")

        ClaudeSkills.install(root, listOf(
            ClaudeSkillReport("review", mapOf("SKILL.md" to "---\ndescription: x\n---", "scripts/check.sh" to "echo ok")),
            ClaudeSkillReport("old", mapOf("SKILL.md" to "old")),
        ))
        assertEquals("echo ok", root.resolve("review/scripts/check.sh").readText())
        assertTrue(root.resolve("review/scripts/check.sh").toFile().canExecute())

        ClaudeSkills.install(root, listOf(ClaudeSkillReport("review", mapOf("SKILL.md" to "v2"))))
        assertFalse(root.resolve("old").exists())
        assertFalse(root.resolve("review/scripts").exists())
        assertEquals("v2", root.resolve("review/SKILL.md").readText())
        assertTrue(root.resolve("synced/keep").exists())
        assertTrue(root.resolve("mine/SKILL.md").exists())
    }

    @Test
    fun `스킬 폴더 밖으로 나가는 경로는 쓰지 않는다`() {
        val root = Files.createTempDirectory("ilsim-skills")
        assertFailsWith<IllegalArgumentException> {
            ClaudeSkills.install(root, listOf(ClaudeSkillReport("bad", mapOf("../../escape" to "x"))))
        }
        assertFalse(root.parent.resolve("escape").exists())
    }

    @Test
    fun `계정 디렉터리에 공통 설정을 쓸 때 스킬도 함께 설치한다`() {
        val previous = ClaudeAccounts.root
        val before = ClaudeAccounts.environment
        try {
            ClaudeAccounts.root = Files.createTempDirectory("ilsim-accounts")
            ClaudeAccounts.dir("work").createDirectories()
            ClaudeAccounts.environment = ClaudeEnvironmentReport("md", "{}", "v1", listOf(ClaudeSkillReport("review", mapOf("SKILL.md" to "s"))))
            ClaudeAccounts.applyEnvironment("work")
            assertEquals("s", ClaudeAccounts.dir("work").resolve("skills/review/SKILL.md").readText())
        } finally {
            ClaudeAccounts.root = previous
            ClaudeAccounts.environment = before
        }
    }
}
