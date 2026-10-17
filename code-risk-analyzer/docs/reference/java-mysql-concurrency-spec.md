# Java·Kotlin(JVM) + MySQL 동시성 문제: 22개 정적 분석 규칙 및 구현 명세

> **프로젝트 통합 현황:** [`code-risk-analyzer` 구현 진행표](concurrency.md)에서 기존 규칙 대응, 구현 우선순위, 진행 상태와 테스트 근거를 확인한다. `rules --spec`으로도 조회할 수 있다. 명세 요건과 실제 구현 진행 상태는 구분한다.

> **목표:** Java/Spring/JPA/MySQL 시스템에 존재하는 22개 동시성 위험 유형 **전부를 분석 대상으로 구현**한다. 단, **22개 유형을 검사하는 것**과 **프로그램에 존재하는 모든 동시성 오류를 100% 검출하는 것**은 다르다. 특히 DB 실행 계획, 운영 중 병렬 실행 순서, 외부 서비스 상태, 업무 불변식에 따라 달라지는 문제는 정적 분석 결과를 *잠재 위험*으로 표시하고 추가 검증한다.
>
> **정적 분석기의 역할:** 이 명세의 분석기는 단독 판정 도구가 아니다. (1) 확실한 패턴을 차단하는 게이트이자 (2) 에이전트 리뷰에 넘길 후보 생성기다. 정적 분석으로 확정할 수 없는 문제는 후보로 표시하고 에이전트가 검증한다(6절).
>
> **분석 환경 가정:** JDK 21~25, Java 및 Kotlin 2.3.x 소스, Spring Boot/Spring Framework, Spring Data JPA/Hibernate(QueryDSL 포함), JDBC/MyBatis가 혼재할 수 있는 코드베이스, MySQL **InnoDB 8.4 기준**. 다른 버전 및 설정은 따로 확인해야 한다. JDK 26 이상은 Kotlin 2.3.x의 바이트코드 타깃 상한(25)에 걸리고 SpotBugs의 지원 여부가 확인되지 않았다.
>
> **기준 자료:** [IN-COM — 정적 분석은 멀티스레드/동시 코드를 어떻게 처리하는가?](https://www.in-com.com/ko/blog/how-does-static-code-analysis-handle-multi-threaded-or-concurrent-code/). 이 글의 데이터 흐름·제어 흐름·스레드 안전·잠금·원자성 분석 접근을 **Java와 MySQL에 맞게 구체화**한 독립 설계 문서다.

## 1. 범위와 현실적 검출 수준

| 등급 | 정의 | 결과 표현 | 흐름(6절)에서의 역할 |
|---|---|---|---|
| D1 — 명확한 위반 | 코드와 알려진 프레임워크 의미론만으로 특정 패턴을 확인 | `DEFINITE_PATTERN` (문제 발생까지 확정된 것은 아닐 수 있음) | **차단**: 0건이 되어야 리뷰 단계로 진행 |
| D2 — 조건부 위험 | 호출 관계·공유성·DB 설정 등 추가 조건에 따라 위험 | `POTENTIAL_RISK` | **후보**: 리뷰 에이전트의 입력 |
| D3 — 추가 검증 필요 | 실행 계획·운영 경합·업무 불변식·다른 프로세스 정보가 필요 | `REQUIRES_VALIDATION` | **후보**: 리뷰 에이전트의 입력, 동시성 테스트로 확인 |
| 근거 부족 | 판정에 필요한 입력이 없음 | `UNKNOWN` | **후보**: 누락된 근거와 함께 전달 |
| 보호 입증 | 해당 규칙·분석 범위에서 보호 수단을 확인 | `SAFE_BY_PROOF` | 보고하지 않고 기록만 남김 |

**중요:** D1도 "이 코드 경로가 반드시 실행되어 장애가 난다"는 뜻이 아니다. 22개 규칙을 모두 구현하되, 분석 근거가 부족하면 `UNKNOWN`을 반환한다. `UNKNOWN`을 **안전함**으로 간주하지 않는다.

**차단과 후보의 작성 기준:** 차단형 규칙(`DEFINITE_PATTERN`)은 좁고 확실하게 작성한다. 고칠 수 없는 오탐이 차단에 섞이면 수정 반복이 끝나지 않는다. 후보형 규칙은 넓게 작성한다. 에이전트는 분석기가 내놓은 후보만 보므로 후보 단계의 누락은 아무도 보지 않는다. 한 규칙이 차단형과 후보형을 모두 가질 수 있다(2.3절).

### 1.1 검사 대상 22개

| ID | 문제 | 대상 | 초기 검출 수준 | 기본 조치 |
|---|---|---|---|---|
| JAVA-CONC-001 | 공유 변수 Race Condition | JVM | D2 | 공유 상태 차단·동기화·Atomic |
| JAVA-CONC-002 | 메모리 가시성·안전하지 않은 공개 | JVM | D2 | `volatile`, 안전한 공개, Lock |
| JAVA-CONC-003 | Java Deadlock | JVM | D2 | 전역 Lock 획득 순서 |
| JAVA-CONC-004 | 원자성 위반 | JVM | D2 | 원자적 API·임계영역 |
| JAVA-CONC-005 | 스레드 안전하지 않은 컬렉션 공유 | JVM | D2 | Concurrent 컬렉션·동기화 |
| JAVA-CONC-006 | 잘못된 Double-Checked Locking | JVM | D1 | `volatile`·초기화 순서 개선 |
| JAVA-CONC-007 | Lock 해제 누락 | JVM | D1 | `try/finally` |
| JAVA-CONC-008 | Starvation·Livelock | JVM | D3 | 유한 재시도·백오프·공정성 |
| DB-CONC-009 | Lost Update | InnoDB | D2 | CAS·`@Version`·Lock |
| DB-CONC-010 | 중복 데이터 생성 (Check-Then-Insert) | InnoDB | D2 | 적절한 UNIQUE + 예외 처리 |
| DB-CONC-011 | 초과 차감·초과 판매 | InnoDB | D2 | 조건부 원자적 UPDATE |
| DB-CONC-012 | DB Deadlock | InnoDB | D3 | 접근 순서 통일·재시도 |
| DB-CONC-013 | Non-repeatable Read | InnoDB | D2/D3 | 격리 수준·잠금·업무 재검증 |
| DB-CONC-014 | Phantom Read / 범위 판정 경쟁 | InnoDB | D3 | 적절한 범위 보호·제약·재설계 |
| DB-CONC-015 | Write Skew | InnoDB | D3 | 공통 잠금 대상·직렬화 |
| DB-CONC-016 | Lock Wait Timeout 위험 | InnoDB | D3 | 잠금 시간 단축·인덱스·예외 정책 |
| SPRING-CONC-017 | 트랜잭션 적용 누락 | Spring | D1/D2 | 프록시 경유 호출·설정 수정 |
| JPA-CONC-018 | 낙관적 락 보호 누락 | JPA | D2 | `@Version`·CAS·Lock |
| JPA-CONC-019 | Bulk Update와 버전 보호 불일치 | JPA | D2 | 버전 조건/갱신·컨텍스트 관리 |
| DIST-CONC-020 | JVM Lock만으로 DB 보호 | 여러 JVM | D2 | DB 원자성·DB/분산 잠금 |
| DIST-CONC-021 | 중복 요청·멱등성 위반 | API/DB | D2/D3 | 멱등성 키·UNIQUE·상태 전이 |
| DIST-CONC-022 | DB 갱신·외부 효과 불일치 (Dual Write) | DB/API/메시지 | D2/D3 | Transactional Outbox 등 |

## 2. 정적 분석 엔진의 공통 입력과 아키텍처

```text
Java·Kotlin source / bytecode / build files
        │
        ├─ AST(Java·Kotlin) + 타입해석 + call graph + CFG + data-flow
        │    ├─ 필드 escape/shared-state 분석
        │    ├─ lockset/happens-before 근사 분석
        │    ├─ lock-order graph / 원자성 패턴
        │    └─ Spring AOP/트랜잭션 경로
        │
        ├─ ORM 및 SQL 추출
        │    ├─ @Entity / @Version / @Table / @Column
        │    ├─ @Query / @Modifying / JPQL / native SQL
        │    ├─ Spring Data 파생 쿼리(메서드 이름) / QueryDSL / 변경 감지
        │    ├─ MyBatis XML / JDBC SQL
        │    └─ Entity -> Repository -> SQL -> table 매핑
        │
        ├─ MySQL 설정 및 DDL
        │    ├─ Flyway/Liquibase/DDL, PK/UNIQUE/INDEX/FK
        │    ├─ InnoDB 여부, isolation/autocommit
        │    └─ 가능하면 읽기 전용 EXPLAIN/운영 지표 (동적 보완)
        │
        ├─ 멀티 인스턴스 / 외부 호출 의미론
        │    ├─ REST/gRPC/메시지 발행, retry, idempotency
        │    └─ 서버 인스턴스 수 / 스케줄러 / 병렬 소비자
        │
        └─ 업무 불변식 메타데이터 (명시적 규칙 파일)
             ├─ inventory.quantity >= 0
             ├─ unique(product.customer_account_id, sku)
             └─ one_active_assignment_per_task 등
                        │
                  22개 규칙 엔진
                        │
         Finding: ruleId, fingerprint, severity, evidencePath, confidence,
                  assumptions, missingEvidence, remediation,
                  testTemplate, outcome
```

### 2.1 반드시 필요한 추론

최종 판정은 에이전트 리뷰가 이어받는다. 따라서 1~4번은 정밀 분석 대신 **규약형 과잉 근사**로 먼저 구현한다. 예: escape 분석 대신 "싱글턴 빈의 가변 필드 금지", lock-order 그래프 대신 "락 보유 중 다른 락 획득 금지". 정밀 추론은 규약형 규칙의 오탐이 실제로 문제가 될 때만 추가한다.

1. **공유 가능성(escape) 분석**: 로컬 변수/요청별 객체와 Singleton 필드를 구별한다.
2. **Interprocedural data-flow**: 서비스에서 가져온 값을 다른 메서드에서 검증·수정해도 같은 데이터임을 추적한다.
3. **Call graph**: 가상 호출, 프록시, 비동기 작업(`@Async`, `Executor`, `CompletableFuture`, 스케줄러, 메시지 리스너)을 고려한다.
4. **Lockset / happens-before의 보수적 근사**: 모든 스레드 간 실행을 열거하기 어렵다. 알려진 동기화 보장만 사용한다.
5. **JPA 실행 의미론**: 변경 감지 시 flush/commit 시점에 UPDATE가 발생하는 경로까지 모델링한다.
6. **트랜잭션 경계 분석**: Spring 프록시 적용, 전파/격리 수준, 예외 롤백, `REQUIRES_NEW`, 비동기 경계 등을 고려한다.
7. **SQL + schema 정규화**: 테이블/컬럼/조건/인덱스/고유성/원자적 갱신 여부를 비교한다.
8. **환경 설정**: 기본 격리 수준을 코드에서 지정하지 않았다는 이유만으로 단정하지 않는다. 실제 설정이 미확인이라면 `UNKNOWN`으로 표시한다.
9. **도메인 규칙**: 재고·한도·중복 등은 코드만으로 의미를 알 수 없으므로 도메인 규칙 파일 또는 애너테이션이 필요하다.
10. **불완전성 표시**: 동적 SQL, reflection, 원격 서비스, native code, DB 트리거, 기타 서비스의 쓰기 경로가 누락되면 발견 근거에 명시한다.
11. **Kotlin 의미론**: `object`·`companion object`·최상위 `var`, `@Volatile`, `@Synchronized`, `lazy`의 스레드 안전 모드, 코루틴(`suspend`)을 공유 상태·동기화 모델에 포함한다. `withLock`과 `synchronized { }`는 인라인되어 바이트코드에서 형태가 사라지므로 소스에서 본다.
12. **문자열이 아닌 쿼리**: Spring Data 파생 쿼리는 메서드 이름(`PartTree`)으로, JPQL은 Hibernate의 HQL 파서로 해석한다. QueryDSL은 호출 체인에서 대상 엔티티와 갱신 컬럼을 추출하고, 조건을 해석할 수 없으면 `UNKNOWN`으로 표시한다. 변경 감지에 의한 UPDATE는 엔티티 변경 메서드 호출로 식별한다.
13. **인터페이스 경유 호출**: 서비스 → 포트 → 어댑터 → 리포지토리처럼 인터페이스를 거치는 호출은 구현체까지 따라간다.

### 2.2 분석 층과 도구

규칙이 읽는 대상에 따라 세 층으로 나눈다. JPA·Spring 의미론은 언어와 무관하므로 가능한 한 JVM 층에 작성하고, 바이트코드에서 형태가 사라지는 것만 소스 층에 작성한다.

| 층 | 읽는 대상 | 도구 (2026-10-09 확인 버전) | 주의 |
|---|---|---|---|
| **JVM** | 바이트코드, 런타임 메타모델 | ArchUnit 1.5.1, SpotBugs 4.10.4 + Gradle 플러그인 6.5.12(커스텀 디텍터), Hibernate 메타모델 테스트 | SpotBugs는 Kotlin을 공식 지원하지 않아 Kotlin 생성 코드에서 오탐이 난다. ArchUnit에는 전이 호출 순회가 없어 직접 구현한다 |
| **Kotlin** | Kotlin 소스 | detekt 2.0.0-alpha.3 커스텀 규칙, 선언 수준 검사는 Konsist | alpha.3이 Kotlin 2.3.21 기준 빌드다. 2.x는 알파뿐이고 알파 사이에 규칙 API가 깨질 수 있다. 1.23.x는 Kotlin 2.0.21까지만 지원한다 |
| **Java** | Java 소스 | Error Prone 내장 검사, 필요 시 커스텀 `BugChecker` | Kotlin에는 적용할 수 없다. JVM 층이 이미 덮는 규칙은 중복 작성하지 않는다 |

JDK 호환: 세 층의 도구 모두 JDK 21과 25에서 사용할 수 있다. ArchUnit 1.5.x는 Java 27까지 지원한다. SpotBugs는 JDK 25까지만 확인되었다. SpotBugs 플러그인과 Gradle 9.x 조합은 공식 문서에 명시가 없으므로 도입 시 실행으로 확인한다.

보조 도구:

| 도구 | 역할 | 주의 |
|---|---|---|
| **JSqlParser 등 SQL 파서** | native SQL·MyBatis의 SQL AST 추출 | MySQL 방언 및 동적 SQL 처리 확인 필요 |
| **Flyway/Liquibase parser** | 최종 DDL 및 제약/인덱스 파악 | 마이그레이션 파일이 없으면 Hibernate 메타모델과 실제 스키마 조회로 대체 |
| **Testcontainers + MySQL** | 발견된 DB 위험의 동시 실행 재현 | 정적 분석이 아닌 동적 검증 |
| **jcstress** | Java 메모리 모델/병렬 경합 검증 | DB 잠금 검증에 직접 사용하지 않음 |
| **CodeQL** (선택) | 내장 JVM 동시성 쿼리, 사용자 정의 데이터 흐름 | Gradle 의존성이 아닌 별도 CLI. 비공개 저장소는 유료. Kotlin 버전 지원 확인 필요 |
| **Semgrep** (선택) | 빠른 후보 검색 | 파일 간 흐름 분석은 유료 기능 |

JavaParser와 Spoon은 Kotlin 소스를 읽지 못하므로 Kotlin 코드베이스에서는 쓰지 않는다.

### 2.3 규칙별 층 배치와 차단·후보 구분

| 규칙 | 층 | 차단형 규칙 (`DEFINITE_PATTERN`) | 후보형 규칙 |
|---|---|---|---|
| 001, 005 | JVM + Kotlin | 싱글턴 빈·`object`·최상위의 가변 필드, 비동시성 컬렉션 필드 | 동기화가 일관되지 않은 필드 접근 |
| 002 | JVM | 생성자 내 `this` 유출 | 비-`volatile` 플래그의 스레드 간 읽기 |
| 003 | JVM | 락 보유 중 다른 락 획득 | 호출 경로를 거친 중첩 획득 |
| 004 | JVM + Kotlin | `containsKey` 후 `put`, `get` 후 `set` 연속 | 여러 호출에 걸친 check-then-act |
| 006 | JVM + Kotlin | 비-`volatile` DCL, 공유 위치의 `lazy(LazyThreadSafetyMode.NONE)` | — |
| 007 | JVM + Kotlin | `finally` 없는 `unlock`, Kotlin에서 `withLock` 없는 `lock()` | 래퍼에 위임된 해제 |
| 008 | JVM + Kotlin | — | 상한 없는 `tryLock`·CAS·재시도 루프 |
| 009 + 018 | JVM | — | 락 없는 조회 → 엔티티 변경, `@Version`·CAS 없음 |
| 010 | JVM | — | 존재 확인 → 저장 경로, 조회 컬럼과 UNIQUE 불일치 |
| 011 | JVM | 조건부 `@Modifying` 갱신의 반환값 무시 | 불변식 대상 필드의 비보호 변경 |
| 012 | JVM | — | 한 트랜잭션에서 여러 행을 정렬 없이 잠금 |
| 013 | JVM | — | 한 트랜잭션 내 같은 조회 반복 (가치가 낮아 기본 비활성) |
| 014, 015 | JVM | — | `count`·`exists`·`sum` → 분기 → 쓰기 |
| 016 | JVM | — | 트랜잭션에서 외부 호출·대기 도달, `REQUIRES_NEW` 중첩 |
| 017 | JVM | 자기 호출, 프록시가 가로챌 수 없는 메서드의 `@Transactional`, 트랜잭션 밖 `@Lock` | 바깥 트랜잭션 유무에 따른 전파 의미 차이 |
| 019 | JVM | (정책으로 켠 경우) `@Version` 엔티티의 벌크 갱신에 버전 증가 누락, 컨텍스트 정리 누락 | QueryDSL 벌크 갱신 |
| 020 | JVM + Kotlin | — | JVM 락 구간에서 리포지토리 쓰기 도달, 다중 인스턴스 `@Scheduled` |
| 021 | JVM | — | Inbox를 거치지 않는 리스너, 멱등성 키 없는 생성 API |
| 022 | JVM | Outbox 어댑터 외의 메시지 전송 의존 | 트랜잭션 내 DB 쓰기 + 외부 전송 |

008, 012~015, 021은 어느 층에서도 후보 표시까지만 가능하다. 이 여섯 규칙의 확인은 에이전트와 동시성 테스트가 맡는다.

## 3. 22개 정적 분석 규칙 상세

각 규칙은 **트리거 → 보호 조건 → 검사 알고리즘 → 해결책 → 한계·검증**의 동일한 구조를 따른다.

### JAVA-CONC-001 — 공유 변수 Race Condition

- **위험 예:** `@Service` 싱글턴의 `int count;`에 여러 요청이 `count++` 수행.
- **트리거:** 여러 실행 경로로부터 접근 가능한 가변 필드에 읽기/쓰기 또는 쓰기/쓰기가 일어남.
- **보호 조건:** 동일한 올바른 Lock, 원자적 필드, 불변 구조, 실제 스레드 confinement 등.
- **검사 알고리즘:** (1) Bean/Static/공유 객체의 escape 분석 → (2) 필드 read/write 위치 수집 → (3) 병렬 실행 가능한 진입점 쌍 추론 → (4) lockset·Atomic 보호 비교 → (5) 교차 접근이 가능하면 경고.
- **해결책:** 요청별 상태로 이동, `AtomicInteger.incrementAndGet()`, 일관된 `synchronized`/`Lock`; 필요한 원자성 단위가 여러 필드라면 단일 임계영역.
- **한계·검증:** `@Service`의 모든 필드가 실제 공유 위험은 아님. jcstress 또는 동시 요청 테스트.

### JAVA-CONC-002 — 메모리 가시성 및 안전하지 않은 공개

- **위험 예:** 스레드 A가 `running = false`를 쓰지만 스레드 B가 비-`volatile` 값을 루프에서 읽음; 생성 완료 전 객체 참조 공개.
- **트리거:** 공유 필드를 스레드 간 읽고 쓰되 happens-before 관계를 입증할 수 없음.
- **보호 조건:** `volatile`, 동일 Monitor/Lock, 안전한 thread start/join, 동시성 유틸리티 등. `final`도 올바른 생성·공개 조건이 중요.
- **검사 알고리즘:** 쓰기→공개→다른 스레드 읽기의 경로 식별; `volatile`/synchronization/happens-before 근사 검토; 생성자 내 `this` escape 식별.
- **해결책:** `volatile`/`AtomicBoolean`, 불변 객체, 안전한 객체 공개.
- **한계·검증:** **`volatile`은 `count++`의 원자성을 보장하지 않는다.** jcstress/JMM 테스트.

### JAVA-CONC-003 — Java 교착 상태 (Deadlock)

- **위험 예:** 경로 A는 `lockA`→`lockB`, 경로 B는 `lockB`→`lockA`.
- **트리거:** 중첩 잠금 획득 순서가 일관되지 않음.
- **보호 조건:** 모든 관련 경로의 전역 잠금 순서, `tryLock` timeout/rollback을 통한 교착 회피.
- **검사 알고리즘:** 호출 그래프를 따라 `(heldLock, acquiredLock)` 간선을 만들고, 잠금 별칭을 통합한 후 그래프의 cycle/SCC 찾기; 동시에 실행 가능한 경로인지 확인.
- **해결책:** Lock 순서 통일, 중첩 잠금 축소, 조건부 획득과 안전한 실패 복구.
- **한계·검증:** 재진입 가능 Lock, 조건 분기, 동적 Lock ID에 따라 오탐 가능. 스레드 덤프/타임아웃 테스트.

### JAVA-CONC-004 — 원자성 위반 (Check-Then-Act / Read-Modify-Write)

- **위험 예:** `if (!map.containsKey(k)) map.put(k, create());`, `x = counter.get(); counter.set(x+1);`.
- **트리거:** 논리적으로 한 번에 이루어져야 할 검사/갱신 사이에 다른 실행이 개입할 수 있음.
- **보호 조건:** `computeIfAbsent`(함수 부작용·재실행 가능성 검토), `putIfAbsent`, `compareAndSet`, 단일 임계영역.
- **검사 알고리즘:** 동일 key/object에 대해 `read/check → branch → write` 데이터 흐름을 식별하고 모든 연산이 하나의 Lock/원자적 API 아래 있는지 확인.
- **해결책:** 원자적 컬렉션 API/CAS 사용; 여러 자원에 대한 불변식은 별도 동기화.
- **한계·검증:** `ConcurrentHashMap`을 써도 **여러 메서드 호출의 조합**이 자동으로 원자적이지 않다.

### JAVA-CONC-005 — 스레드 안전하지 않은 컬렉션의 공유

- **위험 예:** 여러 HTTP 요청이 Singleton의 `HashMap`을 동시에 수정.
- **트리거:** `HashMap`, `ArrayList`, `HashSet` 등 가변 컬렉션이 공유되고 수정·순회가 병렬 가능.
- **보호 조건:** thread confinement, 외부 동기화, 적절한 concurrent 자료구조.
- **검사 알고리즘:** 컬렉션 allocation/alias/escape 추적 → read/write/iteration 호출 분류 → 다중 진입점에서 Lock 일관성 검증.
- **해결책:** `ConcurrentHashMap`, `CopyOnWriteArrayList`(쓰기 빈도에 따라), 일관된 Lock 또는 불변 스냅샷.
- **한계·검증:** Concurrent 자료구조라도 순회와 갱신을 하나의 강한 스냅샷으로 취급하면 오류 가능.

### JAVA-CONC-006 — Double-Checked Locking 오류

- **위험 예:** `if (instance == null) synchronized(...) { if (instance == null) instance = new X(); }`에서 `instance`가 non-volatile.
- **트리거:** DCL 패턴 + 게시 필드의 가시성 및 초기화 순서 위반.
- **보호 조건:** 올바른 `volatile`, 초기화 완료 후 공개, 또는 initialization-on-demand holder.
- **검사 알고리즘:** 외부/내부 null 검사, Lock, 필드 할당 패턴 식별 → 필드 modifier/할당 후 초기화 여부 확인.
- **해결책:** Holder idiom, enum singleton, 올바른 volatile DCL.
- **한계·검증:** 생성·초기화의 미묘한 공개 의미론은 별도 검토. CodeQL 기존 `java/unsafe-double-checked-locking` 활용.

### JAVA-CONC-007 — Lock 해제 누락

- **위험 예:** `lock.lock(); process(); lock.unlock();`에서 `process()` 예외 시 미해제.
- **트리거:** 획득 후 모든 종료 경로에서 `unlock`이 보장되지 않음.
- **보호 조건:** 획득 직후 `try { ... } finally { lock.unlock(); }`.
- **검사 알고리즘:** CFG의 정상 종료·예외 경로마다 acquire/release 균형 검사, 같은 Lock 객체인지 alias 분석.
- **해결책:** `try/finally`, 필요한 경우 `tryLock()` 성공 분기에서만 해제.
- **한계·검증:** `unlock`이 다른 래퍼에 위임되면 요약 모델 필요. CodeQL `java/unreleased-lock` 참고.

### JAVA-CONC-008 — Starvation·Livelock

- **위험 예:** 공정성이 없는 Lock 경쟁, 무한 `tryLock` 루프, 진행 조건 없는 CAS 재시도.
- **트리거:** 유한 종료 보장 없는 경쟁 루프, 고정 우선순위, 동일 응답을 반복하는 재시도 패턴.
- **보호 조건:** 유한 시도 수/기한, 지수 백오프 + jitter, 필요한 곳의 공정성 설정 및 모니터링.
- **검사 알고리즘:** `while(true)`·CAS/`tryLock`/retry 루프를 찾아 progress/boundedness 검증; 반복 호출의 상태 변화·대기 조건을 추적.
- **해결책:** bounded retry, 타임아웃, 백오프, 적절한 큐·스케줄링.
- **한계·검증:** 실행 중 실제 기아 발생 여부는 스케줄러와 부하에 달림. **완전한 정적 판정 불가능**, 위험 후보로 보고 스트레스 테스트.

### DB-CONC-009 — Lost Update

- **위험 예:** 두 트랜잭션이 수량 10을 읽고 각각 3으로 저장하여 한 요청의 변경이 사라짐.
- **트리거:** DB read → Java 계산 → 일반 UPDATE; 경쟁하는 동일 행 갱신이 가능.
- **보호 조건:** `@Version` 기반 버전 검증, `SELECT ... FOR UPDATE`와 올바른 트랜잭션, 버전 CAS, 요구에 맞는 원자적 `UPDATE`.
- **검사 알고리즘:** Entity 읽기→상태 변경→flush/commit 경로 탐지 → @Version/lock-mode/SQL의 `WHERE version = ?`와 결과 행 수 검증 확인 → 미보호 시 경고.
- **해결책:** `@Version` + 충돌 처리, 잠금 조회, `UPDATE stock SET qty=qty-? WHERE id=? AND qty>=?`.
- **한계·검증:** 실제 UPDATE 형태 및 ORM 설정 중요. MySQL 동시 트랜잭션 테스트로 결과/예외 검증.
- **병합:** JPA-CONC-018과 트리거가 같다. 같은 코드 위치에서는 하나의 Finding으로 보고한다(5.3절).

### DB-CONC-010 — 중복 데이터 생성 (Check-Then-Insert)

- **위험 예:** `existsBySku(sku)` 검사 후 `save(product)`만 실행.
- **트리거:** 사전 중복 검증 → INSERT, 동일 자연키에 대한 UNIQUE 부재 또는 제약 컬럼 불일치.
- **보호 조건:** DB UNIQUE 인덱스/제약 조건이 업무상 고유 범위와 일치하고 중복 키 예외를 처리.
- **검사 알고리즘:** exists/count/find → create/insert 호출 연결 → Entity→DDL 매핑 → 유니크 조합 및 NULL 처리 정책 비교.
- **해결책:** 예: 거래처별 SKU는 `UNIQUE(customer_account_id, sku)`; 두 필드의 NOT NULL 정책 확인.
- **한계·검증:** `UNIQUE`는 정확히 정의된 키의 중복만 방지. MySQL에서 UNIQUE 컬럼의 `NULL`은 다수 허용될 수 있음. 동시 INSERT 통합 테스트.

### DB-CONC-011 — 초과 차감·초과 판매 (불변식 위반)

- **위험 예:** 재고 10에서 각 요청 7을 허용하여 총 14를 판매 처리.
- **트리거:** 수량 검증과 변경이 다른 SQL/트랜잭션으로 분리되고 충돌 보호 없음.
- **보호 조건:** `UPDATE stock SET qty=qty-:n WHERE id=:id AND qty>=:n` + **영향 행 수=1 검증**, 또는 적절한 Lock/버전 재검증.
- **검사 알고리즘:** 도메인 규칙 `qty >= 0` 로드 → 수량 check/source 추적 → 최종 변경 SQL 확인 → WHERE 불변식/Lock/CAS/affected-row handling 확인.
- **해결책:** 원자적 조건부 UPDATE; 재고 부족 시 명시적 실패, 기타 재고 예약/주문 상태와의 트랜잭션 일관성.
- **한계·검증:** `@Version`의 충돌 감지만으로 업무 재시도 결과가 정의되지는 않는다. 병렬 차감 후 불변식·성공 수 확인.

### DB-CONC-012 — MySQL Deadlock

- **위험 예:** 트랜잭션 A는 `stock(1)`→`stock(2)` 수정, B는 반대 순서.
- **트리거:** 두 트랜잭션의 잠금 자원(행/인덱스 범위) 획득 순서가 충돌 가능.
- **보호 조건:** 접근 순서 표준화, 트랜잭션 축소, 적절한 인덱스, 데드락 예외의 **전체 트랜잭션 재시도**.
- **검사 알고리즘:** SQL 실행 순서 + 엔티티/테이블/인덱스 매핑으로 잠금 순서 그래프 구성 → 상반된 순서의 잠재적 교차 탐지.
- **해결책:** ID 정렬 후 Lock, 짧은 트랜잭션, DB의 deadlock victim을 처리하는 안전한 retry.
- **한계·검증:** InnoDB는 인덱스 레코드·갭·FK 등도 잠글 수 있어 정적 그래프만으로 실제 교착 판단 불가. `SHOW ENGINE INNODB STATUS` 및 DB 부하 테스트.

### DB-CONC-013 — Non-repeatable Read

- **위험 예:** `READ COMMITTED`에서 같은 트랜잭션이 동일 행을 두 번 일반 SELECT하여 서로 다른 값을 봄.
- **트리거:** 트랜잭션의 동일 엔티티/조건 반복 조회와 중간에 다른 트랜잭션의 수정 가능성이 있고, 업무가 값 안정성에 의존.
- **보호 조건:** 적절한 격리 수준/잠금, 스냅샷 읽기에 적합한 설계, 수정 시 CAS 등.
- **검사 알고리즘:** 실효 isolation(메서드→트랜잭션 매니저→세션) 추론 → 반복 SQL와 그 결과에 의존한 결정 확인.
- **해결책:** 필요하다면 `REPEATABLE READ`의 일관성 읽기 또는 `FOR UPDATE`; 검증 시점 재확인/조건부 갱신.
- **한계·검증:** InnoDB 기본 `REPEATABLE READ` 내 **일반 consistent SELECT**는 같은 read view를 사용하므로 무조건 경고하면 오탐. JPA 1차 캐시로 재조회가 SQL로 실행되지 않을 수도 있음.

### DB-CONC-014 — Phantom Read 및 범위 조건 경쟁

- **위험 예:** `READ COMMITTED`에서 `WHERE status='WAITING'` 범위를 두 번 읽는 중 다른 트랜잭션이 INSERT.
- **트리거:** 범위 조회/COUNT/exists 결과를 기반으로 결정하거나 같은 범위를 반복 조회하며 삽입·삭제의 영향을 가정.
- **보호 조건:** 필요한 범위를 보호하는 락·제약, 충분한 격리 수준, 명시적 직렬화 지점.
- **검사 알고리즘:** 범위 predicate 추적 → isolation 분석 → 해당 범위의 insert/delete 가능한 경로 → 인덱스·잠금 읽기 조건을 교차 검토.
- **해결책:** 인덱스와 실제 잠금 semantics 확인, 적절한 범위 잠금 또는 업무를 단일 행/UNIQUE/직렬화 가능한 제약으로 재설계.
- **한계·검증:** InnoDB `REPEATABLE READ`의 일반 스냅샷 SELECT는 같은 read view를 사용한다. `READ COMMITTED`와 잠금 읽기/변경 SQL의 행위는 다르므로 단순 격리 수준 매트릭스만으로 판정 금지.

### DB-CONC-015 — Write Skew

- **위험 예:** "담당 의사 최소 1명 근무" 상태에서 A와 B가 서로 다른 의사를 각각 비활성화, 두 트랜잭션은 동일한 초기 집계를 읽음.
- **트리거:** 다수 행·집계 결과를 보고 서로 다른 행을 갱신해 전역 불변식을 깨뜨릴 가능성.
- **보호 조건:** 일관된 공통 잠금 행, 해당 불변식을 직렬화하는 설계, 필요한 경우 SERIALIZABLE 사용/충돌 재시도.
- **검사 알고리즘:** 불변식 메타데이터 + `COUNT/SUM/EXISTS` → 분기 → 다른 행 UPDATE 경로를 모델링; 동일 판단 집합의 동시 실행 가능한 트랜잭션 식별.
- **해결책:** 업무 단위의 guard row를 잠그거나 집계 상태를 원자적으로 보호, 또는 적절한 직렬화 전략.
- **한계·검증:** **업무 불변식이 없으면 정확한 자동 발견이 사실상 불가능.** 범위잠금 및 SERIALIZABLE 실제 행위는 MySQL에서 실험으로 검증.

### DB-CONC-016 — Lock Wait Timeout 위험

- **위험 예:** 트랜잭션이 row lock을 잡은 채 원격 API 요청·대용량 루프·사용자 대기를 수행.
- **트리거:** 잠금 획득 이후 오래 걸릴 수 있는 동작, 대량 수정, 비효율적 범위 UPDATE, 누락된 적절한 인덱스.
- **보호 조건:** 잠금 유지 구간 축소, 인덱스, 배치 크기 조정, timeout/rollback 정책.
- **검사 알고리즘:** transaction span/lock acquisition 이후 HTTP·sleep·파일 I/O·반복문·N+1 호출 탐지; SQL WHERE와 인덱스 비교.
- **해결책:** 외부 호출을 트랜잭션 밖으로 재구성, 짧은 트랜잭션, 인덱스 설계, 실패 후 트랜잭션 상태를 올바르게 정리하고 안전하게 재시도.
- **한계·검증:** 정적 분석은 **실제 대기 시간이나 timeout 발생을 예측할 수 없다**. InnoDB는 기본적으로 lock wait timeout에서 **명령문만 롤백**할 수 있어, deadlock과 동일한 처리를 단정하지 말 것.
- **확장:** `REQUIRES_NEW`가 바깥 트랜잭션의 커넥션을 쥔 채 두 번째 커넥션을 요구해 커넥션 풀이 고갈되는 경로를 함께 검사한다.

### SPRING-CONC-017 — `@Transactional` 적용 누락

- **위험 예:** `this.innerTransactional()` 또는 동일 객체 내부 호출로 프록시를 우회함.
- **트리거:** Spring AOP **proxy mode**에서 `@Transactional` 대상 메서드에 대한 self-invocation; 접근제어/프록시 타입/설정 불일치.
- **보호 조건:** Bean 간 프록시 경유 호출, `TransactionTemplate`, 올바른 설정; AspectJ mode는 별도 의미론.
- **검사 알고리즘:** Bean 인식 → 메서드 주석·proxy 설정·실제 호출자 분석 → self-invocation 및 적용 불가 경로 탐지 → 바깥 트랜잭션 존재 여부까지 검토.
- **해결책:** 책임 분리된 별도 Bean으로 이동, `TransactionTemplate` 사용.
- **한계·검증:** **self-invocation이 있어도 외부 메서드에서 이미 필요한 트랜잭션이 열린 경우는 장애가 아닐 수 있다.** 특히 `REQUIRES_NEW` 등 지정된 별도 의미론이 무시되는지가 중요.
- **프록시 방식별 추가 트리거:** JDK 동적 프록시(`proxy-target-class: false`)에서는 인터페이스에 선언되지 않은 메서드의 `@Transactional`이 적용되지 않는다. CGLIB에서는 `final` 클래스·메서드(Kotlin의 기본값)가 가로채지지 않는다. 트랜잭션 밖에서 호출되는 `@Lock` 조회도 이 규칙에서 검사한다.

### JPA-CONC-018 — 낙관적 락 보호 누락

- **위험 예:** 같은 `Order`를 여러 요청이 수정하는데 `@Version`도 조건부 UPDATE도 없음.
- **트리거:** 동시 쓰기가 가능한 엔티티에 read-modify-write 갱신 경로가 있고 보호 규약 누락.
- **보호 조건:** `@Version`, 비관적 Lock, SQL CAS, 동시 쓰기가 허용되는 교환법칙적 원자적 갱신 등.
- **검사 알고리즘:** Entity별 변경 경로 분류 → 동시 쓰기 가능성·수정 의미 추론 → 보호 조건 검사 → 없는 경우 위험 보고.
- **해결책:** 엔티티 버전 관리, 충돌 예외 처리 및 재시도 정책. 모든 Entity에 무조건 `@Version`을 붙이라는 규칙은 금지.
- **한계·검증:** 단순 `@Version` 존재가 전체 경로 보호를 증명하지 않음(특히 bulk/native SQL).
- **확장:** 낙관적 락 충돌의 재시도가 같은 트랜잭션·같은 영속성 컨텍스트 안에서 반복되는 경우를 검사한다. 재시도는 새 트랜잭션에서 다시 조회해야 의미가 있다.
- **병합:** DB-CONC-009와 트리거가 같다(5.3절).

### JPA-CONC-019 — Bulk Update와 버전 보호 불일치

- **위험 예:** `@Version`이 있는 Entity를 `@Modifying @Query("update ...")`로 수정하며 버전 조건·증가 없이 처리.
- **트리거:** JPQL bulk UPDATE/DELETE 또는 native SQL이 managed entity의 버전 보호/영속성 컨텍스트와 충돌할 가능성.
- **보호 조건:** 업무상 요구되는 `WHERE version=:expected`, `SET version=version+1`, affected rows 검사; 필요한 경우 flush/clear/refresh.
- **검사 알고리즘:** @Modifying/JPA Query/native SQL 식별 → 엔티티→테이블 매핑 → @Version 컬럼 반영·영향 행 수·컨텍스트 동기화 검사.
- **해결책:** 버전 조건과 증가를 명시하고 `updated == 1` 검사, `clearAutomatically`/명시적 clear 전략.
- **한계·검증:** Bulk UPDATE는 JPA의 자동 낙관적 락 검증을 우회할 수 있고 persistence context를 자동 동기화하지 않는다. 모든 bulk 변경에 버전 검사가 필수인 것은 아니므로 정책 기반 판단.

### DIST-CONC-020 — JVM Lock만으로 DB 보호

- **위험 예:** `synchronized`로 재고 감소를 감쌌지만 여러 서버 인스턴스가 같은 MySQL 행을 갱신.
- **트리거:** DB의 전역 불변식을 JVM 메모리 Lock만으로 보호; 다중 인스턴스/외부 쓰기 가능.
- **보호 조건:** MySQL 원자적 DML, 낙관적/비관적 DB Lock, 필요한 경우 신중한 분산 동기화(장애·lease·fencing 고려).
- **검사 알고리즘:** synchronized/Lock 하위의 Repository 수정 경로 추적 → 처리 대상이 DB 전역 자원인지 판정 → 배포 인스턴스 및 다른 writer 확인.
- **해결책:** DB를 정합성의 최종 보호 지점으로 사용. Lock 키가 JVM별로 분리되는지 점검.
- **한계·검증:** 실제 배포가 단일 JVM이라도 외부 배치·수동 SQL 쓰기를 확인해야 함.
- **확장:** (a) `@Transactional` 메서드 안에서 JVM 락을 잡고 풀어 커밋 전에 락이 해제되는 순서, (b) 다중 인스턴스에서 `@Scheduled` 작업(폴링·정리 작업 포함)이 중복 실행되는 경우를 함께 검사한다.

### DIST-CONC-021 — 중복 요청 및 멱등성 위반

- **위험 예:** 결제/발주 API 재시도마다 새 레코드나 이벤트를 생성.
- **트리거:** 네트워크 재시도·메시지 재전달이 가능한 진입점에서 동일 요청의 중복 실행을 방지하지 않음.
- **보호 조건:** 요청 범위·사용자/테넌트 범위를 고려한 idempotency key + DB UNIQUE + 원자적인 결과 기록 + 동일 키의 payload 불일치 검사 + 동시 진행 상태 처리.
- **검사 알고리즘:** retryable endpoint/listener 식별 → key 전파 → DB UNIQUE 범위·동일 트랜잭션 저장·기존 결과 재사용·예외 처리 경로 추적.
- **해결책:** `idempotency_request(tenant_id, request_id)` UNIQUE, `PENDING/COMPLETED` 및 요청 해시, 비즈니스 변경과 원자적 기록; consumer 측 inbox/유니크 처리.
- **한계·검증:** 멱등성 보장의 범위는 API 계약·외부 시스템과 결합됨. DB 저장 성공 후 재시도, 동시 같은 키, payload 충돌을 테스트.

### DIST-CONC-022 — DB 변경과 외부 효과의 Dual Write 불일치

- **위험 예:** `@Transactional` 내부에서 DB를 변경하고 외부 API 호출 또는 Kafka 발행; DB 롤백과 외부 성공이 분리됨.
- **트리거:** 하나의 업무 동작에서 서로 다른 원자성 경계(DB + 외부 서비스/브로커)를 직접 연속 처리.
- **보호 조건:** DB 변경 + Outbox 레코드를 **같은 DB 트랜잭션**에 저장하고 별도 발행기에서 재시도; 소비자 idempotency/inbox, 또는 사가·보상 설계.
- **검사 알고리즘:** transaction boundary 내 DB write + HTTP/메시지 send 추적; outbox 저장 경로·동일 datasource/tx·발행 retry/consumer 중복 처리 여부 검사.
- **해결책:** Transactional Outbox, publisher의 at-least-once 전송을 전제로 consumer 중복 제거, 외부 API의 멱등성·보상 정책.
- **한계·검증:** Outbox만으로 최종 소비자 처리의 exactly-once를 보장하지 않는다. 커밋 직후 장애·중복 발행·소비자 재처리 실험 필요.

## 4. 실제 정적 분석 패턴 예제

### 4.1 Java 공유 필드

```java
@Service
class CounterService {
    private int count = 0;
    void increment() { count++; } // JAVA-CONC-001
}

@Service
class SafeCounterService {
    private final AtomicInteger count = new AtomicInteger();
    void increment() { count.incrementAndGet(); }
}
```

**추가 분석 조건:** 요청별 객체이거나 호출이 직렬화된 것으로 증명되면 경고 강도를 낮춘다. `AtomicInteger`도 여러 값을 묶은 업무 불변식의 자동 해결책은 아니다.

### 4.2 MySQL 잔여 수량 보호

```java
@Transactional
public void decrease(Long id, int amount) {
    Stock stock = stockRepository.findById(id).orElseThrow();
    if (stock.getQuantity() < amount) throw new InsufficientStockException();
    stock.decrease(amount);                  // DB-CONC-009 / 011 후보
}
```

```sql
UPDATE stock
   SET quantity = quantity - :amount
 WHERE id = :id
   AND quantity >= :amount;
```

```java
int affected = stockRepository.decreaseIfAvailable(id, amount);
if (affected != 1) throw new InsufficientStockException();
```

**검출 시 고려:** SQL에 조건이 있어도 **영향받은 행 수를 무시하면** API가 성공했다고 오인할 수 있다.

### 4.3 동일 거래처 내 SKU 중복 방지

```sql
CREATE TABLE product (
    id BIGINT PRIMARY KEY,
    customer_account_id BINARY(16) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_product_account_sku(customer_account_id, sku)
) ENGINE = InnoDB;
```

`existsByCustomerAccountIdAndSku(...)`는 UX용 사전 검증일 뿐 정합성의 최종 장벽이 아니다. INSERT 시 UNIQUE 위반은 별도로 처리해야 한다.

### 4.4 Spring 프록시 경계

```java
@Service
class InventoryService {
    void outer() { this.inner(); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() {
        // 별도 트랜잭션이 시작될 것이라고 가정하면 위험
    }
}
```

**정적 분석:** self-invocation 감지 + 실제 외부 트랜잭션과 propagation 비교. `@Transactional` 주석이 존재한다는 사실만으로 실행 시 적용을 단정하지 않는다.

### 4.5 Bulk Update와 `@Version`

```java
@Entity
class Stock {
    @Id Long id;
    @Version Long version;
    long quantity;
}
```

```java
@Modifying
@Query("update Stock s set s.quantity = s.quantity - :n where s.id = :id")
int decrease(Long id, long n); // 정책에 따라 JPA-CONC-019
```

```java
@Modifying
@Query("""
    update Stock s
       set s.quantity = s.quantity - :n,
           s.version = s.version + 1
     where s.id = :id and s.version = :expected and s.quantity >= :n
    """)
int decreaseWithVersion(Long id, long expected, long n);
```

이후 반드시 영향 행 수와 예외/재시도 의미를 확인한다.

## 5. 룰 엔진 구현 인터페이스와 결과 스키마 (예시)

```java
public interface ConcurrencyRule {
    String id();
    List<Finding> analyze(AnalysisContext context);
}

public record Finding(
    String ruleId,
    String fingerprint, // 재실행 간 같은 검출을 식별. ruleId + 선언 위치 + 증거 경로로 만들고 줄 번호는 넣지 않는다
    Severity severity,
    Outcome outcome, // DEFINITE_PATTERN, POTENTIAL_RISK, REQUIRES_VALIDATION, UNKNOWN, SAFE_BY_PROOF
    String file,
    int line,
    List<Evidence> evidence,
    List<String> mergedRuleIds, // 같은 위치에서 함께 발화해 병합된 규칙 (5.3절)
    List<String> assumptions,
    List<String> missingEvidence,
    String remediation,
    String recommendedTest,
    Suppression suppression // 억제된 경우 사유와 위치, 아니면 null (5.4절)
) {}

// 리뷰 에이전트의 판정. 분석기 출력과 분리해 fingerprint로 연결한다.
public record Verdict(
    String fingerprint,
    Decision decision, // CONFIRMED, REFUTED, INCONCLUSIVE
    String executedTest, // 판정 근거가 된 동시성 테스트
    String rationale
) {}
```

분석기 출력은 에이전트가 읽을 수 있는 기계 판독 형식(SARIF 또는 JSON)으로 내보낸다. `missingEvidence`는 리뷰 에이전트가 확인해야 할 질문 목록으로 쓰인다.

실제 구현에서는 `AnalysisContext`에 코드/SQL/DDL/설정별 인덱스, 캐시, 심볼 테이블을 포함하되 규칙들이 같은 추론 결과를 재사용하게 한다. 문제를 발견하지 못했다는 사실과 보호가 입증된 상태는 구분한다.

### 5.1 규칙 판정 순서

1. **후보 검출** (`candidate`): 위험 패턴이 있는가?
2. **도달성·동시 실행 가능성 추론** (`reachable/parallelizable`): 같은 데이터를 동시에 처리할 수 있는가?
3. **보호 수단 탐색** (`safeguards`): Atomic/Lock/@Version/UNIQUE/조건부 UPDATE 등이 있는가?
4. **보호의 범위 검증** (`scope`): 보호가 동일 객체·키·트랜잭션·프로세스·DB 전역에 유효한가?
5. **결과 분류**: `SAFE_BY_PROOF` / `DEFINITE_PATTERN` / `POTENTIAL_RISK` / `REQUIRES_VALIDATION` / `UNKNOWN` (1절의 표와 동일).
6. **증거 경로 작성**: 서비스 메서드 → Repository 메서드 → SQL → 인덱스·트랜잭션 경계.

**주의:** `SAFE_BY_PROOF`는 해당 룰과 분석된 범위에 대해서만 의미가 있다. 시스템 전체 안전성을 뜻하지 않는다.

### 5.2 정책 정보 예시

```yaml
analysis:
  runtime:
    multipleApplicationInstances: true
    database: mysql
    engine: InnoDB
    effectiveIsolation: REPEATABLE_READ
  invariants:
    - entity: Stock
      property: quantity
      constraint: NON_NEGATIVE
    - table: product
      uniqueBusinessKey: [customer_account_id, sku]
  concurrencyEntrypoints:
    - '@RestController'
    - '@KafkaListener'
    - '@Scheduled'
    - '@Async'
  externalSideEffects:
    - 'RestClient.*'
    - 'KafkaTemplate.send'
```

이 파일은 **예시**이며 런타임 배포·연결 풀·DB 설정과 불일치하면 분석이 잘못된다. 설정을 검증하거나 `UNKNOWN`을 표시할 것. `effectiveIsolation`처럼 실행 환경에서 읽을 수 있는 값은 기동 테스트에서 실제 값과 대조한다.

### 5.3 중복 병합

같은 코드 위치에서 여러 규칙이 발화하면 하나의 Finding으로 합친다. 대표 규칙을 `ruleId`에, 나머지를 `mergedRuleIds`에 기록한다. 병합하지 않으면 같은 후보가 에이전트에게 여러 번 전달된다.

| 묶음 | 대표 규칙 선택 |
|---|---|
| 009 · 011 · 018 | 불변식이 선언된 필드면 011, 아니면 009 |
| 001 · 004 · 005 | 004 > 005 > 001 순 |
| 016 · 022 | 외부 전송이 있으면 022, 대기·지연만 있으면 016 |

### 5.4 억제

- 억제는 사유를 필수로 하는 애너테이션 또는 주석으로만 한다. 사유 없는 억제는 그 자체를 `DEFINITE_PATTERN`으로 보고한다.
- 억제된 검출은 사라지지 않는다. `suppression`이 채워진 Finding으로 남아 리뷰 에이전트에게 전달된다.
- 규칙 설정, 정책 파일(5.2절), 베이스라인은 코드를 작성하는 에이전트가 수정할 수 없다.

## 6. 에이전트 연동 운영 흐름

```text
1. 에이전트가 코드 작성
2. 정적 분석 실행
3. DEFINITE_PATTERN 수정
4. 2~3 반복 ── DEFINITE_PATTERN 0건이 될 때까지
5. 리뷰 에이전트가 후보와 억제 목록을 검토
6. 리뷰에 따라 수정하면 2번으로 복귀, 수정이 없으면 완료

모든 단계에서 회귀 테스트가 통과해야 한다.
```

- **차단 조건:** 2~4번 반복을 막는 것은 `DEFINITE_PATTERN`뿐이다. `POTENTIAL_RISK`, `REQUIRES_VALIDATION`, `UNKNOWN`은 반복을 막지 않고 5번의 입력이 된다.
- **수정과 테스트:** 3번에서 검출을 고칠 때 `recommendedTest`를 바탕으로 한 동시성 테스트를 함께 추가한다. 기존 회귀 테스트는 동시성 경로를 다루지 않으므로, 회귀 테스트 통과만으로는 수정이 옳다고 볼 수 없다.
- **반복 상한:** 같은 `fingerprint`의 검출이 N회(기본 3회) 연속 남으면 반복을 멈추고 사람에게 넘긴다.
- **리뷰 에이전트의 분리:** 리뷰는 코드를 작성한 에이전트와 다른 컨텍스트에서 수행한다.
- **리뷰 판정 방식:** 후보의 판정은 코드를 읽는 것만으로 끝내지 않는다. 동시성 테스트를 실행해 재현 여부를 `Verdict`로 남긴다.
- **재진입:** 6번에서 코드를 고쳤다면 완료가 아니라 2번으로 돌아간다. 리뷰 수정이 새 검출을 만들 수 있다.
- **후보가 없는 영역:** 분석기가 후보를 내지 않은 코드는 리뷰 대상에 들어가지 않는다. 쓰기 트랜잭션 진입점 전체를 에이전트가 검출과 무관하게 주기적으로 훑는 절차를 별도로 둔다.
- **분석 속도:** 분석기는 반복 안에서 여러 번 실행된다. 변경된 모듈만 분석하는 증분 실행을 초기 요건으로 둔다.

## 7. 검증 전략: 22개 규칙 모두 테스트

**모든 룰에 대해 최소 다음 테스트 묶음을 작성한다.**

| 테스트 | 목적 | 필수 조건 |
|---|---|---|
| Positive fixture | 위험 코드를 찾아내는가? | 규칙별 2개 이상 (직접/간접 호출) |
| Negative fixture | 안전한 구현에 오탐이 없는가? | Lock/원자적 SQL/버전/UNIQUE 등 각각 |
| Boundary fixture | 안전성의 경계 조건을 구분하는가? | 격리 수준·프록시·여러 JVM·다른 인덱스 |
| Unknown fixture | 정보 부족 시 단정하지 않는가? | 동적 SQL·누락된 DDL·외부 서비스 등 |
| Concurrent test | 실제 경쟁 상황에서 문제가 재현되거나 해결되는가? | Java는 jcstress/멀티스레드, DB는 실제 MySQL |

- **JAVA-CONC-001~008:** JMM 및 Lock 동작을 검증하는 jcstress/동시 실행 테스트.
- **DB-CONC-009~016:** Testcontainers MySQL(InnoDB)에서 두 개 이상의 서로 다른 커넥션으로 `CountDownLatch`/barrier를 사용한 동시 트랜잭션 테스트; 격리 수준별로 실행.
- **SPRING/JPA-CONC-017~019:** 실제 Spring 프록시 및 Hibernate flush/bulk-update 통합 테스트.
- **DIST-CONC-020~022:** 다중 애플리케이션 인스턴스·이벤트 중복 발행·소비자 재처리·장애 주입 테스트.

회귀 묶음에 넣는 동시성 테스트의 조건:

- DB 규칙(009~016, 019)의 테스트는 MySQL 컨테이너에서 실행한다. H2 등 대체 DB로는 InnoDB의 잠금·격리 동작이 재현되지 않는다.
- 래치·배리어로 실행 순서를 고정한 테스트만 회귀 묶음에 넣는다. 타이밍에 의존하는 테스트는 간헐적으로 실패해 6절의 반복을 오염시키므로 별도 묶음으로 분리한다.
- Fixture는 Java와 Kotlin 양쪽으로 둔다. Kotlin 고유 형태(`object`, `lazy`, `withLock`)는 별도 fixture로 확인한다.

### 7.1 MySQL에서 특히 주의할 사항

- InnoDB **기본 격리 수준은 `REPEATABLE READ`**. 일반 non-locking `SELECT`의 consistent read와 `SELECT ... FOR UPDATE`/UPDATE의 잠금 읽기는 다르다.
- 인덱스 및 검색 조건에 따라 record/gap/next-key lock의 범위가 달라진다. 스키마만으로 실제 사용된 인덱스를 항상 확정할 수 없으므로, 필요한 경우 실제 환경에서 `EXPLAIN`과 잠금 관측을 활용한다.
- `Deadlock`은 InnoDB가 피해 트랜잭션을 롤백할 수 있다. 일반적인 lock wait timeout은 **명령문 롤백**과 연관되며 설정에 따라 달라진다. 동일 오류 처리로 단순화하지 않는다.
- `UNIQUE`로 막을 수 있는 키 중복과 다중 행의 총합·최소 인원 같은 업무 불변식은 별개다.
- 자동 재시도 전에 외부 API 호출/이벤트 발행 등 **재실행하면 안 되는 부수효과**가 있는지 검사한다.

## 8. 22개를 모두 구현하기 위한 수행 계획

| 단계 | 포함 룰 | 구현 목표 | 완료 판정 |
|---|---|---|---|
| A. 분석 기반 | 전체 공통 | 호출 그래프 순회(인터페이스 구현체 포함), 쿼리·DDL·Spring 모델, 결과 스키마와 지문, 증분 실행 | 서비스↔SQL↔테이블 증거 경로 생성 |
| B. JVM 동시성 (Java·Kotlin) | 001~008 | 공유 상태·Lock·가시성·원자성·진행 위험 | 8개 룰 모두 positive/negative/unknown 테스트 |
| C. MySQL | 009~016 | 읽기-수정-쓰기, UNIQUE, 제약, 격리, 잠금 순서 | 8개 룰 모두 MySQL 통합 테스트 및 가정 기록 |
| D. Spring/JPA | 017~019 | 트랜잭션 프록시·버전·Bulk DML | 3개 룰 모두 프록시/flush 변형 테스트 |
| E. 분산 경계 | 020~022 | 멀티 인스턴스·멱등성·dual-write | 3개 룰 모두 중복/장애 주입 테스트 |
| F. 통합 | 22개 전부 | 차단·후보 분류 조정, 에이전트 흐름(6절) 연동, SARIF/CI 통합 | 모든 룰 활성화; 증거와 불확실성 출력; 6절 흐름이 끝까지 실행됨 |

**권장 검출 지표:** 사례별 precision/recall(라벨링된 fixture와 실제 코드 샘플 기준), `UNKNOWN` 비율, 분석 비용, triage 시간, 중요한 문제의 누락 여부. 차단형 규칙은 precision을, 후보형 규칙은 recall을 우선한다. **무조건 0건 경고가 나오도록 억제하는 방식은 피한다.**

## 9. 기술적 최종 판단

1. **22개 문제 유형 모두에 대해 정적 분석 규칙을 작성하고 위험을 찾아내는 시스템을 개발하는 것은 기술적으로 가능하다.**
2. **22개 유형에서 발생할 수 있는 모든 실제 오류를 누락 없이 자동 발견·확정하는 것은 불가능하다.** 특히 `JAVA-CONC-008`, `DB-CONC-012~016`, `DIST-CONC-021~022`는 정적 분석만으로 런타임·외부·업무 조건을 완전히 알 수 없다.
3. 따라서 완성도 높은 도구의 성공 기준은 **'22개 룰 제공 + 증거 경로 + 보호 조건 검사 + UNKNOWN 표현 + 차단·후보 분리 + 에이전트 검증 및 동적 검증 연결'**이다.
4. 구현 방향은 **JVM 층 규칙(ArchUnit·SpotBugs·메타모델) 중심 + Kotlin 소스 규칙(detekt) + Spring/JPA 의미론 모델 + 쿼리/DDL/업무 규칙 분석 계층**이 합리적이다. 의존성 추가만으로 검출되는 것은 006, 007과 001~005, 017의 일부뿐이며 나머지는 직접 작성한다. CodeQL 등 범용 엔진은 선택 사항이다.
5. 데이터 정합성의 최종 보호에는 **DB 제약·원자적 SQL·트랜잭션 설계**를 활용한다. 정적 검사 자체가 런타임 보호를 대신하지는 못한다.

## 10. 참고 자료

1. [IN-COM: 정적 코드 분석은 멀티스레드 또는 동시 코드를 어떻게 처리합니까?](https://www.in-com.com/ko/blog/how-does-static-code-analysis-handle-multi-threaded-or-concurrent-code/)
2. [CodeQL Java query help (동시성 관련 내장 검사 포함)](https://codeql.github.com/codeql-query-help/java/)
3. [CodeQL: Unreleased lock](https://codeql.github.com/codeql-query-help/java/java-unreleased-lock/)
4. [CodeQL: Unsafe double-checked locking](https://codeql.github.com/codeql-query-help/java/java-unsafe-double-checked-locking/)
5. [MySQL 8.4: Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)
6. [MySQL 8.4: Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
7. [MySQL 8.4: How to Minimize and Handle Deadlocks](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html)
8. [MySQL 8.4: InnoDB Error Handling](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html)
9. [Spring Framework: Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
10. [Spring Framework: Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
11. [Jakarta Persistence: Bulk Update semantics](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.pdf)
12. [OpenJDK jcstress](https://github.com/openjdk/jcstress)
13. [detekt: Compatibility Table](https://detekt.dev/docs/introduction/compatibility/)
14. [SpotBugs CHANGELOG](https://github.com/spotbugs/spotbugs/blob/master/CHANGELOG.md)
15. [ArchUnit Releases](https://github.com/TNG/ArchUnit/releases)
16. [Kotlin 2.3.0 Released (Java 25 지원)](https://blog.jetbrains.com/kotlin/2025/12/kotlin-2-3-0-released/)
17. [Spring Boot 4.1: System Requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html)

---

*작성 기준일: 2026-10-09. 본 문서는 구현 명세 및 연구 계획이며 정적 분석 엔진이 이미 만들어졌다는 의미는 아니다. 도구 버전과 호환 정보는 공개 문서로 확인한 것이며 실제 빌드에 붙여 실행해 확인한 것은 아니다.*
