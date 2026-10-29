package com.dochiri.ilsim.runner.jev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidelineLegacyTest {
    private val diff = """
        diff --git a/src/main/java/demo/Greeter.java b/src/main/java/demo/Greeter.java
        --- a/src/main/java/demo/Greeter.java
        +++ b/src/main/java/demo/Greeter.java
        @@ -3,6 +3,10 @@ public class Greeter {
             public String greet(String name) {
                 return "Hello, " + name;
             }
        -    // old
        +
        +    public String bye(String name) {
        +        return Messages.BYE + ", " + name + ".";
        +    }
         }
    """.trimIndent()

    @Test
    fun `기존 코드 보기는 맥락 줄과 지운 줄을, 추가 줄 보기는 추가 줄만 담는다`() {
        val previous = Guidelines.previous(diff)!!
        assertTrue("+        return \"Hello, \" + name;" in previous)
        assertTrue("+    // old" in previous)
        assertFalse("Messages.BYE" in previous)
        val added = Guidelines.additions(diff)
        assertTrue("+        return Messages.BYE + \", \" + name + \".\";" in added)
        assertFalse("Hello" in added)
        assertNull(Guidelines.previous("+++ b/A.java\n+class A {}"), "새 파일만 있으면 기존 코드가 없다")
    }

    @Test
    fun `기존 코드만 지침을 어기고 추가 줄은 지키면 기존 위반으로 보고, 다시 물은 값은 큰 쪽을 쓴다`() {
        val g = Guideline("docs/c.md", 3, "문구 상수", "인사말은 상수로 둔다", 0.9)
        assertEquals(0.1, Guidelines.Part(g, "x", 0.5, 0.45, before = 0.98, added = 0.1).probability)
        assertEquals(0.96, Guidelines.Part(g, "x", 0.96, null, before = 0.09, added = 0.95).probability, "추가 줄이 어기면 그대로")
        assertEquals(0.7, Guidelines.Part(g, "x", 0.7, null, before = 0.5, added = 0.1).probability, "기존 코드가 확실히 어기지 않으면 그대로")
        assertEquals(0.48, Guidelines.Part(g, "x", 0.35, 0.48).probability)
    }
}
