package com.dochiri.ilsim.runner

import java.util.concurrent.ConcurrentHashMap

/**
 * 웹에서 작업을 중지하면 실행 중인 Claude·테스트 프로세스를 끝낸다. 작업 스레드마다 하나씩 두며,
 * 사용량 확인 등 다른 스레드가 띄운 프로세스는 건드리지 않는다.
 */
class StopSignal {
    @Volatile
    var stopped: Boolean = false
        private set

    private val processes = ConcurrentHashMap.newKeySet<Process>()

    /** 중지로 표시하고 등록된 프로세스와 그 자식(sh -c 아래 gradle 등)을 끝낸다. */
    fun stop() {
        stopped = true
        processes.forEach(::kill)
    }

    private fun kill(p: Process) {
        // 일부 OS/sandbox는 자식 조회만 거부한다. 그 경우에도 직접 띄운 프로세스는 종료한다.
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
            .onFailure { System.err.println("자식 프로세스 조회 실패: ${it.message}") }
        p.destroyForcibly()
    }

    companion object {
        private val current = ThreadLocal<StopSignal?>()

        /** 이 스레드에서 [block] 을 실행하는 동안 [signal] 에 프로세스를 등록한다. */
        fun <T> within(signal: StopSignal, block: () -> T): T {
            current.set(signal)
            try {
                return block()
            } finally {
                current.remove()
            }
        }

        fun isStopped(): Boolean = current.get()?.stopped == true

        /** 이미 중지됐으면 새로 띄운 프로세스를 바로 끝낸다. */
        fun track(p: Process): Process {
            val signal = current.get() ?: return p
            signal.processes += p
            p.onExit().thenRun { signal.processes -= p }
            if (signal.stopped) signal.kill(p)
            return p
        }
    }
}
