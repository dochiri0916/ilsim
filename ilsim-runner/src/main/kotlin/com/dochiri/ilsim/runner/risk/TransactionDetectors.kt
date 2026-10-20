package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.risk.Detectors.braceBlock
import com.dochiri.ilsim.runner.risk.Detectors.readCall
import com.dochiri.ilsim.runner.risk.Detectors.remoteType
import com.dochiri.ilsim.runner.risk.Detectors.repoType
import com.dochiri.ilsim.runner.risk.Detectors.writeCall

internal object TransactionDetectors {
    private val proxied = listOf("Transactional", "Async", "Cacheable", "CacheEvict", "CachePut", "Retryable", "CircuitBreaker", "Retry", "PreAuthorize", "Validated")
    private val swallowedTypes = Regex("""\b(Exception|Throwable|RuntimeException|DataAccessException|PersistenceException|\w+ViolationException)\b""")
    private val asyncInTx = Regex("""\b(CompletableFuture\s*\.\s*(supplyAsync|runAsync)|\w*[eE]xecutor\w*\s*\.\s*(submit|execute)|Thread\s*\(|\.start\(\)|GlobalScope|\blaunch\s*\{|\basync\s*\{|\.parallelStream\()""")
    private val blocking = Regex("""\bThread\s*\.\s*sleep\s*\(|\.join\(\)|\.await\(""")

    private fun isTx(c: Cls, m: Member) = m.kind == "func" && ("Transactional" in m.annotations || ("Transactional" in c.annotations && !m.isPrivate))

    fun run(s: Scope) {
        val c = s.c
        val isBean = c.annotations.any { it in Detectors.beanAnnotations }
        val remoteFields = c.fields.filter { remoteType.containsMatchIn(it.type) }
        val repoFields = c.fields.filter { repoType.containsMatchIn(it.type) }
        val classAnn = s.annotationText(c.line)

        if (s.f.lang == "kotlin" && c.kind == "class" && "open" !in c.modifiers && "abstract" !in c.modifiers && !s.ctx.hasAllOpen &&
            (c.annotations.any { it in proxied } || c.funcs.any { m -> m.annotations.any { it in proxied } })
        ) {
            s.hit("TX-006", c.line, "Kotlin 클래스 `${c.name}` 은 final 인데 프록시 어노테이션을 씁니다. kotlin-spring(allopen) 플러그인이 없으면 프록시가 적용되지 않습니다")
        }

        for (m in c.funcs) {
            val ann = s.annotationText(m.line)
            if ("Transactional" in m.annotations && m.isPrivate) {
                s.hit("TX-003", m.line, "`${c.name}.${m.name}` 은 private 이라 @Transactional 이 적용되지 않습니다")
            }
            if (isTx(c, m)) {
                for (r in remoteFields) {
                    val call = Regex("""\b${Regex.escape(r.name)}\s*\.\s*\w+""").find(m.body) ?: continue
                    val outbox = if (Regex("Kafka|Rabbit|Jms|Stream").containsMatchIn(r.type)) " 커밋 전에 메시지가 나가 롤백 시 불일치가 생길 수 있으니 outbox 를 검토하세요." else ""
                    s.hit("TX-001", m.lineOf(call.range.first), "트랜잭션 `${c.name}.${m.name}` 안에서 외부 호출 `${call.value.replace(Regex("\\s+"), "")}`.$outbox")
                }
                blocking.find(m.body)?.let {
                    s.hit("TX-001", m.lineOf(it.range.first), "트랜잭션 `${c.name}.${m.name}` 안에서 블로킹 호출(`${it.value.trim()}`)로 커넥션을 오래 점유합니다")
                }
                for (catch in swallowed(m.body)) {
                    s.hit("TX-004", m.lineOf(catch), "트랜잭션 `${c.name}.${m.name}` 안에서 예외를 던지지 않고 삼킵니다 (롤백되지 않거나 rollback-only 불일치)")
                }
                asyncInTx.find(m.body)?.let {
                    s.hit("TX-009", m.lineOf(it.range.first), "트랜잭션 `${c.name}.${m.name}` 안에서 다른 스레드로 작업을 넘깁니다. 그 작업은 이 트랜잭션에 참여하지 않습니다")
                }
                val readOnly = Regex("""readOnly\s*=\s*true""").containsMatchIn(ann) ||
                    ("Transactional" !in m.annotations && Regex("""readOnly\s*=\s*true""").containsMatchIn(classAnn))
                if (readOnly) repoFields.firstNotNullOfOrNull { r -> Regex("""\b${Regex.escape(r.name)}${writeCall.pattern}""").find(m.body) }?.let {
                    s.hit("TX-007", m.lineOf(it.range.first), "readOnly 트랜잭션 `${c.name}.${m.name}` 안에서 쓰기 호출 `${it.value.trim()}` 이 있습니다")
                }
                if (s.f.lang == "java" && "Transactional" in m.annotations && Regex("""\bthrows\s+[\w, .]+""").containsMatchIn(ann) && "rollbackFor" !in ann) {
                    s.hit("TX-008", m.line, "`${c.name}.${m.name}` 이 체크 예외를 던지는데 rollbackFor 가 없어 예외가 나도 커밋됩니다")
                }
            }
            if (isBean && "Repository" !in c.annotations && !isTx(c, m) && "Transactional" !in c.annotations && !m.isPrivate && repoFields.isNotEmpty()) {
                val writes = repoFields.sumOf { r -> Regex("""\b${Regex.escape(r.name)}${writeCall.pattern}""").findAll(m.body).count() }
                val reads = repoFields.sumOf { r -> Regex("""\b${Regex.escape(r.name)}${readCall.pattern}""").findAll(m.body).count() }
                when {
                    writes >= 2 -> s.hit("TX-005", m.line, "`${c.name}.${m.name}` 은 트랜잭션 없이 저장소 쓰기를 ${writes}번 호출합니다")
                    writes == 1 && reads >= 1 -> s.hit("TX-005", m.line, "`${c.name}.${m.name}` 은 트랜잭션 없이 조회 후 쓰기를 합니다 (읽은 뒤 다른 요청이 바꿀 수 있습니다)")
                }
            }
        }

        val delegated = c.funcs.filter { m -> m.annotations.any { it in proxied } }
        for (target in delegated) for (caller in c.funcs) {
            if (caller === target) continue
            val shared = target.annotations.filter { it in proxied }
            if (caller.annotations.any { it in shared }) continue
            val name = Regex.escape(target.name)
            val call = Regex("""(?<![.\w])$name\s*\(|\bthis\s*(\.|::)\s*$name\b""").find(caller.body) ?: continue
            s.hit("TX-002", caller.lineOf(call.range.first), "`${c.name}.${caller.name}` 이 같은 클래스의 @${shared.first()} 메서드 `${target.name}` 을 직접 호출해 프록시가 적용되지 않습니다")
        }
    }

    private fun swallowed(body: String): List<Int> = Regex("""\bcatch\s*\(([^)]*)\)\s*\{""").findAll(body).mapNotNull { m ->
        if (!swallowedTypes.containsMatchIn(m.groupValues[1])) return@mapNotNull null
        val block = braceBlock(body, m.range.last)
        if (Regex("""\b(throw|setRollbackOnly)\b""").containsMatchIn(block)) null else m.range.first
    }.toList()
}
