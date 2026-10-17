package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateTest {

    @Test
    fun `테스트 경로를 판별한다`() {
        listOf("src/test/java/A.java", "tests/test_a.py", "web/src/a.test.ts", "src/main/FooTest.kt", "x/__tests__/a.js")
            .forEach { assertTrue(Gate.isTestPath(it), it) }
        listOf("src/main/java/A.java", "app/service.py", "specs/f/test-spec.md").forEach { assertFalse(Gate.isTestPath(it), it) }
    }

    @Test
    fun `요구사항 단계는 요구사항 문서만 수정할 수 있다`() {
        assertEquals(emptyList(), Gate.pathViolations("REQUIREMENTS", "f", listOf("specs/f/requirements.md")))
        assertEquals(1, Gate.pathViolations("REQUIREMENTS", "f", listOf("specs/f/requirements.md", "src/A.java")).size)
        assertEquals(1, Gate.pathViolations("REQUIREMENTS", "f", emptyList()).size)
    }

    @Test
    fun `테스트 작성 단계는 테스트 경로 밖을 수정할 수 없다`() {
        assertEquals(emptyList(), Gate.pathViolations("TEST_CODE", "f", listOf("src/test/A.java")))
        assertEquals(1, Gate.pathViolations("TEST_CODE", "f", listOf("src/test/A.java", "src/main/A.java")).size)
    }

    @Test
    fun `구현 단계는 테스트와 specs를 수정할 수 없다`() {
        assertEquals(emptyList(), Gate.pathViolations("IMPLEMENTATION", "f", listOf("src/main/A.java")))
        assertEquals(2, Gate.pathViolations("IMPLEMENTATION", "f", listOf("src/test/A.java", "specs/f/test-spec.md", "src/main/A.java")).size)
    }

    @Test
    fun `모든 요구사항이 테스트 케이스에 연결되어야 한다`() {
        val req = "REQ-1 가입\nREQ-2 탈퇴"
        assertEquals(emptyList(), Gate.traceabilityViolations(req, "| TC-1 | REQ-1 |\n| TC-2 | REQ-2 |"))
        val missing = Gate.traceabilityViolations(req, "| TC-1 | REQ-1 |")
        assertEquals(1, missing.size)
        assertTrue(missing.single().startsWith("REQ-2"))
        assertEquals(1, Gate.traceabilityViolations("설명만 있음", "TC-1 REQ-1").size)
    }

    @Test
    fun `빌드 산출물은 변경에서 제외한다`() {
        assertTrue(Gate.isIgnored("build/classes/A.class"))
        assertTrue(Gate.isIgnored("app/__pycache__/a.pyc"))
        assertFalse(Gate.isIgnored("src/A.java"))
    }
}
