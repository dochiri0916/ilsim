package com.dochiri.ilsim.auth.domain

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 같은 접속처에서 로그인에 연달아 실패하면 잠시 막는다. */
class LoginThrottle(
    private val maxFailures: Int = MAX_FAILURES,
    private val lockFor: Duration = LOCK,
) {
    private class Failures(val count: Int, val lockedUntil: Instant?)

    private val failures = ConcurrentHashMap<String, Failures>()

    fun isLocked(key: String, now: Instant): Boolean {
        val lockedUntil = failures[key]?.lockedUntil ?: return false
        return now.isBefore(lockedUntil)
    }

    fun recordFailure(key: String, now: Instant) {
        failures.merge(key, Failures(1, null)) { old, _ ->
            // 잠금이 끝난 뒤의 실패는 처음부터 다시 센다.
            val expired = old.lockedUntil != null && !now.isBefore(old.lockedUntil)
            val count = if (expired) 1 else old.count + 1
            Failures(count, if (count >= maxFailures) now.plus(lockFor) else null)
        }
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    companion object {
        const val MAX_FAILURES = 5
        val LOCK: Duration = Duration.ofMinutes(1)
    }
}
