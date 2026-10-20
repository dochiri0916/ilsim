package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.convention.ConvFile

internal object ResilienceDetectors {
    private val httpClientType = Regex("""(RestTemplate|RestClient|WebClient|HttpClient|OkHttpClient|Feign\w*|\w+Client)\b""")
    private val messagingType = Regex("""Kafka|Rabbit|Jms|Redis|Mongo|Elasticsearch|S3|Dynamo|Sqs|Sns""", RegexOption.IGNORE_CASE)
    private val resilienceAnn = setOf("CircuitBreaker", "Bulkhead", "TimeLimiter", "HystrixCommand")
    private val resilienceText = Regex("""CircuitBreaker|Bulkhead|TimeLimiter|fallback|Resilience|Hystrix""", RegexOption.IGNORE_CASE)
    private val listeners = setOf("KafkaListener", "RabbitListener", "JmsListener", "SqsListener")
    private val clientCreation = Regex("""\bRestTemplate\s*\(\s*\)|\bRestTemplateBuilder\b|HttpClient\s*\.\s*(newHttpClient|newBuilder)\s*\(|WebClient\s*\.\s*(create|builder)\s*\(|\bRestClient\s*\.\s*(create|builder)\s*\(|\bOkHttpClient\s*\(|\bOkHttpClient\s*\.\s*Builder""")
    private val timeoutText = Regex("""[tT]imeout|ReadTimeout|connectTimeout|responseTimeout""")

    fun run(s: Scope) {
        val c = s.c

        if ("FeignClient" in c.annotations && !s.ctx.hasResilience && !s.annotationText(c.line).contains("fallback")) {
            s.hit("REL-001", c.line, "@FeignClient `${c.name}` 에 fallback 이 없고 프로젝트에 서킷브레이커도 없습니다")
        }

        val clients = c.fields.filter { httpClientType.containsMatchIn(it.type) && !messagingType.containsMatchIn(it.type) }
        if (clients.isNotEmpty() && c.kind != "interface") {
            val protectedClass = c.annotations.any { it in resilienceAnn } || c.funcs.any { m -> m.annotations.any { it in resilienceAnn } } || resilienceText.containsMatchIn(c.text)
            if (!protectedClass) {
                val first = clients.asSequence().flatMap { cl -> c.funcs.asSequence().map { m -> m to Regex("""\b${Regex.escape(cl.name)}\s*\.\s*\w+""").find(m.body) } }
                    .firstOrNull { it.second != null }
                if (first != null) {
                    val (m, call) = first
                    s.hit("REL-001", m.lineOf(call!!.range.first), "`${c.name}.${m.name}` 의 외부 호출 `${call.value.replace(Regex("\\s+"), "")}` 에 서킷브레이커·폴백이 없습니다")
                }
            }
        }

        for (m in c.funcs) {
            if ("Retryable" in m.annotations && !s.annotationText(m.line).contains("backoff")) {
                s.hit("REL-003", m.line, "@Retryable `${c.name}.${m.name}` 에 backoff 가 없어 실패 즉시 연달아 재시도합니다")
            }
            val listener = m.annotations.firstOrNull { it in listeners }
            if (listener != null && !s.ctx.hasDeadLetter && !s.annotationText(m.line).contains("errorHandler")) {
                s.hit("REL-004", m.line, "@$listener `${c.name}.${m.name}` 에 DLQ·재처리 경로가 없습니다 (프로젝트에 DLT/DLQ 구성이 없음)")
            }
        }
    }

    fun file(f: ConvFile, out: MutableList<Hit>) {
        val text = stripLiterals(f.lines.joinToString("\n"))
        val creation = clientCreation.find(text) ?: return
        if (timeoutText.containsMatchIn(text)) return
        out += Hit("REL-002", f.path, text.take(creation.range.first).count { it == '\n' } + 1, "HTTP 클라이언트 `${creation.value.replace(Regex("\\s+"), "")}` 를 만들면서 타임아웃을 지정하지 않았습니다")
    }
}
