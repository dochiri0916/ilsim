package com.dochiri.ilsim.task.domain

import java.security.MessageDigest
import java.util.HexFormat

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
