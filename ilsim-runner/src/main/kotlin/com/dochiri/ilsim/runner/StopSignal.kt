package com.dochiri.ilsim.runner

import java.util.concurrent.ConcurrentHashMap

class StopSignal {
    @Volatile
    var stopped: Boolean = false
        private set

    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun stop() {
        stopped = true
        processes.forEach(::kill)
    }

    private fun kill(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
            .onFailure { System.err.println("자식 프로세스 조회 실패: ${it.message}") }
        p.destroyForcibly()
    }

    companion object {
        private val current = ThreadLocal<StopSignal?>()

        fun <T> within(signal: StopSignal, block: () -> T): T {
            current.set(signal)
            try {
                return block()
            } finally {
                current.remove()
            }
        }

        fun isStopped(): Boolean = current.get()?.stopped == true

        fun track(p: Process): Process {
            val signal = current.get() ?: return p
            signal.processes += p
            p.onExit().thenRun { signal.processes -= p }
            if (signal.stopped) signal.kill(p)
            return p
        }
    }
}
