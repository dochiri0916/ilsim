package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.architecture.ArchitectureMiner
import com.dochiri.ilsim.runner.architecture.RuleApproval
import com.dochiri.ilsim.runner.convention.CodeText
import com.dochiri.ilsim.runner.convention.ConventionMiner
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.convention.Facts
import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecommendationsTest {

    private fun kt(pkg: String, name: String, vararg imports: String): Pair<String, String> =
        "${pkg.replace('.', '/')}/$name.kt" to buildString {
            appendLine("package $pkg")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("class $name")
        }

    private fun project(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("ilsim-recommend")
        files.forEach { (path, text) ->
            val f = root.resolve("src/main/kotlin/$path")
            f.parent.createDirectories()
            f.writeText(text)
        }
        return root
    }

    private fun hexagonal(domainImportsAdapter: Boolean) = project(
        kt("com.acme.order.domain", "Order", *(if (domainImportsAdapter) arrayOf("com.acme.order.adapter.OrderEntity") else emptyArray())),
        kt("com.acme.order.domain", "OrderLine"),
        kt("com.acme.order.application", "PlaceOrder", "com.acme.order.domain.Order"),
        kt("com.acme.order.adapter", "OrderEntity", "com.acme.order.domain.Order", "jakarta.persistence.Entity"),
        kt("com.acme.billing.domain", "Invoice"),
        kt("com.acme.billing.application", "Charge", "com.acme.billing.domain.Invoice"),
        kt("com.acme.billing.adapter", "InvoiceEntity", "com.acme.billing.domain.Invoice", "jakarta.persistence.Entity"),
    )

    @Test
    fun `도메인이 어댑터를 알면 의존 방향 추천에 위반 근거와 함께 REVIEW 로 제안한다`() {
        val root = hexagonal(domainImportsAdapter = true)
        val profile = StackDetector.detect(root)
        val mined = ArchitectureMiner.mine(root, profile)
        val rules = ArchitectureRecommender.recommend(root, profile, mined).associateBy { it.id }

        val edge = assertNotNull(rules["recommend:forbid-edge:domain>adapter"])
        assertEquals("REVIEW", edge.proposedSeverity)
        assertEquals(1, edge.evidence.total - edge.evidence.compliant)
        assertTrue(edge.evidence.examples.single().contains("Order.kt"))
        assertEquals("클린 아키텍처·헥사고날", edge.recommendation?.principle)
        assertFalse(edge.approved)
        assertTrue(edge.rejected)
        assertEquals("auto", edge.decidedBy)
        assertTrue(rules.values.all { it.rejected == (it.evidence.total != it.evidence.compliant) })
    }

    @Test
    fun `이미 지키는 추천은 BLOCK 으로 제안하고 채굴된 같은 규칙은 다시 추천하지 않는다`() {
        val root = hexagonal(domainImportsAdapter = false)
        val profile = StackDetector.detect(root)
        val mined = ArchitectureMiner.mine(root, profile)
        val recommended = ArchitectureRecommender.recommend(root, profile, mined)

        val minedKeys = mined.rules.map { (if (it.kind == "absent-edge") "forbid-edge" else it.kind) to it.params }.toSet()
        assertTrue(recommended.none { (it.kind to it.params) in minedKeys })
        assertTrue(recommended.all { it.proposedSeverity == "BLOCK" })
        assertTrue(recommended.all { Recommendations.isRecommended(it.id) && it.recommendation != null })
    }

    @Test
    fun `다시 분석해도 추천 규칙의 승인은 유지되고 위반이 사라지면 BLOCK 으로 강화된다`() {
        val before = hexagonal(domainImportsAdapter = true)
        val profile = StackDetector.detect(before)
        val mined = ArchitectureMiner.mine(before, profile)
        val first = mined.copy(rules = mined.rules + ArchitectureRecommender.recommend(before, profile, mined))
        val approved = RuleApproval.approve(first, listOf("recommend:forbid-edge:domain>adapter"))

        val after = hexagonal(domainImportsAdapter = false)
        val minedAgain = ArchitectureMiner.mine(after, StackDetector.detect(after))
        val next = RuleApproval.carryOver(approved, minedAgain.copy(rules = minedAgain.rules + ArchitectureRecommender.recommend(after, StackDetector.detect(after), minedAgain)))

        val rule = next.rules.first { it.id == "recommend:forbid-edge:domain>adapter" }
        assertTrue(rule.approved)
        assertEquals("BLOCK", rule.proposedSeverity)
    }

    private fun facts(vararg files: Pair<String, String>) =
        Facts(files.map { (path, text) -> FactScanner.parse(path, "kt", false, text.lines())!! })

    @Test
    fun `컨벤션 추천은 주석·도메인 캡슐화·식별자 값 객체·함수 길이·중첩을 잰다`() {
        val longBody = (1..35).joinToString("\n") { "        val v$it = $it" }
        val facts = facts(
            "Order.kt" to """
                package com.acme.order.domain

                class Order(val id: String, var status: String) {
                    var note: String = ""
                    fun place() {
                        var count = 0
                        count++
                    }
                }
            """.trimIndent(),
            "OrderId.kt" to """
                package com.acme.order.domain

                class Line(val orderId: OrderId)
            """.trimIndent(),
            "Service.kt" to """
                package com.acme.order.application

                // 설명 주석
                class Service {
                    fun long() {
                $longBody
                    }
                    fun nested(xs: List<Int>) {
                        xs.forEach { x ->
                            if (x > 0) {
                                if (x > 1) { println("//") }
                            }
                        }
                    }
                }
            """.trimIndent(),
        )
        val rules = ConventionRecommender.recommend(facts, ConventionMiner.mine(facts, "acme")).associateBy { it.kind }

        assertEquals(2 to 3, rules.getValue("no-comments").evidence.let { it.compliant to it.total })
        assertEquals(1 to 2, rules.getValue("domain-encapsulation").evidence.let { it.compliant to it.total })
        assertEquals(1 to 2, rules.getValue("identifier-value-object").evidence.let { it.compliant to it.total })
        assertTrue(rules.getValue("max-function-lines").evidence.examples.single().endsWith("Service.kt:5"))
        assertTrue(rules.getValue("max-nesting").evidence.examples.single().endsWith("Service.kt:42"))
        assertEquals("REVIEW", rules.getValue("no-comments").severity)
    }

    @Test
    fun `코드 보기는 문자열 속 주석 기호를 주석으로 세지 않는다`() {
        val view = CodeText.scrub(listOf("val a = \"http://x\" // 끝", "/* 시작", "끝 */ val b = '{'", "val c = \"\"\"", "// raw", "\"\"\""))
        assertEquals(listOf(0, 1, 2), view.commentLines)
        assertFalse(view.code[2].contains("{"))
    }

    @Test
    fun `도메인에서 setter 를 하나라도 쓰면 도메인 캡슐화 추천을 자동으로 끄고 쓰지 않으면 켜 둔다`() {
        fun domain(setter: Boolean) = Files.createTempDirectory("ilsim-setter").also { root ->
            (1..3).forEach { n ->
                val f = root.resolve("src/main/java/com/x/domain/D$n.java")
                f.parent.createDirectories()
                f.writeText("package com.x.domain;\n\npublic class D$n {\n" + (if (setter && n == 1) "    public void setName(String n) {}\n" else "") + "}\n")
            }
        }
        fun recommended(root: Path): com.dochiri.ilsim.runner.convention.ConventionRule {
            val profile = StackDetector.detect(root)
            val mined = ConventionMiner.mine(root, profile)
            return ConventionRecommender.recommend(root, profile, mined).first { it.id == "recommend:domain-encapsulation" }
        }
        val used = recommended(domain(true))
        assertTrue(used.rejected)
        assertEquals("auto", used.decidedBy)
        val clean = recommended(domain(false))
        assertFalse(clean.rejected)
        assertTrue(clean.approved)
        assertEquals("auto", clean.decidedBy)
    }

    @Test
    fun `일급 컬렉션은 컬렉션과 다른 필드를 함께 둔 도메인 클래스만 위반으로 잡는다`() {
        fun probe(files: Map<String, String>): List<com.dochiri.ilsim.runner.convention.Sample> {
            val root = Files.createTempDirectory("ilsim-fcc")
            files.forEach { (n, body) ->
                val ext = n.substringAfterLast('.')
                val f = root.resolve("src/main/${if (ext == "kt") "kotlin" else "java"}/com/x/domain/$n")
                f.parent.createDirectories()
                f.writeText(body)
            }
            val facts = FactScanner.scan(root, StackDetector.detect(root).modules)
            return com.dochiri.ilsim.runner.convention.Probes.byKind("first-class-collection")!!.samples(facts, emptyMap())
        }
        val java = probe(mapOf(
            "Lines.java" to "package com.x.domain;\n\npublic class Lines {\n    private final List<String> items = new ArrayList<>();\n    public int size() { return items.size(); }\n}\n",
            "Order.java" to "package com.x.domain;\n\npublic class Order {\n    @Id\n    private Long id;\n    private List<String> lines;\n    private static final List<String> NAMES = List.of();\n}\n",
            "Plain.java" to "package com.x.domain;\n\npublic class Plain {\n    private String name;\n}\n",
        ))
        assertEquals(1, java.count { !it.ok })
        assertTrue(java.single { !it.ok }.file.endsWith("Order.java"))
        val kotlin = probe(mapOf(
            "Items.kt" to "package com.x.domain\n\nclass Items(private val values: List<String>) {\n    fun first(): String {\n        val local: List<String> = values\n        return local.first()\n    }\n}\n",
            "Cart.kt" to "package com.x.domain\n\nclass Cart(val owner: String, val items: MutableList<String>)\n",
        ))
        assertEquals(listOf("Cart.kt"), kotlin.filter { !it.ok }.map { it.file.substringAfterLast('/') })
    }
}
