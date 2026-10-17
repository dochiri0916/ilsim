package com.dochiri.ilsim.task.domain

import java.security.MessageDigest
import java.util.HexFormat

/** 줄 번호·경로·숫자·공백 차이를 무시하고 사유의 앞부분만 비교한다. 같은 사유가 연달아 나오면 더 시도해도 달라지지 않는다고 본다. */
object FailureSignature {
    private val DIGITS = Regex("""\d+""")
    private val SPACES = Regex("""\s+""")

    fun of(error: String?): String? {
        if (error.isNullOrBlank()) return null
        val key = error.replace(DIGITS, "#").replace(SPACES, " ").trim()
        return if (key.length > 300) key.substring(0, 300) else key
    }
}

object ArtifactHash {
    fun of(artifacts: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        artifacts.toSortedMap().forEach { (path, content) ->
            md.update(path.toByteArray())
            md.update(0)
            md.update(content.toByteArray())
            md.update(0)
        }
        return HexFormat.of().formatHex(md.digest())
    }
}
