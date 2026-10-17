# 분석기 참고서

명령, 분석 완전성과 CI 정책, 클래스패스, 실행 증거의 기준을 이 문서에서 관리한다.
아래 명령은 `code-risk-analyzer/`에서 실행하며 분석 대상 프로젝트는 별도 인자로 지정한다.

- [분석기 명령](#분석기-명령)
- [분석 완전성과 CI 정책](#분석-완전성과-ci-정책)
- [클래스패스와 타입 판정](#클래스패스와-타입-판정)
- [실행 기반 테스트 증거](#실행-기반-테스트-증거)

## 분석기 명령

`CRA` = `java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar` (`./gradlew bootJar`로 생성)

빌드는 JDK 21 toolchain과 `--release 21`을 사용한다. 실행 JAR은 Java 21 이상에서 실행하며 소비 프로젝트에 분석기 의존성이나 Gradle 플러그인을 추가할 필요가 없다. `.code-risk.yml`이 없으면 기본 설정으로 검사한다.

| 명령 | 용도 |
|---|---|
| `CRA scan <project> [--files-file f] [--baseline b] [--jev true] [--fail-on] [--format json --output f]` | 위험 22개 규칙 + `TEST-001` 적정성. 종료 1 게이트 실패, 2 입력 오류, 3 분석 실패 |
| `CRA baseline create <project>` | 기존 finding 기준선(`scan --baseline`용) |
| `CRA rules [ID] [--format json]` | 기존 규칙 목록·설명 및 동시성 명세 대응. 명세 ID로도 조회 가능 |
| `CRA rules --spec [--format json]` | 동시성 명세 22개 우선순위·구현 진행 상태·근거·남은 작업(스캔 실행 여부와 별개) |

`.code-risk.yml`은 분석기 자체의 설정만 관리한다. 기존 `validators` 항목은 지원하지 않으며 남아 있으면 설정 오류로 거부한다. 컨벤션 검사는 소비 프로젝트의 Gradle 플러그인으로 별도 적용한다.

규칙: `SEC-001~007`(SQL·Command·Path·SSRF·비밀값·객체 권한·Tenant), `CON-001~005`(005: Java DCL), `IDEM-001~004`, `TX-001~005`, `REL-001`.

동시성 명세는 기존 ID와 CI 계약을 유지해 연결한다. 자체 규칙으로 10개 유형이 PARTIAL, 12개가 PLANNED이며 전체 명세 완료는 아니다. JSON/SARIF의 `concurrencySpecification`은 구현 진행표, finding의 `concurrency`는 증거에 따른 명세 대응·판정·우선순위다. `concurrencyAssessments`는 DCL의 보호 입증·누락 근거를 포함한 감사 기록이며 SAFE_BY_PROOF는 경고로 세지 않는다. DCL의 새 기준선은 줄 번호 없는 v2 지문을 병행 저장하고 기존 v1을 계속 읽는다. 자세한 범위·근거는 [동시성 구현 진행표](concurrency.md)를 따른다.

## 분석 완전성과 CI 정책

`scan`의 console, JSON, SARIF 보고서는 분석 범위와 정적 분석의 한계를 표시한다. JSON에서는 `metrics.completeness`, SARIF에서는 `runs[0].properties.metrics.completeness`에 기록한다. 기존 JSON 버전 `1`에 필드를 추가하며 기존 필드는 유지한다.

### 분석 완전성 지표

| 필드 | 의미 |
|---|---|
| `filesDiscovered` | 설정된 생산·테스트 소스 범위와 excludes 적용 후 발견한 파일 수 |
| `filesSelected` | 전체 scan 또는 `--files-file`로 선택한 seed 파일 수 |
| `filesParsedAsContext` | seed 외에 테스트 연결·호출된 생산 헬퍼 분석을 위해 추가 파싱한 파일 수. 독립 위험 진입점은 확장하지 않음 |
| `methodCalls`, `unresolvedMethodCalls`, `unresolvedCallRatio` | 선택된 생산 AST의 메서드 호출 수, 심볼 해석 실패 수, 실패 수 / 전체 호출 수 |
| `testObligations`, `unknownTestObligations`, `unknownRatio` | 현재 적정성 분석 범위의 항목 수, UNKNOWN 수, UNKNOWN 수 / 전체 항목 수 |
| `limitedPatterns` | 선택된 생산 AST에서 보안 보호 제어 흐름의 입증이 제한되는 LOOP·TRY·SWITCH·SYNCHRONIZED 구문 개수 |
| `configuredSourceRoots`, `configuredExcludes`, `disabledRules` | 사용자가 지정한 소스 범위·제외 패턴·비활성 규칙 |
| `limitations` | 분석 범위와 기본 탐색 제외, 실행 안전성을 입증하지 않는다는 설명 |

분모가 0인 비율은 0이다. 호출 지표는 생성자, 테스트·추가 문맥 호출을 포함하지 않는다. 파싱할 수 없는 파일의 호출도 셀 수 없으며, 별도의 `metrics.filesFailedToParse`와 PARSE 진단을 함께 확인해야 한다. recoverable parse error는 기존 PARSE 경고를 유지한다.

`limitedPatterns`는 파일 제외나 모든 규칙의 미지원 판정이 아니다. 보안 보호 검사를 보수적으로 처리할 수 있는 구문 개수이며, 실제 위험 호출과 연결되었는지는 별도 규칙 결과를 확인한다. 메서드 간 흐름·커스텀 API 등 모든 미지원 패턴을 열거하는 지표는 아니다.

탐색은 기본적으로 `.git`, `.gradle`, `build`, `target`, `out`, `node_modules`, `generated`, `generated-sources` 하위 디렉터리와 심볼릭 링크를 제외한다. 설정한 root 자체는 탐색 대상이다. 읽기 실패 항목이 누락될 수 있다. 설정 범위 밖 파일 수나 실제 제외된 파일 전체 목록은 수집하지 않는다. 변경 파일 scan은 선택한 생산 파일을 위험 진입점으로 삼고 관련 문맥으로 테스트 적정성을 분석한다. SQL·명령 주입은 진입점의 오염된 인자가 전달된 생산 헬퍼도 추적하므로 finding 위치가 미변경 파일일 수 있다. 관련 문맥의 독립 메서드를 전체 위험 진입점으로 추가하지는 않는다. 높은 완전성 수치는 실제 동시성·롤백·멱등성·부분 실패·권한의 안전성 증명이 아니다.

보안 sink는 해석 가능한 JDBC·JPA·Runtime API 타입을 우선 사용하고, 타입이 해석되지 않는 경우에만 이름 휴리스틱을 사용한다. 파일 copy/move는 원본과 목적지 경로를 모두 확인한다. 동시성 보호는 실제 읽기 호출의 Spring Data `@Lock` 또는 JPA 잠금 인자를 확인하며 무관한 문자열·주석·서비스 메서드의 `@Lock`은 보호 근거가 아니다. 트랜잭션 catch는 모든 정상 종료 경로에서 직접 `setRollbackOnly()`를 호출하거나 예외를 재던져야 보호로 본다. 람다 안의 throw, 조건부 rollback만 있는 경로, 보상처럼 보이는 메서드 이름은 보호를 입증하지 않는다.

### 설정

대상 프로젝트의 `.code-risk.yml`에 지정한다. 새 정책은 기본적으로 모두 비활성이다. 단계별 처리 기준은 아래 표를 따른다.

| 대상 | 기본 | 선택형 차단 |
|---|---|---|
| CONFIRMED 위험 | 차단(`fail.severity`·`confidence`) | `--fail-on off`로 해제 |
| REVIEW 위험 | 통과 | `include-review` |
| Test Gap(MISSING/PARTIAL) | 통과 | `new-test-gaps` |
| UNKNOWN 비율 | 통과 | `max-unknown-ratio` |
| 파싱·규칙 실행 실패 | 항상 종료 코드 3 | - |
| JEV 실패 | 경고 | `on-jev-failure` |

```yaml
fail:
  severity: HIGH
  confidence: HIGH
  max-unknown-ratio: 0.25
  new-test-gaps: true
  include-review: true
  on-jev-failure: true
```

- `scan`: `max-unknown-ratio`는 **테스트 적정성 UNKNOWN 비율**의 최대 허용치다. 0~1의 유한 숫자이며, 초과할 때 종료 코드 1과 `CI-UNKNOWN` 진단을 반환한다. baseline·suppression·TEST-001 비활성으로 분석 불확실성을 숨기지 않도록 원래 적정성 결과를 사용한다. 미해결 호출 비율과는 별도 지표다.
- `scan`: 선택형 [실행 증거 연결](#실행-기반-테스트-증거) 후 `new-test-gaps: true`이면 baseline·suppression·규칙 설정 적용 후 남은 TEST-001의 MISSING/PARTIAL 항목에 대해 종료 코드 1과 `CI-TEST-GAPS` 진단을 반환한다. COVERED/UNKNOWN은 이 정책의 실패 대상이 아니다. baseline이 없으면 현재 범위의 모든 미제외 Gap을 신규로 취급한다. `baseline create`로 기존 항목을 기록할 수 있다. baseline fingerprint가 바뀐 항목은 다시 신규로 취급된다.

- `scan`: `include-review: true`이면 `--fail-on`/`fail.severity`·`confidence` 게이트가 `CONFIRMED`뿐 아니라 `REVIEW` finding도 차단한다. 종료 코드 1. `--fail-on off`이면 적용되지 않는다.
- `scan`: `on-jev-failure: true`이면 `--jev` 실행이 실패(`JEV-001`)할 때 종료 코드 1과 `CI-JEV` 진단을 반환한다. 기본은 경고만 남기고 정적 결과를 유지한다.

- `scan`: `jev.max-calls`(0 이상 정수; 없으면 환경변수 `CODE_RISK_JEV_MAX_CALLS`, 그것도 없으면 무제한)는 `--jev` 실행 한 번의 JEV API 호출 상한이다. 초과분 검토는 건너뛰고 정적 결과를 유지하며 `JEV-002` 경고 진단에 건너뛴 개수를 남긴다. 종료 코드에는 영향을 주지 않는다.

임계치와 같으면 통과한다. `--fail-on off`는 기존 CONFIRMED 심각도 게이트만 끄며 새 선택형 정책은 유지한다. scan의 파싱/규칙 실행 실패는 정책 위반보다 우선하여 기존 종료 코드 3을 반환한다. 보고서는 정책 실패 시에도 출력된다.

## 클래스패스와 타입 판정

분석 대상 프로젝트 루트의 `.code-risk.yml`에 외부 의존 JAR을 지정할 수 있다.

```yaml
analysis:
  classpath:
    - libs/spring-data-commons.jar
    - libs/spring-web.jar
    - libs/dependency-api.jar
```

경로는 프로젝트 루트 기준 상대 경로이며 세 가지 형태를 쓸 수 있다.

| 형태 | 예 | 처리 |
|---|---|---|
| JAR 파일 | `libs/spring-web.jar` | 읽을 수 있는 올바른 JAR이어야 한다 |
| JAR glob 패턴 | `libs/*.jar`, `libs/**/*.jar` | `.jar`로 끝나야 하며, 프로젝트 안의 일치하는 JAR을 경로 순으로 펼친다. 하나도 없으면 설정 오류 |
| 클래스 디렉터리 | `build/classes/java/main` | `.class`가 하나 이상 있어야 한다. 바이트코드를 임시 JAR로 묶어 읽기만 하고 클래스를 로드·실행하지 않는다 |

절대 경로·루트 밖 경로·심볼릭 링크 경유(패턴의 고정 부분 포함)·없는 파일·잘못된 JAR·빈 클래스 디렉터리는 설정 오류로 거부한다. 패턴 탐색은 심볼릭 링크를 따라가지 않는다. 환경 변수 확장과 자동 의존성 수집은 지원하지 않으므로 필요한 전이 의존 JAR도 직접(또는 패턴으로) 지정해야 한다. 같은 항목이 여러 번 나오면 한 번만 등록한다.

`scan`의 전체·변경 파일 분석, 추가 생산 코드 문맥 파싱, baseline 생성에 동일하게 적용한다. 설정을 생략하면 기존 소스 루트와 JDK Reflection 기반 해석을 사용한다. 대상 Gradle/Maven 스크립트나 JAR의 코드를 실행하지 않으며, 의존성을 자동 다운로드하지 않는다.

### 판정 방식

`SideEffectDetector`는 호출의 선언 타입과 메서드 시그니처를 먼저 해석한다. 알려진 API의 메서드와 일치하는 override도 상속 타입·제네릭 인자를 확인해 연결한다. 같은 이름의 별도 overload나 임의의 `repository`·`client` 변수는 해석된 API가 다르면 부수 효과로 분류하지 않는다.

현재 인식하는 API와 후보:

| 영역 | API 및 호출 |
|---|---|
| DB 변경 | Spring Data Crud/ListCrud/JpaRepository의 save·delete 계열, deleteBy/removeBy 파생 메서드, JPA EntityManager·Hibernate Session·MyBatis SqlSession의 저장·삭제 호출 |
| JDBC | JdbcOperations/JdbcTemplate 및 NamedParameter 변형의 update·batchUpdate·execute |
| HTTP | RestOperations/RestTemplate·RestClient·WebClient의 exchange·retrieve·getForObject·getForEntity·postForObject·postForEntity·put·patch·delete 등, JDK HttpClient의 send/sendAsync, Apache HttpClient의 execute |
| Feign | `@FeignClient` 타입의 abstract 인스턴스 메서드. default/static helper는 제외 |
| 메시지 | KafkaOperations/KafkaTemplate·AmqpTemplate/RabbitTemplate·JmsOperations/JmsTemplate의 send/convertAndSend 등, ApplicationEventPublisher의 publishEvent |
| 프로세스·파일 | Runtime.exec·ProcessBuilder.start, Files의 쓰기·삭제·복사·이동, OutputStream/Writer.write |

호출 해석이 실패한 경우에만 기존 수신 객체·메서드 이름 추정을 사용한다. 부수 효과를 사용하는 `TX-003~005`, `IDEM-001~004`, `REL-001` 결과에는 `SIDE_EFFECT_CLASSIFICATION` 증거를 추가한다. `basis=RESOLVED_TYPE`은 해석된 시그니처를 포함하고, `basis=NAME_HEURISTIC`은 이름 추정의 부정확성 가능성을 명시한다. 결과의 `REVIEW` 상태와 기존 CI 실패 정책은 유지한다.

### 한계

- 지원 목록은 모든 라이브러리·커스텀 wrapper를 포함하지 않는다. 해석에 성공한 미지원 API는 이름 추정으로 다시 분류하지 않으므로 후보가 누락될 수 있다.
- 외부 JAR은 심볼 해석용이다. JAR 내부 구현, 실제 HTTP 전송·DB 변경, annotation 기반 proxy의 실행 의미를 분석하지 않는다. JDBC execute나 HTTP retrieve도 실행된 부수 효과의 증명이 아닌 후보 판정이다.
- 일부 의존 타입·상속 정보가 없으면 알려진 API를 연결하지 못할 수 있다. 해석 실패 시 이름이 임의인 객체는 기존 이름 추정으로도 탐지되지 않을 수 있다.
- 실제 트랜잭션 경계, 동시 실행 안전성, 멱등성, 재시도·부분 실패 안전성을 실행으로 증명하지 않는다.

## 실행 기반 테스트 증거

`scan --execution-evidence execution-evidence.json`은 이미 실행한 JUnit XML과 JaCoCo XML을 정적 테스트 항목에 연결한다. 대상 빌드·테스트·DB를 실행하지 않는다. 기본 scan 동작은 동일하다.

```shell
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan /path/to/project --format json --execution-evidence execution-evidence.json
```

### 준비 절차

1. 대상 프로젝트에서 실제 DB를 사용하는 롤백·동시성·중복 메시지 테스트와 JaCoCo 보고서를 같은 소스 버전으로 생성한다. 일반 JUnit 테스트의 XML `classname`과 `name`을 사용한다. `name="method"`와 Gradle의 `name="method()"`를 지원한다. 파라미터화·반복·동적 테스트의 표시 이름 매핑은 지원하지 않는다.
2. 기본 JSON scan에서 `TEST_OBLIGATION`의 `obligationId`와 `Required` 메시지를 확인한다. 증거를 연결할 테스트는 `attributes.tests`에 있어야 한다.
3. 생산 소스·테스트 소스·JUnit XML·JaCoCo XML의 SHA-256을 계산한다. macOS/Linux에서 `shasum -a 256 <file>`을 사용할 수 있다. 아래 자리표시자를 실제 해시와 항목 ID로 바꾼다. 경로는 대상 프로젝트 기준 상대 경로다.
4. 실제 DB 사용 및 시나리오를 검토해 `database`와 `databaseEvidence`에 기록한다. assertion의 충분함을 검토했다면 `assertionReview`를 추가한다. 아직 검토하지 않았다면 해당 객체 전체를 생략한다.
5. 증거를 지정해 scan을 다시 실행한다. console·JSON·SARIF 모두 `EXECUTION_TEST` 증거를 출력한다.

```json
{
  "version": 1,
  "entries": [
    {
      "obligationId": "OBLIGATION-<scan에서 얻은 ID>",
      "test": "example.OrderServiceTest#rollsBackAfterSecondWriteFails",
      "scenario": "ROLLBACK",
      "database": "PostgreSQL 18 / Testcontainers",
      "databaseEvidence": "실제 DB와 Spring proxy 사용, 두 번째 쓰기 실패 후 새 트랜잭션에서 저장 상태 확인",
      "sourceSha256": "<생산 소스의 소문자 SHA-256 64자리>",
      "testSourceSha256": "<테스트 소스의 소문자 SHA-256 64자리>",
      "junit": "build/test-results/test/TEST-example.OrderServiceTest.xml",
      "junitSha256": "<JUnit XML의 소문자 SHA-256 64자리>",
      "jacoco": "build/reports/jacoco/test/jacocoTestReport.xml",
      "jacocoSha256": "<JaCoCo XML의 소문자 SHA-256 64자리>",
      "assertionReview": {
        "reviewer": "검토자 식별자",
        "reason": "예외 assertion 외에 두 쓰기가 모두 롤백되었음을 새 트랜잭션으로 확인하는 assertion 검토",
        "requiredEvidence": "assert state rollback after failure"
      }
    }
  ]
}
```

### 판정과 CI

| 증거 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|
| `testPassed` | 정확히 연결된 단일 JUnit testcase에 failure/error/skipped가 없음 | DB 종류, 실제 트랜잭션 시작·종료, 업무 의미 |
| `methodLinesCovered` | 해당 package/sourcefile이 하나이며 메서드 범위의 실행 가능 보고 라인이 존재하고 모든 라인의 missed instructions가 0 | 테스트별 커버리지, 분기 커버리지, 모든 소스 라인이 보고서에 포함되는지 |
| `database` / `databaseEvidence` | 사용자가 기록한 실제 DB·시나리오 검토 근거 | 도구가 DB 사용이나 동시 실행을 직접 검증했다는 의미 |
| `assertionReview` | 필수 동작과 정확히 일치하는 `requiredEvidence`, 검토자 및 비어 있지 않은 이유 | assertion 의미의 자동 증명 |

다음 항목은 **기존 `PARTIAL` + 성공한 연결 테스트 + 메서드 라인 커버리지 + 명시적 assertion 검토 + 일치하는 DB 시나리오**를 모두 만족할 때만 `COVERED`가 된다.

| 항목 | 요구 시나리오 |
|---|---|
| `TRANSACTION_ROLLBACK` | `ROLLBACK` |
| `RISK` / `CON-001`, `CON-002` | `CONCURRENCY` |
| `RISK` / `IDEM-001` | `DUPLICATE_MESSAGE` |

`TRANSACTION_ROLLBACK` 추출은 `saveAndFlush`도 저장 동작으로 인식한다. 기존과 같이 `@Transactional` 메서드에 복수 쓰기 또는 쓰기와 예외 경로가 있는 경우에만 항목을 생성한다. annotation과 호출 이름에 기반한 정적 후보이며 실제 rollback 보장은 연결된 실행·검토 근거로 확인해야 한다.

다른 항목과 `MISSING`/`UNKNOWN`은 증거를 표시해도 상태를 바꾸지 않는다. 커버리지와 assertion 검토는 각각 기록하고 원래 `staticStatus`를 보존한다. `databaseVerifiedByAnalyzer`는 항상 false다. 커버리지만으로 특정 동작의 충분함을 인정하지 않는다. 실행 보고서가 정상적으로 읽혔지만 테스트 실패·skip·누락·중복 testcase·미실행 라인이 있으면 원래 상태를 유지하고 관측 결과를 표시한다.

CI 정책은 증거 연결 후 상태를 사용한다. 승인된 `COVERED`는 `fail.new-test-gaps` 대상에서 빠지지만 다른 `MISSING`/`PARTIAL`은 계속 실패한다. UNKNOWN 비율은 상태를 바꾸지 않으므로 그대로다. 생산 위험 finding의 상태·심각도는 변경하지 않는다. baseline 생성은 기존 정적 분석만 사용한다. suppression·baseline·TEST-001 비활성 설정은 기존 방식대로 이후 적용되므로 증거 finding을 숨길 수 있다.

### 입력 오류와 한계

- 잘못된 manifest/XML, 필수 필드 누락, 해시 불일치, 현재 범위 밖 ID, 중복 ID, 연결되지 않은 테스트는 종료 코드 2와 `EXECUTION-001`로 거부한다. 부분 적용된 보고서는 출력하지 않는다. 변경 파일 scan에서는 현재 분석 범위에 해당하는 entries만 전달해야 한다.
- 절대 경로·프로젝트 밖 경로·심볼릭 링크는 거부한다. XML의 외부 DTD/entity와 schema 로딩은 비활성화하며 내부 DTD 선언도 거부한다. JaCoCo의 일반적인 외부 DOCTYPE은 네트워크 없이 읽는다.
- 해시는 입력 파일의 변경을 탐지한다. 보고서가 실제로 그 소스를 실행한 결과인지는 증명하지 않는다. 보고서와 manifest는 신뢰할 수 있는 동일 CI 작업에서 생성·보관해야 한다. scan 중 파일을 동시에 수정하는 사용 방식은 지원하지 않는다.
- JaCoCo는 전체 실행의 집계 커버리지다. 특정 테스트가 해당 라인을 실행했다고 증명하지 않으며, 보고서에서 누락된 실행 라인도 복원하지 않는다. 동일 package/sourcefile이 여러 번 등장하는 멀티모듈 집계는 보호를 확정하지 않는다. 중첩 클래스의 package 매핑과 비표준 JUnit 이름은 연결에 실패할 수 있다.
- DB 사용·롤백·중복 전달·실제 병렬 실행과 assertion의 충분함은 검토자의 명시적 기록에 의존한다. 동시성 검토는 sleep만으로 실행이 겹쳤다고 가정하지 말고 latch/barrier 등으로 충돌을 유도했는지 확인한다. 롤백 검토는 Spring proxy 및 transaction 경계와 실패 후 커밋 상태를 확인한다. 중복 메시지 검토는 같은 비즈니스 키의 재전달 후 DB·외부 부수 효과의 횟수를 확인한다.
- 이 기능은 실행 증거와 검토 근거를 연결하며 운영 환경의 모든 경쟁·재시도·부분 실패·멱등성을 보장하지 않는다. 현재 프로젝트의 회귀 검증은 XML fixture를 사용하며 실제 DB 테스트를 실행하지 않는다.

구현: [ExecutionEvidenceLinker.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/ExecutionEvidenceLinker.java)
