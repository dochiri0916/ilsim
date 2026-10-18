package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText

/** 실제 판단 입력·모델·effort·프롬프트/정책 버전이 같은 유효한 응답만 재사용한다. */
class ReviewCache(private val directory: Path) {
    fun <T> get(prompt: String, reviewer: String, parse: (String) -> T, ask: () -> String): T {
        val file = directory.resolve(digest("review-v2\u0000$reviewer\u0000$prompt") + ".json")
        return KnowledgeFiles.locked(directory) {
            if (file.exists()) runCatching { parse(file.readText()) }.getOrNull()?.let {
                DecisionScope.record("CHANGE_AUDIT", "CACHE", "동일한 전체 근거·검토 정책의 유효한 결과", prompt)
                return@locked it
            }
            DecisionScope.record("CHANGE_AUDIT", "AGENT", "개방형 누락 탐지는 독립 검토로 유지; JEV 대체 품질은 미검증", prompt)
            val response = ask()
            val result = parse(response)
            KnowledgeFiles.write(file, response)
            result
        }
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
