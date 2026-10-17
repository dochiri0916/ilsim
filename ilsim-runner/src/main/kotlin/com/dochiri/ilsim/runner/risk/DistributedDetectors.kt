package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.risk.Detectors.messagingType
import com.dochiri.ilsim.runner.risk.Detectors.remoteType
import com.dochiri.ilsim.runner.risk.Detectors.repoType
import com.dochiri.ilsim.runner.risk.Detectors.writeCall

/** DB 갱신 경쟁, 중복 요청·메시지, 다중 인스턴스에서 깨지는 가정을 본다. */
internal object DistributedDetectors {
    private val listeners = setOf("KafkaListener", "RabbitListener", "JmsListener", "SqsListener", "StreamListener", "EventListener")
    private val findCall = Regex("""\.(findById|findBy\w+|getById|getReferenceById|get\w*By\w+)\s*\(""")
    private val decrement = Regex("""\b(decrease|deduct|reduce|subtract|withdraw|consume|reserve|debit)\w*\s*\(|\b\w*(stock|quantity|qty|balance|amount|remaining|inventory|point)\w*\s*(-=|=\s*[\w.()]+\s*-)""", RegexOption.IGNORE_CASE)
    private val lockish = Regex("""lock|ForUpdate|Version|atomic|\bupdate\w*\s*\(""", RegexOption.IGNORE_CASE)
    private val retryAnn = setOf("Retryable", "Retry")
    private val remoteWrite = Regex("""\.\s*(post|put|patch|delete|exchange|postFor\w+|send|convertAndSend|charge|pay\w*|transfer\w*)\s*\(""", RegexOption.IGNORE_CASE)

    fun run(s: Scope) {
        val c = s.c
        val isBean = c.annotations.any { it in Detectors.beanAnnotations }
        val repoFields = c.fields.filter { repoType.containsMatchIn(it.type) }
        val messagingFields = c.fields.filter { messagingType.containsMatchIn(it.type) && !it.type.contains("Event") }
        val remoteFields = c.fields.filter { remoteType.containsMatchIn(it.type) }
        val idempotent = Detectors.idempotency.containsMatchIn(c.text)

        if ("Entity" in c.annotations && !s.ctx.hasLock) {
            val versioned = c.fields.any { "Version" in it.annotations }
            val mutable = c.fields.filter { it.mutable && "Id" !in it.annotations && "Version" !in it.annotations && it.name != "id" && "Transient" !in it.annotations }
            if (!versioned && mutable.isNotEmpty()) {
                s.hit("JPA-001", c.line, "엔티티 `${c.name}` 은 변경 가능한 필드(${mutable.take(4).joinToString { it.name }})가 있는데 @Version 이 없어 동시 수정 시 Lost Update 가 생길 수 있습니다")
            }
        }

        for (m in c.funcs) {
            val writes = repoFields.flatMap { r -> Regex("""\b${Regex.escape(r.name)}${writeCall.pattern}""").findAll(m.body).toList() }

            if (m.annotations.contains("Modifying") && s.ctx.hasVersion) {
                val text = s.annotationText(m.line) + m.body
                val updatesVersioned = s.ctx.versionedEntities.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
                if (updatesVersioned && Regex("""\bupdate\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) && !Regex("""version""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    s.hit("JPA-002", m.line, "`${c.name}.${m.name}` 의 bulk update 가 version 을 올리지 않아 낙관적 락을 우회합니다")
                }
            }

            if (isBean && !s.ctx.hasVersion && !s.ctx.hasLock && findCall.containsMatchIn(m.body) && !lockish.containsMatchIn(m.body)) {
                decrement.find(m.body)?.let {
                    s.hit("DB-001", m.lineOf(it.range.first), "`${c.name}.${m.name}` 이 락·@Version·원자적 UPDATE 없이 조회 후 차감합니다 (프로젝트 어디에도 락/버전 보호가 없음)")
                }
            }

            if (isBean && "Repository" !in c.annotations && !s.ctx.hasUniqueGuard && repoFields.isNotEmpty()) {
                for (r in repoFields) {
                    val read = Regex("""\b${Regex.escape(r.name)}\s*\.\s*(exists\w*|find\w*By\w*)\s*\(""").find(m.body) ?: continue
                    val rest = m.body.substring(read.range.last)
                    val window = rest.take(400)
                    val write = repoFields.firstNotNullOfOrNull { w -> Regex("""\b${Regex.escape(w.name)}\s*\.\s*(save|saveAll|insert\w*|persist)\s*\(""").find(window) } ?: continue
                    val between = m.body.substring(maxOf(0, read.range.first - 60), read.range.last) + window.substring(0, write.range.first)
                    if (Regex("""\bif\b|\bwhen\b|\?:|orElse|ifPresent|isPresent|isEmpty|\brequire\b|\bcheck\b|==\s*null|!=\s*null""").containsMatchIn(between)) {
                        s.hit("DB-002", m.lineOf(read.range.first), "`${c.name}.${m.name}` 이 존재 여부를 확인한 뒤 저장합니다. 유니크 제약이 없으면 동시 요청이 중복 데이터를 만듭니다")
                        break
                    }
                }
            }

            val listener = m.annotations.firstOrNull { it in listeners }
            if (listener != null && !idempotent) {
                s.hit("IDEM-001", m.line, "@$listener `${c.name}.${m.name}` 에 중복 메시지 처리(멱등성) 흔적이 없습니다")
            } else if ("PostMapping" in m.annotations && !idempotent && !s.ctx.hasIdempotency) {
                s.hit("IDEM-001", m.line, "POST `${c.name}.${m.name}` 에 멱등성 키 처리가 없고 프로젝트에도 멱등성 구현이 없습니다")
            }

            if (m.annotations.any { it in retryAnn } && !idempotent && (writes.isNotEmpty() || remoteFields.any { r -> Regex("""\b${Regex.escape(r.name)}${remoteWrite.pattern}""", RegexOption.IGNORE_CASE).containsMatchIn(m.body) })) {
                s.hit("IDEM-002", m.line, "재시도되는 `${c.name}.${m.name}` 이 멱등성 처리 없이 쓰기·외부 변경 호출을 합니다. 재시도가 중복 효과를 만듭니다")
            }

            if (isBean && writes.isNotEmpty() && !s.ctx.hasOutbox) {
                messagingFields.firstNotNullOfOrNull { r -> Regex("""\b${Regex.escape(r.name)}\s*\.\s*\w+""").find(m.body) }?.let {
                    s.hit("DIST-001", m.lineOf(it.range.first), "`${c.name}.${m.name}` 이 DB 쓰기와 메시지 발행을 함께 합니다. 한쪽만 성공할 수 있으니 outbox 를 쓰세요 (프로젝트에 outbox 가 없음)")
                }
            }

            val jvmLock = "synchronized" in m.modifiers || Regex("""\bsynchronized\s*\(|\.\s*lock\s*\(\s*\)|withLock""").containsMatchIn(m.body)
            if (isBean && jvmLock && repoFields.any { r -> Regex("""\b${Regex.escape(r.name)}\s*\.\s*\w+""").containsMatchIn(m.body) }) {
                s.hit("DIST-002", m.line, "`${c.name}.${m.name}` 이 JVM 락으로 DB 작업을 보호합니다. 인스턴스가 둘 이상이면 보호되지 않습니다")
            }

            if ("Scheduled" in m.annotations && !s.ctx.hasSchedulerLock) {
                s.hit("DIST-003", m.line, "@Scheduled `${c.name}.${m.name}` 은 인스턴스마다 실행됩니다. 분산 락(ShedLock 등)이 없으면 중복 실행됩니다")
            }
        }
    }
}
