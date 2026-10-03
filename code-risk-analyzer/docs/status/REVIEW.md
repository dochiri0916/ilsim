# Code Risk Analyzer 검토

검토일: 2026-09-30

## 검토 범위

README, 구현 코드, 관련 테스트 소스를 기준으로 현재 분석 기능과 한계, 추가 기능 및 리팩터링 후보를 정리했다. 최초 검토에서는 테스트와 PIT를 실행하지 않았다. 이후 1~5순위 항목은 구현과 회귀 테스트에 반영했다. 리팩터링 후보는 코드 검토 결과이며 재현 테스트로 확정한 결함 목록은 아니다.

현재 프로젝트는 Java/Spring 코드의 위험 패턴 탐지와 테스트 부족 후보 도출이 가능하다. 분석 결과가 없다는 이유로 동시성·트랜잭션·보안의 안전성이 보장되지는 않는다.

## 1. 현재 검증할 수 있는 것

| 영역 | 지원 범위 |
|---|---|
| 보안 — 5개 규칙 | SQL Injection, Command Injection, Path Traversal, SSRF, 하드코딩된 비밀값 탐지 |
| 동시성 — 4개 규칙 | check-then-act, lost update, singleton 공유 상태 변경, 비동기에서 호출 스레드의 컨텍스트 사용 |
| 멱등성 — 4개 규칙 | 메시지 소비의 부수 효과, 재시도되는 외부 변경의 멱등키 누락, 재시도와 부수 효과, 이벤트 비즈니스 키 누락 |
| 트랜잭션 — 5개 규칙 | self-invocation, 비동기 경계, 트랜잭션 내부 외부 호출, 트랜잭션 없는 다중 쓰기, 예외 삼키기 |
| 부분 실패 — 1개 규칙 | DB 변경과 외부 부수 효과 간 불일치 위험 |
| 테스트 적정성 | 분기·경계값·예외·상태 변경·저장·권한·롤백 등에 필요한 테스트 항목 추출 및 테스트 연결, 선택형 실행 증거 import |
| 변이 검증 | 별도 `mutation` 명령으로 Gradle/PIT 실행, 변이를 테스트가 잡는지 확인 |
| CI 연동 | JSON/SARIF, baseline, suppression, 심각도·신뢰도에 따른 실패 정책 |
| 요구사항 실행 경로 | JSON Schema·중복 키 검증, SPEC_ONLY/SPEC_CODE_REVIEW/CODE_CHANGE 실행 제한, 명시적 메서드의 TX/CON/IDEM 연결, 분리된 문서/코드 JEV 후보 검토 |
| 회귀·최종 검증 | CODE_CHANGE 전용 별도 runner의 실행 기준선·기존 테스트 보호·결과 비교·서비스 증거·미해결 근거 완료 판정. fixture는 별도 JVM HTTP/MySQL 검증 지원 |

위험 규칙은 총 21개(2026-10-03 SEC-006·SEC-007 추가)이며, 테스트 적정성은 별도 `TEST-001`이다. 규칙 정의는 [RuleRegistry.java](../../src/main/java/com/example/coderiskanalyzer/production/rule/RuleRegistry.java)에 있다.

기준선 기록과 최종 완료 판정의 실행 방법·지원 제한은 [회귀 검증 실행 절차](../requirements-analysis/regression-workflow.md)를 따른다. 테스트 assertion 의미와 전이적인 변경 영향은 자동 증명하지 않으며, SPEC_ONLY에는 코드 검증을 적용하지 않는다.

[미해결 근거 검토](../requirements-analysis/evidence-review.md)는 테스트 부족·정적 분석 한계·의도된 fixture 결함을 분류하고 요구사항·assertion·실행·해시를 연결한다. 검증 실행 성공과 대상 위험 해소를 별도 상태로 보고하며, 일반 프로젝트의 미해결 production 위험은 계속 차단한다.

테스트 적정성의 `COVERED`도 제한된 의미다. 정적 분석에서 예외 타입 검증이나 저장 호출 검증을 인식하거나, 지원 DB 시나리오에 실행 증거와 명시적 assertion 검토를 연결했다는 뜻이다. 전체 비즈니스 동작이 올바르다는 보장은 아니다.

요구사항 경로의 실행 방법과 승인된 변경·검증 범위는 [requirements 문서](../requirements-analysis/README.md)에 있다.
mode 제한은 해당 실행 경로에 적용하며 외부 Agent 도구의 sandbox는 아니다. 코드 mapping 미해결·관련 finding 부재는 근거 부족으로 남기고,
JEV 확률은 문서 충돌·시나리오 누락·구현 불일치의 검토 후보로만 사용한다. 실제 원문·업무 계약·DB 동작을 자동 증명하지 않는다.

## 2. 현재 검증할 수 없거나 제한적인 것

| 대상 | 현재 한계 |
|---|---|
| 실제 동시 실행 안전성 | 스레드·다중 인스턴스를 실행해 경쟁 상태를 재현하지 않음 |
| 실제 트랜잭션·롤백 | Spring 프록시, propagation, DB 커밋 결과를 실행으로 확인하지 않음 |
| 운영 DB 정합성 | 실제 적용된 UNIQUE/FK/CHECK, 격리 수준, 잠금 설정을 확인하지 않음 |
| 재시도·중복 처리 안전성 | 멱등키의 저장·전달·원자적 사용 및 외부 서비스의 중복 방지 계약까지 증명하지 못함 |
| 여러 메서드에 걸친 보안 흐름 | 현재 오염 데이터 분석은 메서드 내부 중심. Controller → Service → Repository 흐름을 완전하게 추적하지 못함 |
| 테스트의 업무적 정확성 | assertion이 특정 분기 결과·상태 변화·롤백을 검증하는지 일반적으로 판정하지 못함 |
| 권한·테넌트 격리 | 관련 테스트 항목 추출은 있으나, 실제 소유권·테넌트 접근 제어를 검증하는 전용 위험 규칙은 없음 |
| 언어·빌드 지원 | Java 중심이며, 변이 검증은 Gradle만 지원 |

`--fail-on`은 기본적으로 `CONFIRMED` 결과만 대상으로 한다(`fail.include-review`로 `REVIEW` 포함 가능). 테스트 부족이 남아 있어도 종료 코드가 성공일 수 있다. 새 선택형 정책은 UNKNOWN 비율·신규 테스트 부족·변이 생존도 실패시킬 수 있다. 기본 정책은 기존과 같으며 설정과 예외 처리는 [CI_POLICY.md](../guides/CI_POLICY.md)를 참고한다.

## 3. 추가 적용 우선순위

새 규칙 확대보다 현재 분석이 위험을 놓치는 조건을 줄이는 작업을 먼저 권장한다.

### 1순위: 보호 장치 판정 정확도 개선 — 반영 완료 (2026-09-30)

`CheckThenActRaceRule`의 전체 소스 대상 필드명 검색을 제거했다. 조회·변경 호출 수신 객체의 타입에서 Spring Data 저장소와 엔티티를 연결하며, 상속 저장소의 제네릭 엔티티 타입도 추적한다. 두 호출이 같은 엔티티를 대상으로 하는 경우에만 해당 엔티티의 제약을 확인한다. 타입 연결에 실패하면 `REVIEW` 경고를 유지한다.

반영 내용:

- `existsBy`·`findBy`·`countBy`·`selectBy`의 단순 동등 조건과 `And` 조합에서 조회 속성을 추출한다. 인자 변수 이름과 엔티티 속성 이름을 혼동하지 않는다.
- 연결된 엔티티의 `@Column(unique=true)`, `@Id`, `@Table(uniqueConstraints=...)`를 확인한다. 복수 `@Id`는 복합 키로 묶는다.
- `@Column(name=...)`으로 DB 컬럼과 속성을 연결하고, 복합 UNIQUE의 모든 컬럼이 조회 키에 포함되어야 보호로 인정한다. 조회 키의 일부가 단독으로 UNIQUE인 경우도 전체 조회 키의 중복을 방지하므로 인정한다.
- 제약 이름·컬럼명 부분 문자열·무관한 엔티티의 동일 이름 필드로 경고를 숨기지 않는다.
- UNIQUE 보호는 `save`·`saveAll`·`insert`·`persist`에 한정한다. 조회 후 update/delete의 경쟁을 UNIQUE로 보호했다고 판단하지 않는다.
- `synchronized` 메서드·블록만으로 경고를 숨기지 않으며, 결과에 `JVM_LOCAL_LOCK` 증거를 추가해 JVM 내부 보호 범위를 표시한다.
- 메서드에 잠금 문자열이나 무관한 `putIfAbsent`·`computeIfAbsent` 호출이 있다는 이유만으로 DB 작업 경고를 숨기는 판정도 제거했다.

회귀 테스트는 무관한 엔티티, 서로 다른 조회·변경 저장소, 인자명과 속성명의 차이, 복합 UNIQUE·복합 PK, 명시적 컬럼명, JVM 잠금, 상속·제네릭 저장소, 파일 간 타입 연결과 동일한 단순 엔티티 이름을 포함한다.

남은 한계:

- 소스에 선언된 제약을 판정하며, 운영 DB에 실제 적용되었는지·NULL 값의 중복 정책·제약 충돌 처리·재시도 안전성은 검증하지 않는다.
- 커스텀 쿼리의 실제 조건, `Or`·`IgnoreCase` 등 확장 조건, getter·상속 필드·embedded 키 기반 매핑은 보호를 입증하지 못하므로 검토가 필요하다.
- 실제 비관적 잠금의 트랜잭션 범위와 실행 경로를 입증하지 않는다. 잠금이 있는 코드도 경고가 남을 수 있다.
- 기존 check-then-act 후보 탐지는 조회·변경 인자에 공통 변수가 있는 조건문 중심이다. 모든 경쟁 패턴이나 삽입 성공·멱등성을 보장하는 규칙은 아니다.

근거: [CheckThenActRaceRule.java](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/CheckThenActRaceRule.java), [DatabaseUniquenessProtection.java](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/DatabaseUniquenessProtection.java), [CheckThenActRaceRuleTests.java](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/CheckThenActRaceRuleTests.java)

### 2순위: 보안 검증과 위험 호출의 실행 순서 확인 — 반영 완료 (2026-09-30)

`SEC-003`(Path Traversal)과 `SEC-004`(SSRF)는 메서드 전체의 검사 문자열 대신 **각 위험 호출 위치까지의 제어 흐름과 값**을 확인한다. `SecurityProtectionDetector`에 호출 위치를 전달하며, 호출에 도달하는 모든 분석 경로에서 전달 값에 대한 검증이 성립해야 경고를 생략한다.

반영 내용:

- 블록의 실행 순서와 `if`의 양쪽 분기를 추적한다. `return`·`throw`로 끝난 경로는 이후 호출에 도달하지 않는다. 검증 성공 분기 내부의 호출과, 실패 시 종료하는 검사 뒤의 호출을 구분한다.
- `!`, boolean 동등/비동등 비교, `&&`의 참 경로·`||`의 거짓 경로에서 확실한 검증 조건을 추출한다. 조건부 `return`이나 일부 분기에만 있는 검사는 전체 경로의 보호로 인정하지 않는다.
- 변수 선언·단순 대입의 값을 경로별로 저장해 직접 별칭과 파생 값을 연결한다. 검사 이후 대입은 기존 보호 근거를 제거한다. 동일한 입력에서 파생되었어도 다른 경로·호스트 값을 검사했다면 보호로 인정하지 않는다. 별도 helper 호출의 반환값도 호출 위치가 다르면 같은 값으로 보지 않는다.
- 경로 검증은 명시적으로 `Path` 타입을 선언한 두 변수의 `startsWith`를 대상으로 한다. 문자열 접두사 검사는 경로 보호로 인정하지 않는다. `copy`·`move`의 입력 의존 인자는 모두 같은 값의 검증을 요구한다.
- 호스트 검증은 입력 URI의 `getHost()`와 `allowed`/`allowlist` 이름의 목록에 대한 `contains`를 연결한다. `URI.create`, 단일 인자 `URI`/`URL` 생성, `toString`·`toASCIIString`·`toURL` 변환을 통해 검증한 대상과 실제 요청 대상을 비교한다. 임의의 `contains` 호출이나 다른 호스트 검사는 보호로 인정하지 않는다.
- 같은 메서드의 여러 위험 호출은 각각 판정한다. 앞선 호출의 경고를 뒤의 검사로 숨기지 않는다.

회귀 테스트는 호출 뒤 검사, 다른 파생 값·별도 입력 검사, 일부 분기의 검사, 조건부 종료, 검사 후 재대입, 변수 선언 범위·helper 반환값의 구분, 알 수 없는 호출 경계, 삼켜진 예외, 반복문 내부 검사, lambda 내부 throw, 문자열 경로 검사, 여러 호출, 별칭 및 모든 분기의 검증을 포함한다.

남은 한계:

- 메서드 내부의 블록·분기·명시적 종료 중심의 보수적 분석이다. loop, try/catch/finally, switch, synchronized, 중첩 실행 범위에서 보호를 입증하지 못하면 `REVIEW` 경고를 유지한다. 분석 상태가 128개를 넘는 경우도 보호를 확정하지 않는다.
- 대입과 알 수 없는 호출은 보호 근거를 제거하며, 변수 shadowing이나 알 수 없는 helper의 효과를 입증하지 못하면 안전한 코드에도 경고가 남을 수 있다. 호출 위치를 받지 않는 기존 detector API는 보호 여부를 확정하지 않는다.
- 경로별 값 연결은 제한된 AST 표현식 추적이며, 메서드 간 데이터 흐름·모든 alias/필드 변경·커스텀 validator를 증명하지 않는다. 위험 호출 후보와 입력은 기존 `DataFlowAnalyzer`가 탐지하는 범위에 한정한다.
- `Path.startsWith`의 정적 검증 인식은 실제 파일 시스템의 안전성 증명이 아니다. 정규화의 충분함, symlink·TOCTOU, 허용 루트 설정은 실행으로 확인하지 않는다. 호스트 allowlist의 실제 내용, DNS rebinding·redirect·scheme 정책도 검증하지 않는다.

근거: [SecurityProtectionDetector.java](../../src/main/java/com/example/coderiskanalyzer/production/analysis/dataflow/SecurityProtectionDetector.java), [PathTraversalRuleTests.java](../../src/test/java/com/example/coderiskanalyzer/rule/security/PathTraversalRuleTests.java), [SsrfRuleTests.java](../../src/test/java/com/example/coderiskanalyzer/rule/security/SsrfRuleTests.java)

### 3순위: 타입 기반 호출 판정과 클래스패스 지원 — 반영 완료 (2026-09-30)

`SideEffectDetector`는 호출을 해석한 선언 타입·메서드 시그니처로 DB·HTTP·메시지·파일·프로세스 부수 효과를 판정한다. 알려진 API의 상속·override와 제네릭 타입 인자를 확인하며, 같은 이름의 별도 overload와 무관한 `repository`·`client` 객체를 구분한다. 호출 해석에 실패한 경우에만 기존 이름 추정을 사용한다.

반영 내용:

- Spring Data 저장소, JPA/Hibernate/MyBatis SqlSession, Spring JDBC, Spring/JDK/Apache HTTP 클라이언트, Feign abstract 메서드, 메시지 발행 API와 JDK 파일·프로세스 API를 지원한다. 상세 범위는 [CLASSPATH.md](../guides/CLASSPATH.md)에 있다.
- 부수 효과를 사용하는 `TX-003~005`, `IDEM-001~004`, `REL-001` 결과에 `SIDE_EFFECT_CLASSIFICATION` 증거를 추가한다. `RESOLVED_TYPE`은 해석된 시그니처를, `NAME_HEURISTIC`은 해석 실패 후 이름 추정의 한계를 기록한다.
- `.code-risk.yml`의 `analysis.classpath`에 프로젝트 내부 상대 경로 JAR 목록을 지정할 수 있다. 전체·변경 파일 scan, 추가 생산 코드 문맥 파싱, baseline 생성에 동일한 JAR을 등록한다. 기존 parser·config 생성 API는 호환용 overload를 유지한다.
- 없는 파일·잘못된 JAR·절대 경로·프로젝트 밖 경로·심볼릭 링크 경유는 분석 전에 거부한다. JAR 코드를 실행하거나 대상 빌드 스크립트를 실행하지 않는다.

회귀 테스트는 임의 수신 객체 이름, 무관한 해석 성공 호출, 상속·제네릭 override·overload·파생 삭제, JDBC·메시지·JDK HTTP·Feign helper 구분, 이름 추정 증거, 실제 생성한 외부 JAR과 중복 입력, scan·baseline 연결, 잘못된 클래스패스 입력을 포함한다.

남은 한계:

- 지원 API 목록 밖의 해석 성공 호출은 이름으로 다시 추정하지 않으므로 커스텀 wrapper·미지원 라이브러리 후보가 누락될 수 있다. 상속·전이 의존 타입 연결이 실패하는 경우도 보호나 부수 효과를 증명하지 않는다.
- JAR은 심볼 해석용이며 내부 구현을 분석하지 않는다. JDBC execute·HTTP retrieve 등의 후보는 실제 변경·전송이 실행되었다는 보장이 아니다.
- 클래스패스는 JAR 목록만 지원한다. glob·클래스 디렉터리·자동 의존성 수집은 지원하지 않으며 필요한 전이 의존 JAR도 직접 지정해야 한다.
- 실제 동시성·트랜잭션·멱등성·재시도·부분 실패의 실행 안전성을 검증하는 기능은 아니다.

근거: [SideEffectDetector.java](../../src/main/java/com/example/coderiskanalyzer/production/analysis/sideeffect/SideEffectDetector.java), [SymbolSolverFactory.java](../../src/main/java/com/example/coderiskanalyzer/production/parser/SymbolSolverFactory.java), [SideEffectDetectorTests.java](../../src/test/java/com/example/coderiskanalyzer/analysis/sideeffect/SideEffectDetectorTests.java)

### 4순위: 분석 완전성 지표와 선택형 CI 정책 — 반영 완료 (2026-09-30)

console·JSON·SARIF에 분석 완전성 지표를 추가했다. 선택된 생산 AST의 전체/미해결 메서드 호출 수와 비율, 테스트 적정성 UNKNOWN 수와 비율, 발견·선택·추가 문맥 파일 수, 보안 보호 흐름 판정이 제한되는 구문 개수, 설정된 제외·소스 root·비활성 규칙을 표시한다.

반영 내용:

- `.code-risk.yml`의 `fail.max-unknown-ratio`로 적정성 UNKNOWN 최대 비율을 지정한다. 원래 분석 결과로 계산하여 baseline·suppression으로 불확실성을 숨기지 않는다.
- `fail.new-test-gaps`로 baseline·suppression·규칙 설정 적용 후 남은 신규 MISSING/PARTIAL TEST-001 항목을 차단한다. baseline이 없으면 현재 범위의 미제외 Gap 전체가 대상이다.
- `fail.max-survived-mutations`로 PIT 정상 완료 후 허용할 SURVIVED 개수를 지정한다. 동등 변이 검토를 위한 `fail.mutation-exceptions`는 보고서 fingerprint와 필수 사유로 등록한다. 예외도 원래 결과와 근거를 보존하며, 모호한 중복 fingerprint에는 적용하지 않는다.
- 새 정책은 기본 비활성이고 초과/잔여 항목에 대해 종료 코드 1을 반환한다. 파싱·규칙·빌드/PIT 실행 실패의 기존 종료 코드가 우선한다. `--fail-on off`는 새 선택형 정책을 끄지 않는다.
- 정책 실패에도 보고서를 출력한다. 변이 보고서에는 예외 적용 수·잔여 생존 수·매칭되지 않은 예외를 기록한다. mutation의 정적 문맥 파싱도 동일 설정의 root·excludes·classpath를 사용한다.

회귀 테스트는 기본 호환 동작, 임계치 경계, 잘못된 설정, baseline·suppression, UNKNOWN 제외 우회 방지, 변경 범위·빈 범위, 미해결 호출·제한 구문, 세 보고서 형식, 변이 생존·사유 있는 예외·변이 위치 변경·중복 식별자·실행 실패를 포함한다.

남은 한계:

- 지표는 정적 분석 범위와 관측한 불확실성이다. 모든 미지원 API/패턴, 범위 밖 파일, 실제 제외된 파일 전체를 열거하지 않는다. 파싱 실패·읽기 실패로 관측하지 못한 코드도 있다.
- 호출 해석 비율은 선택한 생산 AST의 메서드 호출만 대상으로 한다. 생성자·테스트·문맥 호출은 포함하지 않는다. UNKNOWN 게이트는 테스트 적정성 비율이며 미해결 호출 비율 게이트와 구분한다.
- 제한 구문 개수는 실제 위험 호출과 연결된 미지원 판정이 아니다. 실제 동시성·트랜잭션·중복 처리·부분 실패 안전성을 실행으로 확인하지 않는다.
- 변이 게이트는 SURVIVED·NO_COVERAGE·RUN_ERROR·MEMORY_ERROR·UNKNOWN을 집계한다(TIMED_OUT·NON_VIABLE 제외). 예외는 SURVIVED에만 적용한다. 현재 PIT 모델의 식별 정보가 같아 여러 결과가 겹치면 예외를 적용하지 않으며, 동등성 자체를 자동 증명하지 않는다.

사용법과 정확한 분모·종료 코드·예외 정책: [CI_POLICY.md](../guides/CI_POLICY.md)

근거: [AnalysisCompleteness.java](../../src/main/java/com/example/coderiskanalyzer/production/report/AnalysisCompleteness.java), [ScanCommand.java](../../src/main/java/com/example/coderiskanalyzer/production/cli/ScanCommand.java), [MutationCommand.java](../../src/main/java/com/example/coderiskanalyzer/mutation/MutationCommand.java), [AnalyzerConfigLoader.java](../../src/main/java/com/example/coderiskanalyzer/production/config/AnalyzerConfigLoader.java)

### 5순위: 실행 기반 증거 연결 — 반영 완료 (2026-09-30)

`scan --execution-evidence`로 JUnit XML과 JaCoCo XML을 테스트 항목에 연결한다. 대상 빌드·DB를 실행하지 않으며, 실제 DB 사용·시나리오와 assertion 검토는 명시적인 사용자 기록으로 관리한다.

반영 내용:

- manifest의 항목 ID와 정적으로 연결된 테스트를 확인한다. 생산·테스트 소스와 두 실행 보고서의 SHA-256을 검증해 변경된 증거를 거부한다.
- 연결된 단일 JUnit testcase의 성공 여부와 메서드 범위의 집계 실행 라인 커버리지를 확인한다. 실패·skip·누락·중복 testcase 또는 미실행 라인은 기존 상태를 유지한다.
- 실행 커버리지, DB 사용 근거, assertion 검토자·이유·필수 동작을 별도 `EXECUTION_TEST` 증거로 console·JSON·SARIF에 출력하고 원래 정적 상태를 보존한다.
- 기존 `PARTIAL` 항목 중 `TRANSACTION_ROLLBACK`은 ROLLBACK, CON-001/002 위험은 CONCURRENCY, IDEM-001 위험은 DUPLICATE_MESSAGE 시나리오의 성공 실행·라인 커버리지·명시적 assertion 검토가 모두 있을 때만 `COVERED`로 변경한다. 커버리지만으로 충분한 assertion으로 판단하지 않는다.
- 선택형 CI 신규 Gap 정책은 연결 후 상태를 사용한다. 다른 Gap 및 생산 위험 finding은 유지한다. 입력 오류·범위 밖 ID·해시 불일치·경로 탈출·심볼릭 링크는 종료 코드 2로 거부한다. 외부 XML 리소스를 읽지 않는다.

회귀 테스트는 실행/커버리지/assertion 분리, 실패·skip·중복·누락 테스트, 부분 커버리지, 시나리오 불일치, 승인 대상 제한, 변경된 소스·보고서, 잘못된 ID·필수 필드·XML, 경로 탈출·symlink·entity, 재호출, 세 보고서 및 CI Gap 유지 검증을 포함한다.

남은 한계:

- 실제 DB 사용·트랜잭션·병렬 충돌·중복 메시지와 assertion 충분함은 검토자 기록에 의존한다. 도구가 실행 안전성을 자동 증명하지 않는다.
- JaCoCo는 집계 라인 커버리지이며 테스트별·분기 커버리지와 구분한다. 보고서에서 누락된 라인과 소스 버전의 실행 연관성을 해시만으로 증명하지 않는다.
- 파라미터화·반복·동적 테스트 이름, 중첩 클래스·멀티모듈의 모호한 sourcefile은 보호를 확정하지 않을 수 있다. manifest는 현재 scan 범위에 맞춰 작성해야 한다.
- 실제 DB·다중 인스턴스 실행 검증은 수행하지 않았다. XML fixture 기반으로 import와 판정을 검증했다.

사용법·입력 형식·신뢰 범위: [EXECUTION_EVIDENCE.md](../guides/EXECUTION_EVIDENCE.md)

근거: [ExecutionEvidenceLinker.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/ExecutionEvidenceLinker.java), [ExecutionEvidenceLinkerTests.java](../../src/test/java/com/example/coderiskanalyzer/testanalysis/ExecutionEvidenceLinkerTests.java), [ScanCommandTests.java](../../src/test/java/com/example/coderiskanalyzer/cli/ScanCommandTests.java)

## 4. 리팩터링 후보

| 대상 | 권장 변경 | 이유 |
|---|---|---|
| `ScanCommand` | 분석 실행, 결과 정책, 출력 처리를 분리 | CLI 클래스가 탐색·파싱·분석·JEV·baseline·파일 출력까지 담당 |
| `TestAdequacyAnalyzer` | 테스트 항목 추출, 생산 코드 연결, assertion 판정을 분리 | 판정 규칙을 독립적으로 검증·확장하기 쉬워짐 |
| 규칙 공통 처리 | 위치 계산·annotation 해석을 공통화 | 반복 구현과 규칙별 판정 차이 감소 |
| AST 탐색 | 메서드·호출·부수 효과 인덱스를 만들어 재사용 | 반복되는 `findAll`과 전체 검색 감소 |
| `MutationRunner` | Gradle 설정 생성, 프로세스 실행, 보고서 수집을 분리 | 실패·타임아웃·멀티모듈 처리를 독립적으로 검증 가능 |

특히 테스트 적정성 분석은 생산 코드 호출을 심볼로 연결한 뒤에도, 일부 예외·Mockito 검증 판정은 메서드 이름이나 문자열에 의존한다. 판정 책임을 분리하면서 동일 이름의 다른 객체·오버로드를 구분하는 개선을 함께 하는 것이 좋다.

관련 코드:

- [ScanCommand.java](../../src/main/java/com/example/coderiskanalyzer/production/cli/ScanCommand.java)
- [TestAdequacyAnalyzer.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/TestAdequacyAnalyzer.java)
- [MutationRunner.java](../../src/main/java/com/example/coderiskanalyzer/mutation/MutationRunner.java)

## 5. 검증 상태

- 최초 문서·구현·관련 테스트 소스 검토 완료. 1~5순위 개선을 구현하고 문서에 반영했다.
- Java 25에서 `./gradlew test --tests '*CheckThenActRaceRuleTests' --no-daemon`: PASS (관련 회귀 테스트 16개).
- 1순위 구현 당시 `./gradlew build --no-daemon`: PASS (전체 테스트 90개, 실패·오류·skip 0개, 패키징 포함).
- 2순위 구현 후 Java 25에서 `./gradlew test --tests '*PathTraversalRuleTests' --tests '*SsrfRuleTests' --no-daemon`: PASS (관련 테스트 11개).
- 2순위 구현 당시 `./gradlew build --no-daemon`: PASS (전체 테스트 95개, 실패·오류·skip 0개, 패키징 포함).
- 3순위 구현 후 Java 25에서 `./gradlew test --tests '*SideEffectDetectorTests' --tests '*AnalyzerConfigLoaderTests' --tests '*RemainingTransactionRulesTests' --tests '*MessageConsumerSideEffectRuleTests' --tests '*RemainingV1RulesTests' --tests '*ScanCommandTests' --no-daemon`: PASS (관련 테스트 32개).
- 3순위 구현 당시 Java 25에서 `./gradlew build --no-daemon`: PASS (전체 테스트 101개, 실패·오류·skip 0개, 패키징 포함).
- 4순위 최종 관련 검증: Java 25에서 `./gradlew test --tests '*ScanCommandTests' --tests '*AnalyzerConfigLoaderTests' --tests '*MutationCommandTests' --no-daemon`: PASS (29개).
- 4순위 최종 `./gradlew build --no-daemon`: PASS (전체 테스트 110개, 실패·오류·skip 0개, 패키징 포함).
- 5순위 최종 관련 검증: Java 25에서 `./gradlew test --tests '*ExecutionEvidenceLinkerTests' --tests '*TestAdequacyAnalyzerTests' --tests '*ScanCommandTests' --tests '*SarifReporterTests' --no-daemon`: PASS (34개).
- 5순위 최종 `./gradlew build --no-daemon`: PASS (전체 테스트 120개, 실패·오류·skip 0개, 패키징 포함).
- PIT 및 실제 DB·다중 인스턴스 실행 검증은 수행하지 않았다. 5순위는 실행 증거 import와 명시적 검토 연결을 구현했다.
