package com.dochiri.ilsim.runner.testing

import com.dochiri.ilsim.runner.convention.FactScanner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestingTest {

    private fun dir(): Path = Files.createTempDirectory("ilsim-testing")

    private fun write(root: Path, path: String, text: String) = root.resolve(path).also { it.parent.createDirectories() }.writeText(text)

    private fun scan(path: String, ext: String, text: String) =
        TestIntegrity.scan(listOf(FactScanner.parse(path, ext, true, text.lines())!!))

    @Test
    fun `테스트를 끄거나 단언을 비우거나 항상 통과하게 만든 변경을 찾는다`() {
        val kotlin = """
            package com.x

            import org.junit.jupiter.api.Disabled
            import org.junit.jupiter.api.Test

            class OrderTest {
                @Disabled("나중에")
                @Test
                fun `꺼진 테스트`() {
                    assertEquals(1, 2)
                }

                @Test
                fun `단언 없음`() {
                    service.place()
                }

                @Test
                fun `항상 통과`() {
                    assertTrue(true)
                }

                @Test
                fun `예외 삼킴`() {
                    try {
                        service.place()
                        assertEquals(1, one())
                    } catch (e: Exception) {
                        println("ignored")
                    }
                }

                @Test
                fun `정상`() {
                    assertEquals(2, service.count())
                }

                // @Test
                // fun old() {}
            }
        """.trimIndent() + "\n"
        val v = scan("src/test/kotlin/com/x/OrderTest.kt", "kt", kotlin)
        assertEquals(setOf("TEST-001", "TEST-002", "TEST-003", "TEST-004"), v.map { it.ruleId }.toSet())
        assertEquals(2, v.count { it.ruleId == "TEST-001" }, "꺼진 테스트와 주석 처리된 테스트")
        assertTrue(v.none { it.message.contains("`정상`") })
        assertTrue(v.all { it.severity == "BLOCK" })
    }

    @Test
    fun `Java 테스트도 같은 기준으로 본다`() {
        val java = """
            package com.x;

            import org.junit.jupiter.api.Test;

            class FooTest {
                @Test
                void noAssertion() {
                    service.run();
                }

                @Test
                void fine() {
                    assertEquals(1, service.run());
                }
            }
        """.trimIndent() + "\n"
        val v = scan("src/test/java/com/x/FooTest.java", "java", java)
        assertEquals(listOf("TEST-002"), v.map { it.ruleId })
    }

    @Test
    fun `바뀐 테스트 클래스가 건너뛴 케이스를 결과 XML 에서 찾는다`() {
        val root = dir()
        write(root, "build/test-results/test/TEST-com.x.OrderTest.xml", """<testsuite><testcase name="a" classname="com.x.OrderTest"><skipped/></testcase><testcase name="b" classname="com.x.OrderTest"/></testsuite>""")
        write(root, "build/test-results/test/TEST-com.x.OtherTest.xml", """<testsuite><testcase name="c" classname="com.x.OtherTest"><skipped/></testcase></testsuite>""")
        val since = Instant.now().minusSeconds(60)
        assertEquals(listOf("com.x.OrderTest.a"), TestIntegrity.skipped(root, since, setOf("src/test/kotlin/com/x/OrderTest.kt")))
        assertEquals(emptyList(), TestIntegrity.skipped(root, Instant.now().plusSeconds(60), setOf("src/test/kotlin/com/x/OrderTest.kt")), "이전 실행의 결과는 무시")
        assertEquals(emptyList(), TestIntegrity.skipped(root, since, emptySet()))
    }

    @Test
    fun `선언한 테스트가 결과 XML 에 없으면 실행되지 않은 테스트로 본다`() {
        val root = dir()
        val path = "src/test/kotlin/com/x/OrderTest.kt"
        write(root, path, "package com.x\n\nclass OrderTest {\n    @Test\n    fun a() {}\n\n    @Test\n    private fun b() {}\n\n    class Inner {\n        @Test\n        fun c() {}\n    }\n}\n")
        val since = Instant.now().minusSeconds(60)
        assertEquals(emptyList(), TestIntegrity.unexecuted(root, since, setOf(path)), "결과 XML 이 없으면 판단하지 않는다")
        write(root, "build/test-results/test/TEST-com.x.OrderTest.xml", """<testsuite><testcase name="a()" classname="com.x.OrderTest"/></testsuite>""")
        assertEquals(listOf("$path: b", "$path: c"), TestIntegrity.unexecuted(root, since, setOf(path)))
        write(root, "build/test-results/test/TEST-com.x.OrderTest.xml", """<testsuite><testcase name="a()" classname="com.x.OrderTest"/><testcase name="b()" classname="com.x.OrderTest"/><testcase name="c()[1]" classname="com.x.OrderTest${'$'}Inner"/></testsuite>""")
        assertEquals(emptyList(), TestIntegrity.unexecuted(root, since, setOf(path)))
        write(root, "build/test-results/test/TEST-com.x.OrderTest.xml", """<testsuite><testcase name="주문 a" classname="com.x.OrderTest"/><testcase name="주문 b" classname="com.x.OrderTest"/><testcase name="주문 c" classname="com.x.OrderTest"/></testsuite>""")
        assertEquals(emptyList(), TestIntegrity.unexecuted(root, since, setOf(path)), "표시 이름으로 바뀌어도 실행 수가 선언 수 이상이면 통과")
    }

    @Test
    fun `PIT 결과에서 바뀐 소스의 점수와 살아남은 변이를 계산한다`() {
        val root = dir()
        write(root, "build/reports/pitest/mutations.xml", """
            <mutations>
              <mutation detected='true' status='KILLED'><sourceFile>Order.kt</sourceFile><lineNumber>10</lineNumber><description>negated</description></mutation>
              <mutation detected='true' status='TIMED_OUT'><sourceFile>Order.kt</sourceFile><lineNumber>11</lineNumber><description>loop</description></mutation>
              <mutation detected='false' status='SURVIVED'><sourceFile>Order.kt</sourceFile><lineNumber>12</lineNumber><description>removed call</description></mutation>
              <mutation detected='false' status='NO_COVERAGE'><sourceFile>Order.kt</sourceFile><lineNumber>13</lineNumber><description>return</description></mutation>
              <mutation detected='false' status='NON_VIABLE'><sourceFile>Order.kt</sourceFile><lineNumber>14</lineNumber><description>x</description></mutation>
              <mutation detected='false' status='SURVIVED'><sourceFile>Other.kt</sourceFile><lineNumber>1</lineNumber><description>y</description></mutation>
            </mutations>
        """.trimIndent())
        val r = assertNotNull(MutationReport.evaluate(root, Instant.now().minusSeconds(60), setOf("src/main/kotlin/com/x/Order.kt")))
        assertEquals(2, r.killed)
        assertEquals(4, r.total, "NON_VIABLE 과 다른 파일은 제외")
        assertEquals(0.5, r.score)
        assertEquals(2, r.survivors.size)
        assertTrue(r.score < MutationReport.THRESHOLD)
        assertNull(MutationReport.evaluate(root, Instant.now().minusSeconds(60), setOf("src/main/kotlin/com/x/None.kt")))
    }

    @Test
    fun `로그 출력 intrinsics toString 안의 변이는 등가 변이로 보고 거른다`() {
        val root = dir()
        write(root, "target/pit-reports/mutations.xml", """
            <mutations>
              <mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>com.x.A</mutatedClass><mutatedMethod>run</mutatedMethod><lineNumber>1</lineNumber><description>removed call to org/slf4j/Logger::info</description></mutation>
              <mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>com.x.A</mutatedClass><mutatedMethod>run</mutatedMethod><lineNumber>2</lineNumber><description>removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter</description></mutation>
              <mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>com.x.A</mutatedClass><mutatedMethod>toString</mutatedMethod><lineNumber>3</lineNumber><description>replaced return value</description></mutation>
              <mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>com.x.A</mutatedClass><mutatedMethod>pay</mutatedMethod><lineNumber>4</lineNumber><description>negated conditional</description></mutation>
              <mutation status='KILLED'><sourceFile>A.kt</sourceFile><mutatedClass>com.x.A</mutatedClass><mutatedMethod>pay</mutatedMethod><lineNumber>5</lineNumber><description>negated conditional</description></mutation>
            </mutations>
        """.trimIndent())
        val r = assertNotNull(MutationReport.evaluate(root, Instant.now().minusSeconds(60), setOf("src/main/kotlin/com/x/A.kt")))
        assertEquals(2, r.total)
        assertEquals(1, r.killed)
        assertEquals(1, r.survivors.size)
        assertTrue(r.survivors.single().contains("A.kt:4"))
    }
}
