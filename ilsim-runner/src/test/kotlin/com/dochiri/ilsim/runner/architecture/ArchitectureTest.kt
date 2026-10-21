package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureTest {

    private fun kt(pkg: String, name: String, vararg imports: String): Pair<String, String> =
        "${pkg.replace('.', '/')}/$name.kt" to buildString {
            appendLine("package $pkg")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("class $name")
        }

    private fun project(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("ilsim-arch")
        files.forEach { (path, text) ->
            val f = root.resolve("src/main/kotlin/$path")
            f.parent.createDirectories()
            f.writeText(text)
        }
        return root
    }

    private fun mine(root: Path) = ArchitectureMiner.mine(root, StackDetector.detect(root))
    private fun check(root: Path, model: ArchitectureModel) = ArchitectureChecker.check(root, StackDetector.detect(root), model)

    private fun slicedFiles() = arrayOf(
        kt("com.acme.billing.domain", "Invoice"), kt("com.acme.billing.domain", "Receipt"), kt("com.acme.billing.domain", "Line"),
        kt("com.acme.catalog.domain", "Item"), kt("com.acme.catalog.domain", "Price"), kt("com.acme.catalog.domain", "Sku"),
        kt("com.acme.billing.service", "InvoiceService", "com.acme.billing.domain.Invoice", "org.springframework.stereotype.Service"),
        kt("com.acme.billing.service", "Pricing", "com.acme.catalog.domain.Item", "org.springframework.stereotype.Service"),
        kt("com.acme.billing.service", "Taxes", "com.acme.billing.domain.Line", "org.springframework.stereotype.Service"),
        kt("com.acme.catalog.service", "ItemService", "com.acme.catalog.domain.Item", "org.springframework.stereotype.Service"),
        kt("com.acme.catalog.service", "PriceService", "com.acme.catalog.domain.Price", "org.springframework.stereotype.Service"),
        kt("com.acme.catalog.service", "SkuService", "com.acme.catalog.domain.Sku", "org.springframework.stereotype.Service"),
        kt("com.acme.billing.web", "InvoiceController", "com.acme.billing.service.InvoiceService", "org.springframework.web.bind"),
        kt("com.acme.billing.web", "ReceiptController", "com.acme.billing.service.Taxes", "org.springframework.web.bind"),
        kt("com.acme.billing.web", "LineController", "com.acme.billing.service.Pricing", "org.springframework.web.bind"),
        kt("com.acme.catalog.web", "ItemController", "com.acme.catalog.service.ItemService", "org.springframework.web.bind"),
        kt("com.acme.catalog.web", "PriceController", "com.acme.catalog.service.PriceService", "org.springframework.web.bind"),
        kt("com.acme.catalog.web", "SkuController", "com.acme.catalog.service.SkuService", "org.springframework.web.bind"),
    )

    private fun flatFiles() = arrayOf(
        kt("com.shop.model", "Order"), kt("com.shop.model", "Customer"), kt("com.shop.model", "Product"),
        kt("com.shop.repository", "OrderRepo", "com.shop.model.Order"),
        kt("com.shop.repository", "CustomerRepo", "com.shop.model.Customer"),
        kt("com.shop.repository", "ProductRepo", "com.shop.model.Product"),
        kt("com.shop.service", "OrderService", "com.shop.repository.OrderRepo", "com.shop.model.Order"),
        kt("com.shop.service", "CustomerService", "com.shop.repository.CustomerRepo"),
        kt("com.shop.service", "ProductService", "com.shop.repository.ProductRepo"),
        kt("com.shop.controller", "OrderController", "com.shop.service.OrderService"),
        kt("com.shop.controller", "CustomerController", "com.shop.service.CustomerService"),
        kt("com.shop.controller", "ProductController", "com.shop.service.ProductService"),
    )

    @Test
    fun `한 단계 더 중첩된 슬라이스와 레이어가 없는 모듈을 구분한다`() {
        val layers = { bc: String -> arrayOf(
            kt("com.acme.$bc.domain", "D1"), kt("com.acme.$bc.application", "A1", "com.acme.$bc.domain.D1"),
        ) }
        val root = project(
            *layers("order"), *layers("shop.cart"), *layers("shop.item"),
            kt("com.acme.config", "Settings"), kt("com.acme.config.props", "Props"),
        )
        val m = mine(root)
        assertEquals(listOf("order", "shop.cart", "shop.item"), m.layout.slices)
        assertEquals(listOf("application", "domain"), m.layout.layerNames)
        val locator = Locator(m.layout)
        assertEquals("shop.cart", locator.locate("com.acme.shop.cart.domain").slice)
        assertEquals("domain", locator.locate("com.acme.shop.cart.domain").unit)
        assertEquals(null, locator.locate("com.acme.config").slice)
    }

    @Test
    fun `한 모듈만 전체 레이어를 가지면 그 모듈의 레이어를 다른 어댑터 모듈에도 적용한다`() {
        val m = mine(project(
            kt("com.acme.security.adapter.web", "SecurityController", "com.acme.security.application.Login"),
            kt("com.acme.security.application", "Login", "com.acme.security.domain.Account"),
            kt("com.acme.security.domain", "Account"),
            kt("com.acme.jpa.adapter.persistence", "JpaConfig"),
            kt("com.acme.time.adapter.clock", "ClockConfig"),
            kt("com.acme.errors.adapter.web", "ErrorAdvice"),
        ))
        assertEquals(listOf("errors", "jpa", "security", "time"), m.layout.slices)
        assertEquals(listOf("adapter", "application", "domain"), m.layout.layerNames)
        assertEquals("domain", Locator(m.layout).locate("com.acme.security.domain").unit)
    }

    @Test
    fun `다른 슬라이스를 쓰지 않고 여럿이 쓰는 슬라이스는 공유 커널로 본다`() {
        val slice = { n: String -> arrayOf(
            kt("com.acme.$n.domain", "${n}Entity", "com.acme.core.domain.Money"),
            kt("com.acme.$n.application", "${n}Service", "com.acme.$n.domain.${n}Entity"),
        ) }
        val m = mine(project(*slice("order"), *slice("cart"), *slice("member"),
            kt("com.acme.core.domain", "Money"), kt("com.acme.core.application", "Clock")))
        assertEquals(listOf("cart", "member", "order"), m.layout.slices)
        assertEquals(listOf("core"), m.layout.shared)
        assertEquals("shared:core", Locator(m.layout).locate("com.acme.core.domain").unit)
        assertTrue(m.rules.any { it.id == "shared-independence" })
    }

    @Test
    fun `절반만 쓰는 슬라이스는 공유 커널이 아니다`() {
        val m = mine(project(
            kt("com.acme.order.domain", "OrderEntity", "com.acme.core.domain.Money"),
            kt("com.acme.order.application", "OrderService", "com.acme.order.domain.OrderEntity"),
            kt("com.acme.cart.domain", "CartEntity", "com.acme.core.domain.Money"),
            kt("com.acme.cart.application", "CartService", "com.acme.cart.domain.CartEntity"),
            kt("com.acme.member.domain", "MemberEntity"),
            kt("com.acme.member.application", "MemberService", "com.acme.member.domain.MemberEntity"),
            kt("com.acme.core.domain", "Money"), kt("com.acme.core.application", "Clock"),
        ))
        assertTrue(m.layout.shared.isEmpty())
    }

    @Test
    fun `계층이 먼저인 구조는 기능 이름을 레이어로 오인하지 않고 단위로 본다`() {
        val m = mine(project(
            kt("com.acme.adapter.out.outbox", "A1", "com.acme.application.outbox.P1"),
            kt("com.acme.adapter.out.storage", "A2", "com.acme.application.storage.P2"),
            kt("com.acme.adapter.in.outbox", "A3", "com.acme.application.outbox.P1"),
            kt("com.acme.application.outbox", "P1", "com.acme.domain.D"),
            kt("com.acme.application.storage", "P2"),
            kt("com.acme.domain", "D"), kt("com.acme.domain", "D2"), kt("com.acme.domain", "D3"),
            kt("com.acme.adapter.in.web", "C1", "com.acme.domain.D"), kt("com.acme.adapter.in.web", "C2"), kt("com.acme.adapter.in.web", "C3"),
            kt("com.acme.adapter.out.persistence", "R1", "com.acme.domain.D"), kt("com.acme.adapter.out.persistence", "R2"),
        ))
        assertEquals("FLAT", m.layout.mode)
        assertTrue(m.layerLevels.flatten().containsAll(listOf("adapter", "application", "domain")))
    }

    @Test
    fun `반복 구조를 이름 템플릿 없이 슬라이스와 레이어로 추론한다`() {
        val m = mine(project(*slicedFiles()))
        assertEquals("SLICED", m.layout.mode)
        assertEquals("com.acme", m.layout.rootPrefix)
        assertEquals(listOf("billing", "catalog"), m.layout.slices)
        assertEquals(listOf("domain", "service", "web"), m.layout.layerNames)
        assertEquals(listOf(listOf("web"), listOf("service"), listOf("domain")), m.layerLevels)
        val ids = m.rules.map { it.id }
        assertTrue("leaf-unit:domain" in ids)
        assertTrue("pure-layer:domain" in ids)
        assertTrue("forbid-edge:service>web" in ids)
        assertTrue("confinement:org.springframework.web" in ids)
        assertTrue("cross-slice-targets" in ids)
        assertEquals(listOf("service>domain"), m.rules.first { it.id == "cross-slice-targets" }.params["allowed"])
    }

    @Test
    fun `생성한 규칙은 원본 코드에서 위반이 없다`() {
        listOf(slicedFiles(), flatFiles()).forEach { files ->
            val root = project(*files)
            assertEquals(emptyList(), check(root, mine(root)))
        }
    }

    @Test
    fun `평평한 구조는 다른 규칙이 나온다`() {
        val m = mine(project(*flatFiles()))
        assertEquals("FLAT", m.layout.mode)
        assertEquals(listOf(listOf("controller"), listOf("service"), listOf("repository"), listOf("model")), m.layerLevels)
        val ids = m.rules.map { it.id }
        assertTrue("forbid-edge:service>controller" in ids)
        assertTrue("forbid-edge:repository>service" in ids)
        assertTrue("leaf-unit:model" in ids)
        assertTrue("absent-edge:controller>repository" in ids)
        assertEquals("REVIEW", m.rules.first { it.id == "absent-edge:controller>repository" }.proposedSeverity)
        assertFalse(ids.any { it.contains("billing") || it.contains("domain") })
    }

    @Test
    fun `위반을 주입하면 해당 규칙이 잡는다`() {
        val files = slicedFiles().toMutableList()
        val clean = project(*files.toTypedArray())
        val model = mine(clean)

        val bad = project(
            *files.toTypedArray(),
            kt("com.acme.billing.domain", "Leak", "com.acme.billing.service.InvoiceService", "org.springframework.web.bind"),
            kt("com.acme.catalog.service", "Rogue", "org.springframework.web.bind", "com.acme.billing.web.InvoiceController"),
        )
        val violations = check(bad, model)
        val byRule = violations.groupBy { it.ruleId }
        assertTrue("leaf-unit:domain" in byRule, "domain 이 다른 단위에 의존")
        assertTrue("pure-layer:domain" in byRule, "domain 이 외부 라이브러리 import")
        assertTrue("confinement:org.springframework.web" in byRule, "web 밖에서 web 라이브러리 사용")
        assertTrue("cross-slice-targets" in byRule, "관측되지 않은 슬라이스 간 의존")
        assertTrue(violations.all { it.line > 0 && it.file.endsWith(".kt") })
    }

    @Test
    fun `다른 프로젝트의 규칙 모델은 거부한다`() {
        val sliced = mine(project(*slicedFiles()))
        val flat = project(*flatFiles())
        val e = assertFailsWith<IllegalArgumentException> { check(flat, sliced) }
        assertTrue(e.message!!.contains("다른 프로젝트"))
    }

    @Test
    fun `이미 있는 슬라이스 순환은 순환 금지 규칙을 만들지 않고 알린다`() {
        val m = mine(project(
            kt("com.x.a.domain", "A1"), kt("com.x.a.domain", "A2"), kt("com.x.a.domain", "A3"),
            kt("com.x.b.domain", "B1"), kt("com.x.b.domain", "B2"), kt("com.x.b.domain", "B3"),
            kt("com.x.a.service", "AS", "com.x.b.domain.B1"),
            kt("com.x.b.service", "BS", "com.x.a.domain.A1"),
        ))
        assertFalse(m.rules.any { it.id == "no-cycles:slice" })
        assertTrue(m.notes.any { it.contains("슬라이스 간 순환") })
    }

    @Test
    fun `코틀린 백틱 패키지와 별칭 와일드카드 import 를 해석한다`() {
        val root = Files.createTempDirectory("ilsim-bt")
        val f = root.resolve("src/main/kotlin/A.kt")
        f.parent.createDirectories()
        f.writeText(
            """
            package com.q.`in`.web

            import com.q.out.Port as P
            import com.q.out.*
            import org.springframework.web.bind.annotation.GetMapping
            import java.util.UUID

            class A
            """.trimIndent(),
        )
        val g = root.resolve("src/main/kotlin/B.kt")
        g.writeText("package com.q.out\n\nclass Port\n")
        val files = SourceScanner.scan(root, StackDetector.detect(root).modules)
        val a = files.first { it.path.endsWith("A.kt") }
        assertEquals("com.q.in.web", a.pkg)
        assertEquals(listOf("com.q.out.Port", "com.q.out", "org.springframework.web.bind.annotation.GetMapping", "java.util.UUID"), a.imports.map { it.name })
    }

    @Test
    fun `패키지 구조가 없으면 UNKNOWN 이며 규칙을 만들지 않는다`() {
        val m = mine(project(kt("single", "Only")))
        assertEquals("UNKNOWN", m.layout.mode)
        assertEquals(emptyList(), m.rules)
    }
    private fun mesh(vararg pkgs: String) = pkgs.flatMapIndexed { i, p ->
        val other = pkgs[(i + 1) % pkgs.size]
        (1..3).map { n -> kt("com.old.$p", "C$n", "com.old.$other.C1") }
    }

    @Test
    fun `서로 뒤엉킨 레거시 구조는 검증 비적용으로 판정한다`() {
        val m = mine(project(*mesh("a", "b", "c").toTypedArray()))
        assertEquals(Applicability.NOT_APPLICABLE, m.applicability.status)
        assertTrue(m.applicability.reasons.any { it.contains("순환") })
    }

    @Test
    fun `일관된 구조가 깔끔하면 전체 적용으로 판정한다`() {
        assertEquals(Applicability.APPLICABLE, mine(project(*slicedFiles())).applicability.status)
        assertEquals(Applicability.APPLICABLE, mine(project(*flatFiles())).applicability.status)
    }

    @Test
    fun `일부만 순환이면 부분 적용하고 순환 단위는 검증에서 제외한다`() {
        val base = flatFiles().toList()
        val tangled = mesh("x", "y").map { (p, t) -> p.replace("com/old", "com/shop") to t.replace("com.old", "com.shop") }
        val m = mine(project(*(base + tangled).toTypedArray()))
        assertEquals(Applicability.PARTIAL, m.applicability.status)
        assertEquals(listOf("x", "y"), m.applicability.unverifiedUnits)
        val bad = project(*(base + tangled).toTypedArray(), kt("com.shop.x", "Z", "com.shop.controller.OrderController"))
        assertTrue(check(bad, m).none { it.file.contains("/x/") })
    }
}
