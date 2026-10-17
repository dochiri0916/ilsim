package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.convention.ConvFile

/** 탐지 결과 한 건. 규칙의 기본 심각도는 [RiskRules] 가 갖고 프로젝트 설정이 덮어쓴다. */
internal class Hit(val ruleId: String, val file: String, val line: Int, val message: String)

internal class RiskRule(val id: String, val title: String, val defaultSeverity: String, val spec: String)

/**
 * 트랜잭션·동시성·멱등성 위험 탐지. Java·Kotlin 소스를 텍스트로만 읽으며 컴파일러·JDK 버전에 의존하지 않는다.
 * 타입 해석이 없는 휴리스틱이라 후보를 찾는 수준이고, 정밀도가 높은 규칙만 기본 BLOCK 이다.
 */
internal object RiskRules {
    val all = listOf(
        RiskRule("TX-001", "트랜잭션 안의 외부 호출·블로킹", "REVIEW", "DIST-CONC-022"),
        RiskRule("TX-002", "프록시를 거치지 않는 자기 호출", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-003", "private 메서드의 @Transactional", "BLOCK", "SPRING-CONC-017"),
        RiskRule("TX-004", "트랜잭션 안에서 삼킨 예외", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-005", "트랜잭션 없는 다중 쓰기·조회 후 쓰기", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-006", "프록시할 수 없는 final Kotlin 클래스의 프록시 어노테이션", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-007", "readOnly 트랜잭션 안의 쓰기", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-008", "체크 예외가 롤백되지 않는 @Transactional", "REVIEW", "SPRING-CONC-017"),
        RiskRule("TX-009", "트랜잭션 안에서 다른 스레드로 넘긴 작업", "REVIEW", "SPRING-CONC-017"),
        RiskRule("CON-001", "변경 가능한 공유 상태(싱글톤·static·companion)", "REVIEW", "JAVA-CONC-001"),
        RiskRule("CON-002", "volatile 없는 Double-Checked Locking", "BLOCK", "JAVA-CONC-006"),
        RiskRule("CON-003", "finally 에서 해제하지 않는 Lock", "BLOCK", "JAVA-CONC-007"),
        RiskRule("CON-004", "컬렉션 check-then-act", "REVIEW", "JAVA-CONC-004"),
        RiskRule("CON-005", "공유 필드의 비원자적 갱신", "REVIEW", "JAVA-CONC-004"),
        RiskRule("CON-006", "락 획득 순서가 엇갈리는 중첩 synchronized", "REVIEW", "JAVA-CONC-003"),
        RiskRule("CON-007", "부적절한 synchronized 대상", "REVIEW", "JAVA-CONC-002"),
        RiskRule("CON-008", "락을 쥔 채 외부 호출·sleep", "REVIEW", "DB-CONC-016"),
        RiskRule("CON-009", "while 없이 호출한 wait()", "REVIEW", "JAVA-CONC-008"),
        RiskRule("CON-010", "스레드 안전하지 않은 객체의 공유", "REVIEW", "JAVA-CONC-005"),
        RiskRule("CON-011", "동기화 없는 지연 초기화", "REVIEW", "JAVA-CONC-002"),
        RiskRule("JPA-001", "@Version 없는 변경 가능한 엔티티", "REVIEW", "JPA-CONC-018"),
        RiskRule("JPA-002", "버전을 올리지 않는 bulk update", "REVIEW", "JPA-CONC-019"),
        RiskRule("DB-001", "락·버전 없이 읽고 검사하고 차감", "REVIEW", "DB-CONC-011"),
        RiskRule("DB-002", "존재 확인 후 저장(중복 생성 경쟁)", "REVIEW", "DB-CONC-010"),
        RiskRule("IDEM-001", "멱등성 처리 흔적이 없는 소비자·POST", "REVIEW", "DIST-CONC-021"),
        RiskRule("IDEM-002", "멱등성 없이 재시도하는 쓰기", "REVIEW", "DIST-CONC-021"),
        RiskRule("DIST-001", "DB 쓰기와 메시지 발행이 한 메서드에 있음(outbox 없음)", "REVIEW", "DIST-CONC-022"),
        RiskRule("DIST-002", "JVM 락으로 DB 작업을 보호", "REVIEW", "DIST-CONC-020"),
        RiskRule("DIST-003", "분산 락 없는 @Scheduled", "REVIEW", "DIST-CONC-021"),
        RiskRule("REL-001", "서킷브레이커·폴백 없는 외부 호출", "REVIEW", "RELIABILITY"),
        RiskRule("REL-002", "타임아웃 없는 HTTP 클라이언트", "REVIEW", "RELIABILITY"),
        RiskRule("REL-003", "백오프 없는 재시도", "REVIEW", "RELIABILITY"),
        RiskRule("REL-004", "DLQ·재처리 경로 없는 메시지 소비자", "REVIEW", "RELIABILITY"),
    )

    /** 규칙마다 한 줄 권고. 탐지 결과와 함께 보여 줘서 무엇을 적용해야 하는지 알려 준다. */
    val remedies = mapOf(
        "TX-001" to "외부 호출은 트랜잭션 밖(커밋 후)으로 옮기세요. 메시지는 Transactional Outbox, 그 외 호출은 @TransactionalEventListener(AFTER_COMMIT)로 분리합니다.",
        "TX-002" to "대상 메서드를 별도 빈으로 분리해 주입받아 호출하세요(프록시를 거쳐야 어노테이션이 적용됩니다).",
        "TX-003" to "메서드를 public 으로 바꾸거나 트랜잭션 경계를 호출하는 public 메서드로 옮기세요.",
        "TX-004" to "예외를 다시 던지거나 setRollbackOnly() 로 롤백을 명시하세요.",
        "TX-005" to "서비스 메서드에 @Transactional 을 두어 조회와 쓰기를 한 트랜잭션으로 묶으세요.",
        "TX-006" to "빌드에 kotlin-spring(allopen) 플러그인을 추가하거나 클래스를 open 으로 선언하세요.",
        "TX-007" to "쓰기가 필요하면 readOnly 를 제거하고, 읽기 전용이면 쓰기 호출을 분리하세요.",
        "TX-008" to "@Transactional(rollbackFor = Exception.class) 를 지정하세요.",
        "TX-009" to "비동기 작업은 커밋 이후(@TransactionalEventListener/outbox)에 실행하세요.",
        "CON-001" to "상태를 지역 변수·불변 객체로 바꾸거나 Atomic*/Concurrent* 컬렉션을 쓰세요. 공유가 꼭 필요하면 DB/Redis 로 옮기세요.",
        "CON-002" to "필드를 volatile 로 선언하거나 holder idiom, Kotlin by lazy 를 쓰세요.",
        "CON-003" to "lock() 직후 try { ... } finally { unlock() } 로 감싸세요.",
        "CON-004" to "putIfAbsent/computeIfAbsent 같은 원자적 API 를 쓰세요.",
        "CON-005" to "AtomicInteger/LongAdder 또는 synchronized 로 읽기-수정-쓰기를 원자화하세요.",
        "CON-006" to "락 획득 순서를 전역으로 통일하거나 tryLock(timeout) 으로 대기를 제한하세요.",
        "CON-007" to "private final 전용 락 객체를 만들어 쓰세요.",
        "CON-008" to "락 안에서는 메모리 연산만 하고 외부 호출·sleep 은 락 밖으로 옮기세요.",
        "CON-009" to "while (!조건) { wait() } 형태로 조건을 다시 검사하거나 Condition/BlockingQueue 를 쓰세요.",
        "CON-010" to "요청마다 새로 만들거나 ThreadLocal, DateTimeFormatter 같은 스레드 안전한 대안을 쓰세요.",
        "CON-011" to "생성자에서 초기화하거나 by lazy/holder idiom/동기화로 한 번만 초기화되게 하세요.",
        "JPA-001" to "@Version 필드를 추가하고 충돌 시 재시도·오류 처리를 정의하세요(또는 비관적 락).",
        "JPA-002" to "bulk update 에 version = version + 1 을 포함하거나 엔티티를 조회해 변경하세요.",
        "DB-001" to "비관적 락(@Lock PESSIMISTIC_WRITE)이나 'UPDATE … SET stock = stock - :n WHERE stock >= :n' 같은 원자적 UPDATE 와 영향 행 수 검사를 쓰세요. @Version 도 가능합니다.",
        "DB-002" to "유니크 제약을 추가하고 중복 키 예외를 처리하거나 INSERT … ON CONFLICT 를 쓰세요.",
        "IDEM-001" to "Inbox 패턴: 메시지/요청 ID 를 유니크 키로 처리 이력 테이블에 기록하고 중복이면 건너뛰세요. POST 는 Idempotency-Key 헤더를 받으세요.",
        "IDEM-002" to "재시도되는 쓰기에 멱등성 키를 전달하거나 처리 이력으로 중복 효과를 막으세요.",
        "DIST-001" to "Transactional Outbox: 같은 트랜잭션에서 outbox 테이블에 이벤트를 저장하고 별도 릴레이가 발행하게 하세요.",
        "DIST-002" to "낙관적/비관적 락, 유니크 제약, 분산 락(Redis/ShedLock)으로 인스턴스 간 보호를 하세요.",
        "DIST-003" to "ShedLock 같은 분산 락을 적용하거나 단일 인스턴스 전용 잡으로 분리하세요.",
        "REL-001" to "Resilience4j CircuitBreaker + TimeLimiter + Bulkhead 를 적용하고 fallback 을 정의하세요.",
        "REL-002" to "연결·읽기 타임아웃을 명시하세요(기본값이 무한 대기일 수 있습니다).",
        "REL-003" to "@Retryable 에 backoff(지수 + jitter)와 maxAttempts 를 지정하세요.",
        "REL-004" to "@RetryableTopic/DeadLetterPublishingRecoverer 로 DLQ 를 구성하고 재처리 절차를 정의하세요.",
    )
    val byId = all.associateBy { it.id }
}

/** 프로젝트 전체에서 보호 장치가 한 번이라도 쓰였는지. 한 곳에서 보호가 보이면 다른 곳의 같은 패턴은 후보로 올리지 않는 규칙에 쓴다. */
internal class Context(
    val hasVersion: Boolean,
    val hasLock: Boolean,
    val hasIdempotency: Boolean,
    val hasOutbox: Boolean,
    val hasUniqueGuard: Boolean,
    val hasSchedulerLock: Boolean,
    val hasAllOpen: Boolean,
    /** @Version 이 있는 엔티티의 클래스명·테이블명(소문자). */
    val versionedEntities: Set<String>,
    val hasResilience: Boolean,
    val hasDeadLetter: Boolean,
)

/** 클래스 하나를 검사하는 동안 쓰는 작업 공간. */
internal class Scope(val f: ConvFile, val c: Cls, val ctx: Context, private val out: MutableList<Hit>) {
    fun hit(ruleId: String, line: Int, message: String) { out += Hit(ruleId, f.path, line, message) }

    /** 멤버(또는 클래스) 선언 바로 위에 붙은 어노테이션 줄까지 포함한 원문. 어노테이션 인자(readOnly, rollbackFor 등)를 보기 위함이다. */
    fun annotationText(declLine: Int): String {
        val sb = StringBuilder(f.lines.getOrElse(declLine - 1) { "" })
        var i = declLine - 2
        while (i >= 0) {
            val l = f.lines[i].trim()
            if (l.isEmpty() || l.endsWith(";") || l.endsWith("{") || l.endsWith("}")) break
            sb.insert(0, l + "\n"); i--
        }
        return sb.toString()
    }
}

internal object Detectors {
    internal val beanAnnotations = setOf("Service", "Component", "RestController", "Controller", "Repository", "Configuration")
    internal val remoteType = Regex("""(RestTemplate|RestClient|WebClient|KafkaTemplate|RabbitTemplate|JmsTemplate|HttpClient|OkHttpClient|Feign\w*|\w+Client)\b""")
    internal val messagingType = Regex("""(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|\w*Publisher|\w*Producer)\b""")
    internal val repoType = Regex("""\w*(Repository|Dao|Mapper)\b""")
    internal val writeCall = Regex("""\.(save|saveAll|saveAndFlush|delete\w*|insert\w*|update\w*|persist|merge|remove\w*)\s*\(""")
    internal val readCall = Regex("""\.(find\w*|get\w*By\w*|load\w*|select\w*|exists\w*|read\w*)\s*\(""")
    internal val idempotency = Regex("""idempot|dedup|duplicate|alreadyProcessed|inbox""", RegexOption.IGNORE_CASE)
    private val lockText = Regex("""@Lock\b|FOR\s+UPDATE|PESSIMISTIC|ForUpdate|WithLock""", RegexOption.IGNORE_CASE)
    private val outboxText = Regex("""outbox|transactional.?event""", RegexOption.IGNORE_CASE)
    private val uniqueText = Regex("""uniqueConstraints|unique\s*=\s*true|\bUNIQUE\b|DuplicateKey|DataIntegrityViolation|ON\s+CONFLICT|INSERT\s+IGNORE""", RegexOption.IGNORE_CASE)
    private val schedulerLockText = Regex("""SchedulerLock|ShedLock|LockProvider|RedissonClient|distributed.?lock|\blease|SKIP\s+LOCKED|GET_LOCK|advisory|leader.?election""", RegexOption.IGNORE_CASE)

    internal fun context(files: List<ConvFile>, hasAllOpen: Boolean): Context {
        val main = files.filter { !it.test }
        fun anyLine(r: Regex) = main.any { f -> f.lines.any { r.containsMatchIn(it) } }
        return Context(
            hasVersion = main.any { f -> f.annotated.any { "Version" in it.annotations } },
            hasLock = anyLine(lockText),
            hasIdempotency = anyLine(idempotency),
            hasOutbox = anyLine(outboxText),
            hasUniqueGuard = anyLine(uniqueText),
            hasSchedulerLock = anyLine(schedulerLockText),
            hasAllOpen = hasAllOpen,
            versionedEntities = versionedEntities(main),
            hasResilience = anyLine(Regex("""CircuitBreaker|Resilience4j|Hystrix|Bulkhead|TimeLimiter""")),
            hasDeadLetter = anyLine(Regex("""RetryableTopic|DeadLetter|\\bDLT\\b|\\bDLQ\\b|dead.?letter""", RegexOption.IGNORE_CASE)),
        )
    }

    private fun versionedEntities(main: List<ConvFile>): Set<String> = main.flatMap { f ->
        StructureParser.classes(f).filter { c -> c.fields.any { "Version" in it.annotations } }.flatMap { c ->
            val header = (maxOf(0, c.line - 6) until c.line).joinToString("\n") { f.lines.getOrElse(it) { "" } }
            listOfNotNull(c.name.lowercase(), Regex("""@Table\s*\(\s*name\s*=\s*"([^"]+)"""").find(header)?.groupValues?.get(1)?.lowercase())
        }
    }.toSet()

    private fun moduleKey(name: String) = name.trimStart(':').substringAfterLast(':').lowercase()

    /** 모듈과 그 모듈이 (간접적으로) 의존하는 모듈들. 공용 모듈이 제공하는 보호 장치(outbox, 멱등성 등)를 쓰는 쪽에도 인정하기 위함이다. */
    internal fun scopeOf(module: String, dependencies: Map<String, List<String>>): Set<String> {
        val graph = dependencies.entries.associate { moduleKey(it.key) to it.value.map(::moduleKey) }
        val seen = mutableSetOf(moduleKey(module))
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) for (d in graph[queue.removeFirst()].orEmpty()) if (seen.add(d)) queue.addLast(d)
        return seen
    }

    /** 보호 장치 판단은 프로젝트 전체가 아니라 그 파일이 속한 모듈과 의존 모듈 안에서만 한다. 모듈 정보가 없으면 전체를 쓴다. */
    fun detect(files: List<ConvFile>, hasAllOpen: Boolean = true, dependencies: Map<String, List<String>> = emptyMap()): List<Hit> {
        val contexts = mutableMapOf<String, Context>()
        val whole by lazy { context(files, hasAllOpen) }
        fun contextFor(f: ConvFile): Context {
            if (f.module.isEmpty() || dependencies.isEmpty()) return whole
            return contexts.getOrPut(f.module) {
                val scope = scopeOf(f.module, dependencies)
                context(files.filter { moduleKey(it.module) in scope }, hasAllOpen)
            }
        }
        return files.filter { !it.test }.flatMap { f ->
            val ctx = contextFor(f)
            // 휴리스틱 파서가 예상 못 한 소스 때문에 게이트 전체를 중단시키지 않도록 파일 단위로 격리한다.
            runCatching {
                val out = mutableListOf<Hit>()
                val classes = StructureParser.classes(f)
                for (c in classes) {
                    val scope = Scope(f, c, ctx, out)
                    TransactionDetectors.run(scope)
                    ConcurrencyDetectors.run(scope)
                    DistributedDetectors.run(scope)
                    ResilienceDetectors.run(scope)
                }
                ConcurrencyDetectors.companions(f, out)
                ResilienceDetectors.file(f, out)
                out.toList()
            }.getOrDefault(emptyList())
        }
    }

    /** 중괄호로 묶인 블록의 내용. [open] 은 여는 `{` 의 위치다. */
    internal fun braceBlock(text: String, open: Int): String {
        var d = 0
        for (k in open until text.length) {
            if (text[k] == '{') d++ else if (text[k] == '}') { d--; if (d == 0) return text.substring(open + 1, k) }
        }
        return text.substring(open + 1)
    }
}
