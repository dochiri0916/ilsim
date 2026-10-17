# 동시성 명세 통합과 구현 진행 현황

> 이 문서는 `scripts/render_concurrency_progress.py`로 생성한다. 진행 상태는 `src/main/resources/concurrency/specification.json`에서 수정한다.

[원본 명세](java-mysql-concurrency-spec.md) · [배포 카탈로그](../../src/main/resources/concurrency/specification.json)

구현 진행 현황이다. PARTIAL·PLANNED는 명세 완료나 현재 스캔의 안전성을 뜻하지 않는다. 위험 scan은 Java 전용이며 build-convention은 선택 사항이다.

## 조회

`CRA`는 `java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar`다.

```bash
CRA rules --spec
CRA rules --spec --format json
CRA rules DB-CONC-009
CRA rules CON-002
```

기존 `rules` 목록·규칙 ID·설정·기준선·CI 게이트는 유지한다. 명세 ID는 조회·보고용 대응 정보이며 `.code-risk.yml`의 `rules` 키에 사용하는 실행 규칙 ID를 대신하지 않는다. `rules --spec`의 상태는 이 분석기 버전의 구현 진행 상태이며, 대상 프로젝트에서 규칙이 활성화되거나 실행되었다는 뜻이 아니다.

## 상태와 우선순위

| 상태 | 기준 |
|---|---|
| PLANNED | 자체 분석 구현을 아직 연결하지 않았다. 보조 도구의 기본 검사도 완료로 세지 않는다. |
| PARTIAL | 실제 실행 규칙·구현 파일·기존 테스트 근거가 있으나 명세의 남은 요건이 있다. |
| IMPLEMENTED | 요구된 frontend·트리거·보호 조건·판정·증거 경로를 구현했다. 전체 동적 검증은 남을 수 있다. |
| VALIDATED | IMPLEMENTED 요건에 더해 명세 7절의 fixture와 실제 동시 실행 검증을 통과했다. |

| 우선순위 | 작업 순서 |
|---|---|
| P0 | 공통 입력·판정·호환성 기반을 먼저 준비한다. |
| P1 | 기존 핵심 대응 유형을 보강하고 DCL·락 해제 검사를 분석기에 연결한다. |
| P2 | JVM 공유성·원자성 및 SQL 기반 불변식·bulk DML·잠금 유지 구간을 구현한다. |
| P3 | 운영·격리·다중 행 조건에 따른 후보 및 독립 리뷰 흐름을 구현한다. |
| P4 | 가치가 낮고 환경 의존적인 반복 조회 검사를 마지막에 검토한다. |

우선순위는 구현 순서이며 finding의 severity가 아니다. 상태를 IMPLEMENTED 또는 VALIDATED로 올릴 때는 전체 명세 요건을 확인하고 구현·테스트 근거와 남은 작업을 함께 갱신한다. 기존 fixture 통과만으로 MySQL·JMM·다중 인스턴스 검증 완료를 표시하지 않는다.

현재 22개: 부분 구현 10개, 미구현 12개, 구현 완료 0개, 전체 검증 완료 0개.

## 공통 기반과 운영 흐름

| 항목 | 우선순위 | 진행 상태 | 현재 범위 | 남은 작업 |
|---|---|---|---|---|
| CATALOG-INTEGRATION | P0 | IMPLEMENTED | 명세 22개 매핑·우선순위·진행 상태·CLI와 보고서 연결 | 이 통합 범위의 구현 완료 |
| ANALYSIS-CONTEXT | P0 | PARTIAL | Java AST·타입해석·기본 호출 그래프·보안 taint 기반 | 인터페이스 구현체·비동기 경로를 포함한 동시성 data-flow / 공유성·lockset·트랜잭션 의미론 모델 |
| SQL-SCHEMA-ENVIRONMENT | P0 | PLANNED | SQL·최종 DDL·실효 InnoDB 설정·불변식 모델 | 파생 쿼리/JPQL/native/QueryDSL/MyBatis/JDBC 정규화 / 스키마·환경·불변식의 검증과 UNKNOWN 표현 |
| KOTLIN-CONCURRENCY | P0 | PLANNED | Kotlin 동시성 frontend | object·companion·최상위 var·lazy·withLock·코루틴 분석 / Java/Kotlin 공통 모델 연결 |
| ASSESSMENT-AND-MERGING | P0 | PARTIAL | 기존 명세 대응·009/018 연결 및 Java DCL의 증거별 실제 판정·보호 입증 감사 기록 | 다른 실행 규칙에 UNKNOWN·SAFE_BY_PROOF 실제 판정 적용 / 011/009/018, 001/004/005, 016/022 교차 규칙 병합 / 명세 outcome 기반 게이트 정책 |
| STABLE-FINGERPRINT | P0 | PARTIAL | DCL의 줄 번호 없는 선언·증거 경로 지문과 기존 v1 지문 병행 저장·기준선 읽기 호환 | 다른 규칙에 선언·증거 경로 기반 지문 적용 / 기존 기준선 재생성 전까지 v1 항목의 줄 번호 의존 유지 |
| CONCURRENT-VALIDATION | P1 | PLANNED | 명세 7절의 22개 규칙 검증 묶음 | Java/Kotlin positive/negative/boundary/unknown fixture / JMM·실제 Spring/Hibernate·MySQL 2커넥션·다중 인스턴스/장애 주입 |
| AGENT-REVIEW-LOOP | P3 | PLANNED | 수정·재분석·독립 리뷰·Verdict 연결 | 같은 지문 3회 반복 상한 / 리뷰의 동시성 테스트 실행·Verdict 기록·수정 후 재분석 / 억제 후보 검토·정책 파일 변경 보호 |

## 22개 규칙 대응표

| 명세 ID | 문제 | 우선순위 | 진행 상태 | 기존 실행 규칙 |
|---|---|---|---|---|
| DB-CONC-009 | Lost Update | P1 | PARTIAL | CON-002 |
| DB-CONC-010 | 중복 데이터 생성 | P1 | PARTIAL | CON-001 |
| DIST-CONC-020 | JVM Lock만으로 DB 보호 | P1 | PARTIAL | CON-001¹ |
| DIST-CONC-021 | 중복 요청·멱등성 위반 | P1 | PARTIAL | IDEM-001, IDEM-002, IDEM-003, IDEM-004 |
| DIST-CONC-022 | DB 갱신·외부 효과 불일치 (Dual Write) | P1 | PARTIAL | REL-001 |
| JAVA-CONC-001 | 공유 변수 Race Condition | P1 | PARTIAL | CON-003¹ |
| JAVA-CONC-006 | 잘못된 Double-Checked Locking | P1 | PARTIAL | CON-005 |
| JAVA-CONC-007 | Lock 해제 누락 | P1 | PLANNED | 없음 |
| JPA-CONC-018 | 낙관적 락 보호 누락 | P1 | PARTIAL | CON-002 |
| SPRING-CONC-017 | 트랜잭션 적용 누락 | P1 | PARTIAL | TX-001 |
| DB-CONC-011 | 초과 차감·초과 판매 | P2 | PLANNED | 없음 |
| DB-CONC-016 | Lock Wait Timeout 위험 | P2 | PARTIAL | TX-003 |
| JAVA-CONC-002 | 메모리 가시성·안전하지 않은 공개 | P2 | PLANNED | 없음 |
| JAVA-CONC-003 | Java Deadlock | P2 | PLANNED | 없음 |
| JAVA-CONC-004 | 원자성 위반 | P2 | PLANNED | 없음 |
| JAVA-CONC-005 | 스레드 안전하지 않은 컬렉션 공유 | P2 | PLANNED | 없음 |
| JPA-CONC-019 | Bulk Update와 버전 보호 불일치 | P2 | PLANNED | 없음 |
| DB-CONC-012 | DB Deadlock | P3 | PLANNED | 없음 |
| DB-CONC-014 | Phantom Read / 범위 판정 경쟁 | P3 | PLANNED | 없음 |
| DB-CONC-015 | Write Skew | P3 | PLANNED | 없음 |
| JAVA-CONC-008 | Starvation·Livelock | P3 | PLANNED | 없음 |
| DB-CONC-013 | Non-repeatable Read | P4 | PLANNED | 없음 |

¹ JAVA-CONC-001은 `SHARED_FIELD_MUTATION`, DIST-CONC-020은 `JVM_LOCAL_LOCK` 증거가 있을 때만 연결한다.

## 구현 근거와 남은 작업

### DB-CONC-009 — Lost Update

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: 같은 메서드의 조회→기존 값 기반 수정→명시적 저장 및 소스 @Version·일부 PESSIMISTIC_WRITE를 검사한다.
- 구현 근거: [LostUpdateRiskRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/LostUpdateRiskRule.java)
- 테스트 근거: [RemainingV1RulesTests](../../src/test/java/com/example/coderiskanalyzer/rule/RemainingV1RulesTests.java)
- 남은 작업: 간접 호출·JPA 변경 감지/flush 경로; SQL CAS·영향 행 수·실효 트랜잭션 범위; 실제 MySQL 동시 트랜잭션 검증
- 검증 목표: 두 MySQL 커넥션에서 같은 행의 read-modify-write를 재현하고 버전·CAS·잠금 구현과 비교한다.

### DB-CONC-010 — 중복 데이터 생성

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: if 조건의 존재 확인→저장 후보와 엔티티의 단일·복합 UNIQUE/PK 컬럼 대응을 검사한다.
- 구현 근거: [CheckThenActRaceRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/CheckThenActRaceRule.java), [DatabaseUniquenessProtection](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/DatabaseUniquenessProtection.java)
- 테스트 근거: [CheckThenActRaceRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/CheckThenActRaceRuleTests.java)
- 남은 작업: 사전 조회 결과 변수·간접 호출 경로; 최종 DDL·NOT NULL·NULL 고유성 의미; 중복 키 예외 처리 및 동시 INSERT 검증
- 검증 목표: 같은 업무 키의 동시 INSERT, 복합 UNIQUE 컬럼 불일치, NULL과 중복 예외를 MySQL에서 검증한다.

### DIST-CONC-020 — JVM Lock만으로 DB 보호

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: CON-001이 synchronized 구간에서도 후보를 유지하고 JVM_LOCAL_LOCK 증거를 남기는 경우에만 연결한다.
- 구현 근거: [CheckThenActRaceRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/CheckThenActRaceRule.java)
- 테스트 근거: [CheckThenActRaceRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/CheckThenActRaceRuleTests.java)
- 남은 작업: 일반 Lock 구간·간접 리포지토리 쓰기 경로; 배포 인스턴스·다른 writer 입력; 커밋 전 락 해제·다중 인스턴스 Scheduled 경로
- 검증 목표: 두 애플리케이션 인스턴스의 JVM 락과 DB 보호를 비교하고 스케줄러 중복 실행을 검증한다.

### DIST-CONC-021 — 중복 요청·멱등성 위반

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: 리스너 부수효과·재시도·외부 멱등성 키·업무 키의 가시적 근거를 검사한다.
- 구현 근거: [MessageConsumerSideEffectRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/idempotency/MessageConsumerSideEffectRule.java), [ExternalSideEffectWithoutIdempotencyKeyRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/idempotency/ExternalSideEffectWithoutIdempotencyKeyRule.java), [RetryableNonIdempotentOperationRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/idempotency/RetryableNonIdempotentOperationRule.java), [EventHandlerWithoutStableBusinessKeyRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/idempotency/EventHandlerWithoutStableBusinessKeyRule.java)
- 테스트 근거: [MessageConsumerSideEffectRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/idempotency/MessageConsumerSideEffectRuleTests.java), [RemainingV1RulesTests](../../src/test/java/com/example/coderiskanalyzer/rule/RemainingV1RulesTests.java)
- 남은 작업: 키 전파·사용자/테넌트 UNIQUE 범위; 동일 트랜잭션 결과 기록·payload 충돌·진행 상태; 재전달·동시 같은 키·장애 주입 검증
- 검증 목표: 동시 같은 키 요청, payload 불일치, 커밋 후 재시도 및 메시지 재전달을 검증한다.

### DIST-CONC-022 — DB 갱신·외부 효과 불일치 (Dual Write)

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: 같은 메서드의 DB 쓰기와 HTTP/메시지 발행 및 보호 관련 이름의 근거를 검사한다.
- 구현 근거: [DatabaseExternalSideEffectConsistencyRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/reliability/DatabaseExternalSideEffectConsistencyRule.java)
- 테스트 근거: [RemainingV1RulesTests](../../src/test/java/com/example/coderiskanalyzer/rule/RemainingV1RulesTests.java)
- 남은 작업: 간접 호출·실제 트랜잭션 경계; Outbox의 동일 datasource/트랜잭션·발행기 재시도·소비자 중복 제거; 문자열 보호 근거의 의미 검증 및 장애 주입
- 검증 목표: 커밋 직후 장애, 중복 발행·재소비, Outbox 원자성과 외부 API 보상 정책을 검증한다.

### JAVA-CONC-001 — 공유 변수 Race Condition

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: 진입점 애너테이션이 있는 싱글턴 빈의 인스턴스 필드 대입·증감과 synchronized 구간을 검사한다.
- 구현 근거: [SharedMutableSingletonStateRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/SharedMutableSingletonStateRule.java)
- 테스트 근거: [SharedMutableSingletonStateRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/SharedMutableSingletonStateRuleTests.java)
- 남은 작업: 일반 서비스·static 필드 및 호출 경로의 공유성 분석; 동일 락·Atomic 보호의 범위 검증; Kotlin 형태 및 동시 실행 검증
- 검증 목표: 요청 진입점과 간접 서비스 호출의 공유 필드 경합을 barrier로 재현하고 동기화 구현과 비교한다.

### JAVA-CONC-006 — 잘못된 Double-Checked Locking

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: Java 동일 필드의 이중 null 검사·monitor·대입을 식별한다. 비-volatile·공개 후 직접 상태 쓰기는 DEFINITE_PATTERN, 좁은 volatile 생성자 공개는 SAFE_BY_PROOF, 미해결 선언·간접 초기화·monitor 근거 부족은 UNKNOWN으로 기록한다.
- 구현 근거: [DoubleCheckedLockingRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/DoubleCheckedLockingRule.java)
- 테스트 근거: [DoubleCheckedLockingRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/DoubleCheckedLockingRuleTests.java), [ConcurrencyScanTests](../../src/test/java/com/example/coderiskanalyzer/cli/ConcurrencyScanTests.java), [BaselineServiceTests](../../src/test/java/com/example/coderiskanalyzer/baseline/BaselineServiceTests.java)
- 남은 작업: 로컬 캐시 별칭·일반 Lock·간접 초기화 및 생성자 부수효과·공개 순서 정밀 분석; Kotlin lazy(NONE) 및 Java/Kotlin 경계 fixture; JMM의 불안전 공개 재현·jcstress 검증
- 검증 목표: non-volatile DCL, 올바른 volatile DCL, holder 및 Kotlin lazy 모드를 비교한다.

### JAVA-CONC-007 — Lock 해제 누락

- 우선순위·상태: P1 · PLANNED
- 현재 범위: 자체 검사 없음. 선택적 build-convention SpotBugs에 UL_UNRELEASED_LOCK 기본 검사가 연결되어 있다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 정상·예외 경로의 acquire/release 균형 검사; 락 별칭·해제 래퍼·Kotlin withLock 분석
- 검증 목표: 예외 경로 해제 누락과 finally/withLock 구현을 비교한다.

### JPA-CONC-018 — 낙관적 락 보호 누락

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: CON-002의 read-modify-persist와 @Version 검사로 009와 함께 대응한다.
- 구현 근거: [LostUpdateRiskRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/LostUpdateRiskRule.java)
- 테스트 근거: [RemainingV1RulesTests](../../src/test/java/com/example/coderiskanalyzer/rule/RemainingV1RulesTests.java)
- 남은 작업: 동시 변경 경로·변경 감지 모델; 버전 보호 적용 범위·CAS 및 새 트랜잭션 재시도; Hibernate flush 통합 검증
- 검증 목표: 동일 엔티티의 버전 충돌과 새 트랜잭션 재조회·재시도를 MySQL/Hibernate에서 검증한다.

### SPRING-CONC-017 — 트랜잭션 적용 누락

- 우선순위·상태: P1 · PARTIAL
- 현재 범위: 같은 객체의 @Transactional 자기 호출과 바깥 트랜잭션·전파 지정 여부를 일부 검사한다.
- 구현 근거: [TransactionalSelfInvocationRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/transaction/TransactionalSelfInvocationRule.java)
- 테스트 근거: [TransactionalSelfInvocationRuleTests](../../src/test/java/com/example/coderiskanalyzer/rule/transaction/TransactionalSelfInvocationRuleTests.java)
- 남은 작업: Bean·JDK/CGLIB/AspectJ 프록시 설정; final·인터페이스·접근제어·트랜잭션 밖 @Lock; 실제 Spring 프록시 통합 검증
- 검증 목표: 자기 호출·외부 호출·바깥 트랜잭션·REQUIRES_NEW 및 프록시 유형을 실제 Spring에서 비교한다.

### DB-CONC-011 — 초과 차감·초과 판매

- 우선순위·상태: P2 · PLANNED
- 현재 범위: 업무 불변식과 조건부 UPDATE를 검사하는 자체 규칙 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: NON_NEGATIVE 등 불변식 입력; SQL WHERE 보호·영향 행 수 검사; 009·018과 우선순위 병합
- 검증 목표: 조건부 UPDATE 반환값 무시와 병렬 차감 후 수량·성공 수를 MySQL에서 검증한다.

### DB-CONC-016 — Lock Wait Timeout 위험

- 우선순위·상태: P2 · PARTIAL
- 현재 범위: @Transactional 메서드·클래스 내부의 직접 HTTP 호출 후보를 검사한다.
- 구현 근거: [RemoteCallInsideTransactionRule](../../src/main/java/com/example/coderiskanalyzer/production/rule/transaction/RemoteCallInsideTransactionRule.java)
- 테스트 근거: [RemainingTransactionRulesTests](../../src/test/java/com/example/coderiskanalyzer/rule/transaction/RemainingTransactionRulesTests.java)
- 남은 작업: 락 획득 이후 간접 HTTP·대기·I/O·반복 경로; WHERE와 인덱스·timeout/rollback 정책; REQUIRES_NEW 연결 풀 고갈 경로
- 검증 목표: MySQL 잠금 보유 중 외부 대기와 timeout 이후 명령문·전체 트랜잭션 상태를 검증한다.

### JAVA-CONC-002 — 메모리 가시성·안전하지 않은 공개

- 우선순위·상태: P2 · PLANNED
- 현재 범위: 자체 검사 없음. 선택적 build-convention의 SpotBugs 기본 검사와는 별개다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 비-volatile 공유 플래그와 생성자 this 유출 탐지; happens-before 근사 및 Kotlin @Volatile 검증
- 검증 목표: 공유 플래그와 생성자 공개를 JMM 동시 실행 테스트로 검증한다.

### JAVA-CONC-003 — Java Deadlock

- 우선순위·상태: P2 · PLANNED
- 현재 범위: 자체 검사 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 중첩 락 획득 후보와 간접 호출 탐지; 락 별칭·획득 순서 및 재진입 구분
- 검증 목표: 반대 순서의 중첩 락과 순서가 일치하는 구현을 타임아웃과 barrier로 비교한다.

### JAVA-CONC-004 — 원자성 위반

- 우선순위·상태: P2 · PLANNED
- 현재 범위: JVM 컬렉션·Atomic 연산 조합의 자체 검사 없음. CON-001은 DB check-then-act 검사다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: containsKey→put 및 Atomic get→set 조합 탐지; 동일 객체·키·임계영역 검증
- 검증 목표: ConcurrentHashMap check-then-put과 computeIfAbsent, Atomic get/set과 CAS를 비교한다.

### JAVA-CONC-005 — 스레드 안전하지 않은 컬렉션 공유

- 우선순위·상태: P2 · PLANNED
- 현재 범위: 컬렉션 내용 변경을 검사하는 자체 규칙 없음. CON-003은 필드 대입·증감만 검사한다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: final 컬렉션 필드의 내용 변경과 순회 분석; concurrent 컬렉션·외부 락·불변 스냅샷 검증
- 검증 목표: 싱글턴의 final HashMap/ArrayList 내용 변경과 concurrent·불변 구현을 비교한다.

### JPA-CONC-019 — Bulk Update와 버전 보호 불일치

- 우선순위·상태: P2 · PLANNED
- 현재 범위: bulk DML의 버전·컨텍스트 보호 자체 검사 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: JPQL/native/QueryDSL bulk DML 및 엔티티 매핑; 버전 조건·증가·영향 행 수·flush/clear 정책
- 검증 목표: 버전 엔티티 bulk UPDATE의 충돌·영향 행 수·영속성 컨텍스트 stale 상태를 MySQL/Hibernate에서 비교한다.

### DB-CONC-012 — DB Deadlock

- 우선순위·상태: P3 · PLANNED
- 현재 범위: InnoDB 잠금 순서 자체 검사 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 트랜잭션별 다중 행 접근 순서 후보; SQL·인덱스·FK 잠금 매핑과 전체 트랜잭션 재시도
- 검증 목표: 두 MySQL 트랜잭션의 반대 행 잠금 순서와 정렬된 순서를 barrier로 비교한다.

### DB-CONC-014 — Phantom Read / 범위 판정 경쟁

- 우선순위·상태: P3 · PLANNED
- 현재 범위: InnoDB 범위 판정 자체 검사 없음. CON-001의 일반 count/exists 후보는 완전한 범위 분석이 아니다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 범위 predicate·삽입/삭제 경로 추적; consistent read·잠금 읽기·인덱스 보호의 구분
- 검증 목표: 동시 INSERT와 범위 조회·잠금 읽기를 격리 수준별 MySQL 테스트로 비교한다.

### DB-CONC-015 — Write Skew

- 우선순위·상태: P3 · PLANNED
- 현재 범위: 업무 불변식·집계 집합에 대한 자체 검사 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 집계 기반 다중 행 불변식 입력; COUNT/SUM/EXISTS→분기→서로 다른 행 갱신 후보; 공통 guard row·직렬화 보호 검증
- 검증 목표: 동일 집계를 읽고 다른 행을 갱신하는 두 MySQL 트랜잭션과 공통 잠금 구현을 비교한다.

### JAVA-CONC-008 — Starvation·Livelock

- 우선순위·상태: P3 · PLANNED
- 현재 범위: 자체 검사 없음.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 상한 없는 tryLock·CAS·재시도 루프 후보; 종료 조건·백오프·jitter·공정성 검토
- 검증 목표: 유한·무한 경쟁 루프를 fixture로 비교하고 부하 테스트에서 진행 여부를 확인한다.

### DB-CONC-013 — Non-repeatable Read

- 우선순위·상태: P4 · PLANNED
- 현재 범위: 실효 격리 수준·반복 조회 자체 검사 없음. 구현 시 기본 비활성 정책이 필요하다.
- 구현 근거: 없음
- 테스트 근거: 없음
- 남은 작업: 세션·트랜잭션 매니저의 실효 isolation 입력; 반복 SQL 및 JPA 1차 캐시·업무 의존성 검증
- 검증 목표: READ COMMITTED/REPEATABLE READ의 동일 행 반복 조회를 별도 MySQL 커넥션으로 비교한다.

## 보고서와 판정 호환성

JSON의 `concurrencySpecification`, SARIF run의 같은 이름 properties는 전체 구현 진행표다. finding의 `concurrency`에는 실제 증거에 맞는 명세 ID, 대표 ID, outcome, 우선순위, 구현 상태, 한계, 권장 테스트가 들어간다. 적용되지 않는 finding에는 이 필드를 붙이지 않는다.

CON-002 한 finding에 DB-CONC-009와 JPA-CONC-018을 함께 연결하고 009를 대표로 선택한다. 추가 검사를 실행하거나 같은 finding을 복제하지 않는다. 서로 다른 실행 규칙의 finding 병합은 후속 작업이다.

outcome은 기존 CONFIRMED/REVIEW를 일괄 치환하지 않는다. SHARED_FIELD_MUTATION 증거가 있는 CON-003은 명세의 좁은 패턴을 확인한 DEFINITE_PATTERN, TX-003은 실제 잠금·대기 검증이 필요한 REQUIRES_VALIDATION으로 연결한다. TX-001을 포함한 나머지 연결은 POTENTIAL_RISK다. CON-005는 Java DCL의 실제 증거로 DEFINITE_PATTERN·UNKNOWN·SAFE_BY_PROOF를 판정한다. 다른 규칙의 실제 판정과 outcome 기반 게이트는 후속 작업이다.

JSON의 `concurrencyAssessments`, SARIF run properties의 같은 이름 배열에는 경고와 별도의 규칙별 감사 기록을 남긴다. SAFE_BY_PROOF는 해당 공개 경로에 한정된 보호 입증이며 finding으로 만들거나 경고 수·CI 게이트에 포함하지 않는다. UNKNOWN은 누락 근거를 가진 REVIEW finding이다. 감사 기록은 suppression·baseline 적용 전 판정이고, 최종 경고 상태는 findings/results를 따른다.

CON-005의 finding과 감사 기록에는 선언·대상 필드·증거 경로 기반 `v2` 지문이 있다. 줄 이동·주석 추가에는 유지되고 오버로드·공개 경로 변경에는 달라진다. 새 기준선은 기존 v1 `fingerprint`와 선택적 `stableFingerprint`를 병행 저장하며, 분석기는 기존 v1 기준선을 계속 읽는다. v2가 있는 항목은 v2로 비교해 같은 줄의 다른 경로를 억제하지 않는다. 기존 v1 항목은 재생성 전까지 줄 번호를 사용한다. 다른 규칙의 지문은 기존 방식이다.

기존 finding status·severity·confidence 및 suppression·baseline 처리와 CI 종료 코드는 유지한다. 따라서 현재 CI 게이트가 명세 6절의 DEFINITE_PATTERN 전용 게이트와 같다고 해석하지 않는다. 억제 finding은 기존 SUPPRESSED 상태와 대응 정보를 함께 보존한다.

## 진행표 검증

```bash
python3 scripts/render_concurrency_progress.py --check
../gradlew -p .. :code-risk-analyzer:clean :code-risk-analyzer:build --no-daemon
```

`check`는 진행 문서가 카탈로그와 일치하는지 검사한다. 테스트는 22개 ID의 완전성, 실행 규칙·구현 파일·테스트 근거의 존재, 조건부 매핑, 중복 없는 대응, CLI·JSON·SARIF 호환성을 확인한다. 이 검사들은 개별 동시성 규칙의 전체 런타임 검증을 대신하지 않는다.
