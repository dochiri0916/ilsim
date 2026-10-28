package com.dochiri.ilsim.runner.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeCheckTest {
    private val mapper = ObjectMapper()

    private fun jev(answer: (String) -> Double): Jev {
        val transport = object : JevTransport {
            override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
                val answers = mapper.createObjectNode()
                val paths = body.path("state").path("files").associate { it.path("id").asText() to it.path("path").asText() }
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(paths.getValue(id))) }
                return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev").set("answers", answers))
            }
            override fun close() {}
        }
        return Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())
    }

    private fun file(path: String, line: String) = "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n+$line\n"

    private val spec = """
        # 쿠폰 사용

        ## 요구사항
        - REQ-1: 만료된 쿠폰은 CouponExpiredException 을 던진다.

        ## 영향 범위
        - `src/main/java/com/x/coupon/CouponService.java`
        - `com.x.coupon.policy` 패키지
        - `src/main/resources/db/`

        ## 기존 기능 영향
        - 빌드 설정은 바꾸지 않는다.
    """.trimIndent()

    @Test
    fun `변경을 파일별로 나누고 영향 범위 절과 문서에 적힌 파일을 가린다`() {
        val diff = file("src/main/java/com/x/coupon/CouponService.java", "a") + file("build.gradle", "b")
        assertEquals(listOf("src/main/java/com/x/coupon/CouponService.java", "build.gradle"), ScopeCheck.files(diff).keys.toList())
        assertTrue(ScopeCheck.scope(spec)!!.startsWith("- `src/main/java/com/x/coupon/CouponService.java`"))
        assertNull(ScopeCheck.scope("# 제목\n\n## 요구사항\n- REQ-1: x"))
        assertTrue(ScopeCheck.listed(spec, "src/main/java/com/x/coupon/CouponService.java"))
        assertTrue(ScopeCheck.listed(spec, "src/main/java/com/x/coupon/CouponExpiredException.java"), "요구사항에 적힌 클래스 이름")
        assertTrue(ScopeCheck.listed(spec, "src/main/java/com/x/coupon/policy/ExpiryPolicy.java"), "영향 범위의 패키지")
        assertTrue(ScopeCheck.listed(spec, "src/main/resources/db/V2__coupon.sql"), "영향 범위의 디렉터리")
        assertFalse(ScopeCheck.listed(spec, "build.gradle"), "소문자 파일은 이름 일부로 맞추지 않는다")
        assertFalse(ScopeCheck.listed(spec, "src/main/java/com/x/member/MemberService.java"))
    }

    @Test
    fun `문서에 없는 파일만 JEV에 묻고 0점2 이하는 범위 밖, 0점8 미만은 애매로 본다`() {
        val diff = file("src/main/java/com/x/coupon/CouponService.java", "a") + file("build.gradle", "b") + file("src/main/java/com/x/coupon/Clock.java", "c")
        val asked = mutableListOf<String>()
        val result = ScopeCheck.judge(jev { path -> asked += path; if (path == "build.gradle") 0.1 else 0.6 }, spec, "IMPLEMENTATION", diff)!!
        assertEquals(listOf("build.gradle", "src/main/java/com/x/coupon/Clock.java"), asked.sorted())
        assertEquals(listOf("src/main/java/com/x/coupon/CouponService.java"), result.listed)
        assertEquals(listOf("build.gradle"), result.outside)
        assertEquals(listOf("src/main/java/com/x/coupon/Clock.java"), result.uncertain)
        assertEquals(emptyList(), ScopeCheck.judge(Jev(null), spec, "IMPLEMENTATION", file("src/main/java/com/x/coupon/CouponService.java", "a"))!!.outside)
        assertNull(ScopeCheck.judge(Jev(null), spec, "IMPLEMENTATION", diff), "JEV가 꺼졌는데 물을 파일이 있으면 판정 없음")
    }
}
