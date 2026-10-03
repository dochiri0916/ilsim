# 실행 기반 테스트 증거

`scan --execution-evidence execution-evidence.json`은 이미 실행한 JUnit XML과 JaCoCo XML을 정적 테스트 항목에 연결한다. 대상 빌드·테스트·DB를 실행하지 않는다. 기본 scan 동작은 동일하다.

```shell
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan /path/to/project --format json --execution-evidence execution-evidence.json
```

## 준비 절차

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

## 판정과 CI

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

## 입력 오류와 한계

- 잘못된 manifest/XML, 필수 필드 누락, 해시 불일치, 현재 범위 밖 ID, 중복 ID, 연결되지 않은 테스트는 종료 코드 2와 `EXECUTION-001`로 거부한다. 부분 적용된 보고서는 출력하지 않는다. 변경 파일 scan에서는 현재 분석 범위에 해당하는 entries만 전달해야 한다.
- 절대 경로·프로젝트 밖 경로·심볼릭 링크는 거부한다. XML의 외부 DTD/entity와 schema 로딩은 비활성화하며 내부 DTD 선언도 거부한다. JaCoCo의 일반적인 외부 DOCTYPE은 네트워크 없이 읽는다.
- 해시는 입력 파일의 변경을 탐지한다. 보고서가 실제로 그 소스를 실행한 결과인지는 증명하지 않는다. 보고서와 manifest는 신뢰할 수 있는 동일 CI 작업에서 생성·보관해야 한다. scan 중 파일을 동시에 수정하는 사용 방식은 지원하지 않는다.
- JaCoCo는 전체 실행의 집계 커버리지다. 특정 테스트가 해당 라인을 실행했다고 증명하지 않으며, 보고서에서 누락된 실행 라인도 복원하지 않는다. 동일 package/sourcefile이 여러 번 등장하는 멀티모듈 집계는 보호를 확정하지 않는다. 중첩 클래스의 package 매핑과 비표준 JUnit 이름은 연결에 실패할 수 있다.
- DB 사용·롤백·중복 전달·실제 병렬 실행과 assertion의 충분함은 검토자의 명시적 기록에 의존한다. 동시성 검토는 sleep만으로 실행이 겹쳤다고 가정하지 말고 latch/barrier 등으로 충돌을 유도했는지 확인한다. 롤백 검토는 Spring proxy 및 transaction 경계와 실패 후 커밋 상태를 확인한다. 중복 메시지 검토는 같은 비즈니스 키의 재전달 후 DB·외부 부수 효과의 횟수를 확인한다.
- 이 기능은 실행 증거와 검토 근거를 연결하며 운영 환경의 모든 경쟁·재시도·부분 실패·멱등성을 보장하지 않는다. 현재 프로젝트의 회귀 검증은 XML fixture를 사용하며 실제 DB 테스트를 실행하지 않는다.

구현: [ExecutionEvidenceLinker.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/ExecutionEvidenceLinker.java)
