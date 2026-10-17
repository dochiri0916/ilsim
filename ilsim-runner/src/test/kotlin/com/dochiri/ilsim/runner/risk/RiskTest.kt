package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskTest {

    private fun project(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("ilsim-risk")
        files.forEach { (path, text) -> root.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
        return root
    }

    private fun scan(root: Path, config: RiskConfig = RiskConfig(), only: Set<String>? = null) =
        RiskScanner.scan(root, StackDetector.detect(root), config, only)!!

    private val kotlinRisky = "src/main/kotlin/com/x/OrderService.kt" to """
        package com.x

        import org.springframework.stereotype.Service
        import org.springframework.transaction.annotation.Transactional

        @Service
        class OrderService(
            private val orderRepository: OrderRepository,
            private val kafkaTemplate: KafkaTemplate<String, String>,
            private val client: PaymentClient,
        ) {
            private var counter = 0
            private val cache = mutableMapOf<String, String>()
            @Volatile private var instance: String? = null

            @Transactional
            fun place() {
                orderRepository.save(1)
                kafkaTemplate.send("topic", "x")
                try {
                    client.charge()
                } catch (e: Exception) {
                    println("ignored")
                }
            }

            @Transactional
            private fun hidden() = Unit

            fun entry() {
                place()
                this.place()
            }

            fun batch() {
                orderRepository.save(1)
                orderRepository.deleteById(2)
            }

            fun get(): String {
                if (instance == null) {
                    synchronized(this) {
                        if (instance == null) {
                            instance = "x"
                        }
                    }
                }
                return instance!!
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun `Kotlin 에서 트랜잭션 위험을 후보로 찾는다`() {
        val findings = scan(project(kotlinRisky)).groupBy { it.ruleId }
        assertTrue("TX-001" in findings && findings["TX-001"]!!.size >= 2, "kafka 와 client 호출")
        assertTrue(findings["TX-001"]!!.any { it.message.contains("outbox") })
        assertTrue("TX-002" in findings, "자기 호출")
        assertEquals("BLOCK", findings.getValue("TX-003").single().severity)
        assertEquals("TX-003", findings.getValue("TX-003").single().ruleId)
        assertTrue("TX-004" in findings, "삼킨 예외")
        assertEquals(1, findings.getValue("TX-005").size)
        assertTrue(findings.getValue("CON-001").map { it.message }.any { it.contains("counter") })
        assertTrue(findings.getValue("CON-001").map { it.message }.any { it.contains("cache") })
        assertTrue("CON-002" !in findings, "volatile 이면 DCL 문제가 아니다")
    }

    @Test
    fun `volatile 이 없는 DCL 과 해제 누락 Lock 을 Java 에서도 찾는다`() {
        val java = "src/main/java/com/x/Holder.java" to """
            package com.x;

            import java.util.concurrent.locks.ReentrantLock;

            public class Holder {
                private static Holder instance;
                private final ReentrantLock lock = new ReentrantLock();

                public static Holder get() {
                    if (instance == null) {
                        synchronized (Holder.class) {
                            if (instance == null) {
                                instance = new Holder();
                            }
                        }
                    }
                    return instance;
                }

                public void leak() {
                    lock.lock();
                    work();
                    lock.unlock();
                }

                public void safe() {
                    lock.lock();
                    try {
                        work();
                    } finally {
                        lock.unlock();
                    }
                }

                private void work() {}
            }
        """.trimIndent() + "\n"
        val f = scan(project(java))
        assertTrue(f.map { it.ruleId }.toSet().containsAll(setOf("CON-002", "CON-003")))
        assertTrue(f.filter { it.ruleId in setOf("CON-002", "CON-003") }.all { it.severity == "BLOCK" })
        assertEquals(1, f.count { it.ruleId == "CON-003" }, "finally 에서 해제하는 safe 는 제외")
    }

    @Test
    fun `프로젝트 설정으로 심각도를 바꾸고 바뀐 파일로 범위를 좁힌다`() {
        val root = project(kotlinRisky, "src/main/kotlin/com/x/Other.kt" to "package com.x\n\nclass Other\n")
        assertTrue(scan(root, RiskConfig(mapOf("TX-003" to "OFF"))).none { it.ruleId == "TX-003" })
        assertEquals("BLOCK", scan(root, RiskConfig(mapOf("TX-001" to "BLOCK"))).first { it.ruleId == "TX-001" }.severity)
        assertTrue(scan(root, only = setOf("src/main/kotlin/com/x/Other.kt")).isEmpty())
    }

    @Test
    fun `깨끗한 코드는 후보가 없고 소스가 없으면 null 이다`() {
        val clean = "src/main/kotlin/com/x/Calc.kt" to "package com.x\n\nclass Calc {\n    fun add(a: Int, b: Int) = a + b\n}\n"
        assertEquals(emptyList(), scan(project(clean)))
        val empty = project("README.md" to "x")
        assertNull(RiskScanner.scan(empty, StackDetector.detect(empty)))
    }

    @Test
    fun `알 수 없는 규칙과 잘못된 심각도는 거부한다`() {
        val store = RiskScanner.Store(com.dochiri.ilsim.runner.ConfigStore(Files.createTempDirectory("ilsim-home").resolve(".ilsim/runner.json")))
        assertFailsWith<IllegalArgumentException> { store.set("p", "NOPE", "BLOCK") }
        assertFailsWith<IllegalArgumentException> { store.set("p", "TX-001", "LOUD") }
        store.set("p", "TX-001", "OFF")
        assertEquals("OFF", store.load("p").overrides["TX-001"])
    }

    private fun ids(vararg files: Pair<String, String>) = scan(project(*files)).groupBy { it.ruleId }

    @Test
    fun `멱등성 분산 복원력 위험을 찾고 보호 장치가 있으면 건너뛴다`() {
        val risky = "src/main/kotlin/com/x/Handler.kt" to """
            package com.x

            @Service
            class Handler(
                private val orderRepository: OrderRepository,
                private val kafkaTemplate: KafkaTemplate<String, String>,
                private val paymentClient: PaymentClient,
            ) {
                @KafkaListener(topics = ["a"])
                fun on(msg: String) {
                    orderRepository.save(msg)
                    kafkaTemplate.send("t", msg)
                }

                fun register(name: String) {
                    if (orderRepository.existsByName(name)) return
                    orderRepository.save(name)
                }

                @Retryable
                fun pay() = paymentClient.post("x")

                @Scheduled(fixedRate = 1000)
                fun tick() = Unit
            }

            class Factory {
                fun make() = RestTemplate()
            }
        """.trimIndent() + "\n"
        val f = ids(risky)
        for (id in listOf("IDEM-001", "IDEM-002", "DIST-001", "DB-002", "DIST-003", "REL-001", "REL-002", "REL-003", "REL-004")) {
            assertTrue(id in f, "$id 를 찾아야 한다: ${f.keys}")
        }

        val protectedCode = "src/main/kotlin/com/x/Safe.kt" to """
            package com.x

            @Service
            class Safe(private val paymentClient: PaymentClient, private val inboxRepository: InboxRepository) {
                @CircuitBreaker(name = "pay")
                fun pay() = paymentClient.post("x")
                // outbox, idempotency, DLQ(dead letter) 를 쓰는 프로젝트
                @KafkaListener(topics = ["a"])
                fun on(msg: String) = inboxRepository.hashCode()
            }
        """.trimIndent() + "\n"
        val g = ids(protectedCode)
        assertTrue(g.keys.none { it.startsWith("REL-001") || it == "IDEM-001" }, "보호된 코드: ${g.keys}")
    }

    @Test
    fun `엔티티 버전 readOnly final Kotlin 프록시 문제를 찾는다`() {
        val f = ids(
            "src/main/kotlin/com/x/Item.kt" to "package com.x\n\n@Entity\nclass Item(@Id val id: Long, var stock: Int)\n",
            "src/main/kotlin/com/x/Versioned.kt" to "package com.x\n\n@Entity\nclass Versioned(@Id val id: Long, var stock: Int, @Version var version: Long)\n",
            "src/main/kotlin/com/x/Svc.kt" to """
                package com.x

                @Service
                class Svc(private val itemRepository: ItemRepository) {
                    @Transactional(readOnly = true)
                    fun load() {
                        itemRepository.save(1)
                    }
                }
            """.trimIndent() + "\n",
        )
        assertEquals(1, f.getValue("JPA-001").size)
        assertTrue(f.getValue("JPA-001").single().message.contains("Item"))
        assertTrue("TX-006" in f, "kotlin-spring 플러그인이 없는 final 클래스")
        assertTrue("TX-007" in f)
    }

    @Test
    fun `Java 동시성 패턴을 폭넓게 찾는다`() {
        val java = "src/main/java/com/x/Shared.java" to """
            package com.x;

            import java.text.SimpleDateFormat;
            import java.util.HashMap;
            import java.util.Map;

            @Service
            public class Shared {
                private final Map<String, String> cache = new HashMap<>();
                private int counter;
                private Object lock = new Object();
                private final Object a = new Object();
                private final Object b = new Object();
                private SimpleDateFormat fmt;
                private String lazy;

                public void put(String k) {
                    if (!cache.containsKey(k)) {
                        cache.put(k, k);
                    }
                }

                public void inc() { counter++; }

                public void init() {
                    if (lazy == null) { lazy = "x"; }
                }

                public void first() {
                    synchronized (a) { synchronized (b) { work(); } }
                }

                public void second() {
                    synchronized (b) { synchronized (a) { work(); } }
                }

                public void bad() {
                    synchronized (lock) { work(); }
                    synchronized ("x") { work(); }
                }

                public void waiting() throws Exception { lock.wait(); }

                private void work() {}
            }
        """.trimIndent() + "\n"
        val f = ids(java)
        for (id in listOf("CON-001", "CON-004", "CON-005", "CON-006", "CON-007", "CON-009", "CON-010", "CON-011")) {
            assertTrue(id in f, "$id 를 찾아야 한다: ${f.keys}")
        }
    }

    @Test
    fun `AI 검토는 후보 발췌만 보내고 지문이 같으면 다시 묻지 않는다`() {
        val root = project("src/main/kotlin/com/x/Svc.kt" to "package com.x\n\n@Service\nclass Svc {\n    private var n = 0\n}\n")
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!
        assertEquals(1, findings.size)
        var calls = 0
        var sent = ""
        val ask = { p: String -> calls++; sent = p; """{"findings":[{"id":0,"verdict":"FALSE","reason":"단일 스레드","recommendation":""}]}""" }
        val first = RiskReviewer.review(root, findings, emptyMap(), "sonnet", false, ask = ask)
        RiskReviewer.review(root, findings, first, "sonnet", false, ask = ask)
        assertEquals(1, calls)
        assertTrue(sent.contains("private var n") && sent.contains("CON-001"))
        assertEquals("FALSE", first.values.single().verdict)
        assertFailsWith<IllegalArgumentException> { RiskReviewer.parse("없음") }
    }
}
