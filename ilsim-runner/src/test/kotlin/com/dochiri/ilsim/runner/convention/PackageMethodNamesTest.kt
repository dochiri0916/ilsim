package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class PackageMethodNamesTest {
    private fun ids(methods: List<String>, lang: String = "java"): Set<String> {
        val root: Path = Files.createTempDirectory("acc-h2")
        (1..6).forEach { i ->
            if (lang == "java") {
                val body = methods.joinToString("\n") { "    public void $it() {\n    }\n" }
                root.resolve("src/main/java/com/x/service/S$i.java").also { it.parent.createDirectories() }
                    .writeText("package com.x.service;\n\npublic class S$i {\n$body}\n")
            } else {
                val body = methods.joinToString("\n") { "    fun $it() {\n    }\n" }
                root.resolve("src/main/kotlin/com/x/service/S$i.kt").also { it.parent.createDirectories() }
                    .writeText("package com.x.service\n\nclass S$i {\n$body}\n")
            }
        }
        return ConventionMiner.mine(root, StackDetector.detect(root)).rules.map { it.id }.toSet()
    }

    @Test
    fun `철자만 겹치는 이름은 규칙이 되고 진짜 접근자는 제외된다 자바`() {
        val got = ids(listOf("issueToken", "isolate", "settle", "hashing", "withdraw", "getName", "setName", "isActive", "hasItems", "withName"))
        listOf("issueToken", "isolate", "settle", "hashing", "withdraw").forEach { assertTrue("package-method:service.$it" in got, "$it 규칙이 없다: $got") }
        listOf("getName", "setName", "isActive", "hasItems", "withName").forEach { assertTrue("package-method:service.$it" !in got, "$it 규칙이 나왔다: $got") }
    }

    @Test
    fun `코틀린도 같다`() {
        val got = ids(listOf("issueToken", "settle", "getName", "isActive"), "kotlin")
        assertTrue("package-method:service.issueToken" in got && "package-method:service.settle" in got, got.toString())
        assertTrue("package-method:service.getName" !in got && "package-method:service.isActive" !in got, got.toString())
    }

    @Test
    fun `정확히 같은 이름의 제외 대상은 계속 제외`() {
        val got = ids(listOf("toString", "equals", "hashCode", "values", "valueOf", "main", "copy"))
        assertTrue(got.none { it.startsWith("package-method:service.") }, got.toString())
    }
}
