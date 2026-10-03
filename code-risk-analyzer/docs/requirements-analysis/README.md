# 요구사항 분석 산출물 v1

workflow 1단계에서 사용할 구조화 형식과 가상 주문·재고 예제다.
`requirements` CLI에서 입력 검증, 모드별 실행 제한, 명시적 코드 연결과 선택적 JEV 검토를 실행한다.
기존 TEST-001과 실행 증거 manifest의 형식을 변경하지 않는다.

## 작업 모드 결정

작업 시작 시 사용자 요청의 목적을 보고 모드를 기록한다. 정의서에 코드 이름이 등장하거나
저장소에 코드가 있다는 이유만으로 코드 작업을 시작하지 않는다.

| 모드 | 허용 작업 | 수행하지 않는 작업 |
|---|---|---|
| SPEC_ONLY | 제공된 정의서·요구사항 읽기, 조건·모순·질문·테스트 시나리오 계획 작성, 산출물 schema 검증, 문서 근거의 JEV 검토 | production·테스트 소스 탐색, 코드 scan, 테스트·빌드·서비스 실행, 코드 변경, 코드 근거의 JEV 검토 |
| SPEC_CODE_REVIEW | 명시적으로 요청한 정의서와 기존 코드 대조, 관련 코드·기존 테스트 읽기, 정적 scan과 근거 수집 | 코드 변경, 별도 요청 없는 테스트·빌드·서비스 실행 |
| CODE_CHANGE | 승인된 구현·버그 수정·테스트·분석기 개발 작업과 관련 검증 | 요청 범위 밖 변경. 테스트 작성 단계의 production 코드 변경 |

순수 정의서 분석은 기존 테스트 기준선이나 코드 정적 분석을 실행하지 않는다.
정의서만으로 transaction 업무 단위와 불변 규칙을 설계할 수 있지만, 구현에 transaction이 적용된다고 판단할 수는 없다.
의도가 명확하지 않으면 문서 분석까지만 진행하고 코드 대조·변경에 필요한 범위를 확인한다.
JEV 판정만으로 모드를 변경하거나 코드 도구 실행을 승인하지 않는다.
이 저장소에서 schema와 계약 테스트를 개발하는 작업은 CODE_CHANGE이고,
그 테스트가 검증하는 SPEC_ONLY 예제를 분석하는 작업과는 구분된다.

## 파일

- `requirements-analysis.schema.json`: JSON Schema Draft 2020-12
- `order-inventory.definition.md`: 가상 정의서, 미결정 항목과 미승인 충돌 대안 포함
- `order-inventory.analysis.json`: SPEC_ONLY, 정상·중복·동시성·rollback·키 충돌 시나리오 계획
- `order-inventory.conflict.analysis.json`: SPEC_ONLY, 문서의 서로 충돌하는 두 계약을 명시적으로 기록
- `order-inventory.code-review.analysis.json`: SPEC_CODE_REVIEW, 대조할 코드가 제공되지 않은 상태를 UNKNOWN으로 기록

예제의 테스트 종류는 작성 계획이며 실행 결과가 아니다. 실제 주문·재고 코드를 추가하거나 검증하지 않았다.
코드 미제공 예제의 `codeRevision=NO_CODE_PROVIDED`는 명시적 미확인 표시이며 소스 버전이 아니다.

## 필드와 검증 범위

root의 `version`, `mode`, `project`, `requirements`는 필수다.
requirements는 REQ-* 키를 사용하는 객체, scenarios는 SCN-* 키를 사용하는 객체다.
요구사항마다 원문 위치·버전·인용, 도메인 소유자·aggregate·의존 방향·진입점,
사전·사후 조건·불변 규칙·핵심 로직, transaction·동시성·멱등성·정합성 관점,
시나리오의 조건·입력·기대 결과·테스트 수준과 검토 결과를 기록한다.

각 관점의 `applicability`는 APPLICABLE, NOT_APPLICABLE, UNKNOWN이다.
APPLICABLE에는 구체적 expectations가 필요하며 NOT_APPLICABLE에는 이유와 빈 expectations를 쓴다.
UNKNOWN은 확인할 질문을 남겨야 한다. 빈 문자열·공백만 있는 값·알 수 없는 필드는 거부한다.
검토 결과는 EVIDENCE_SUPPORTED, CONTRADICTED, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE이다.
앞의 두 상태에는 근거가, INSUFFICIENT_EVIDENCE에는 미확인 질문이 필요하다.
EVIDENCE_SUPPORTED는 제공된 근거 범위의 검토 결과이며 구현·실행 안전성의 보장이 아니다.

SPEC_ONLY에서는 `codeRevision`과 `mappings`를 금지하고,
CODE·STATIC_FINDING·TEST·EXECUTION 종류의 review evidence도 금지한다.
코드를 보지 않았으므로 구현 상태를 NOT_IMPLEMENTED라고 판단하지도 않는다.
코드 대조·변경 모드는 codeRevision과 mappings를 요구하며
IMPLEMENTED/PARTIAL에는 코드 위치가 필요하다.

Schema는 필수값·타입·enum·조건별 근거 유무를 확인한다.
자연어 조건의 업무적 모순, 원문 인용의 정확성, 코드 경로 존재·소유권,
reference 정합성, JEV의 정확성이나 실제 도구 호출 금지는 증명하지 않는다.
객체 키 중복은 엄격한 JSON parser로 거부해야 한다. 테스트는 strict duplicate detection을 사용한다.
원문·근거가 유효한지와 UNKNOWN에 대응하는 질문이 충분한지는 별도 검토가 필요하다.
실제 작업 실행기는 **도구 호출 전에 mode에 맞는 허용 작업을 검사**해야 한다.
현재 실행기는 전체 계획을 검증하고 코드 preflight를 마친 뒤 도구를 호출한다.
이 제한은 requirements 경로에 적용하며 별도로 호출한 scan/mutation이나 외부 Agent 도구를 통제하는 sandbox는 아니다.

## JEV 확장 용도

| 용도 | SPEC_ONLY | 코드 대조·변경 모드 |
|---|---|---|
| 용어·요구사항의 모호함과 문서 간 충돌 검토 | 원문 위치·두 계약·판정 기준만 전달 | 코드의 관측 사실을 별도 근거로 추가 가능 |
| 실패·경계·중복·동시성·보상 시나리오 누락 검토 | 계획과 정의서에서 누락 후보 제안 | 실제 진입점·부수 효과와 계획의 차이 검토 |
| 확인 질문과 검토 우선순위 | 업무 영향과 미확인 조건을 기준으로 제안 | 정적 severity·영향·실행 근거도 참고 |
| 요구사항과 구현 계약의 불일치 | 수행하지 않음 | 구조화된 요구사항과 관측한 코드 사실 대조 |
| 테스트 assertion의 충분함·동등 변이 검토 | 수행하지 않음 | 테스트·변이·기대 결과를 비교해 검토 후보 제안 |

한 질문에는 판단할 조건을 좁혀 정의서 원문·기준·근거를 연결한다.
모델 판단을 원문이나 정적 관측 사실과 혼합하지 않는다.
근거가 부족하면 판단을 보류하고 추가 문서·질문·테스트로 연결한다.
높은 점수로 안전성을 승인하거나 실행 검증·검토 없는 mutation 예외를 자동 등록하지 않는다.
기존 scan의 IDEM-001 질문은 유지한다. 새 요구사항 JEV는 문서 충돌·시나리오 누락과 코드 불일치를 별도 경로로 처리한다.
나머지 활용은 확장 설계다. SDK 요청·응답은 대체 transport로 검증했으며 실제 외부 API는 호출하지 않았다.

## CLI 실행

빌드된 실행 JAR로 구조화한 입력을 전달한다. 원문 문서를 자동으로 찾아 읽거나 자연어 정의서를 JSON으로 변환하지 않는다.
입력의 `source.excerpt`, REQUIREMENT 근거와 시나리오 계획을 사용하며 원문 정확성은 사용자가 확인한다.

```sh
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements \
  docs/requirements-analysis/order-inventory.analysis.json
```

기본 action은 `DOCUMENT_ANALYSIS`, phase는 `ANALYSIS`다. transaction/concurrency/idempotency가 APPLICABLE인데
ROLLBACK/CONCURRENCY/DUPLICATE 시나리오가 없으면 구조적인 누락 후보를 남긴다.
전체 계획에서 모드·허용 action·phase·scope를 검사하며, 코드 작업은 추가 preflight까지 통과해야 실행한다.
중복 JSON 키, 후행 JSON, 필수값 누락, 잘못된 모드, SPEC_ONLY의 코드 근거는 분석 전에 거부한다.

| action | SPEC_ONLY | SPEC_CODE_REVIEW | CODE_CHANGE |
|---|---|---|---|
| DOCUMENT_ANALYSIS, DOC_JEV | 허용 | 허용 | 허용 |
| CODE_COMPARE, SCAN | 차단 | 파일 scope 내 정적 대조 | 파일 scope 내 정적 대조 |
| CODE_JEV | 차단 | 앞서 수집한 정적 근거 필요 | 변경 후에는 새 정적 대조 필요 |
| WRITE_TESTS | 차단 | 차단 | 승인 필요, TEST_WRITING phase, src/test/만 변경 |
| IMPLEMENT | 차단 | 차단 | 승인 필요, IMPLEMENTATION phase |
| TEST, BUILD | 차단 | 차단 | 승인 필요, 지정 프로젝트의 Gradle 실행 |

실행기에는 서비스 실행 action이 없다. SPEC_ONLY에서는 해당 작업을 요청할 수도 없으며,
최종 로컬 서비스 검증 절차는 별도 승인된 CODE_CHANGE 작업에서 수행한다.

### 코드 scope와 정적 근거

`--scope scope.json`으로 독립적인 요청 범위를 전달한다. 예를 들어 코드 대조용 scope는 다음 형식이다.

```json
{
  "project": "/absolute/path/to/target-project",
  "files": ["src/main/java/example/OrderService.java"],
  "actions": []
}
```

입력의 `mappings.code`에는 같은 상대 경로와 `example.OrderService#create(String,int)` 형식의 symbol을 쓴다.
매개변수는 소스에 선언된 타입 문자열을 사용한다. `OrderService#create`처럼 생략하면 일치하는 메서드가
한 개일 때만 연결하며 overload를 추측하지 않는다. 생성자·field mapping은 지원하지 않아 근거 부족으로 남는다.

```sh
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements input.json \
  --actions CODE_COMPARE --scope scope.json
```

전체 프로젝트 discovery, 설정·classpath 읽기, 다른 source root로의 자동 확장을 하지 않는다.
명시한 파일의 AST를 파싱하고 mapping 메서드 범위에 primary location이 있는 TX/CON/IDEM finding만 연결한다.
소스 파일 SHA-256, 관측 위치·ruleId·confidence·근거와 원래 expectations를 함께 기록한다.
다른 메서드의 호출자 finding을 mapping된 callee에 자동 연결하지 않는다. 필요한 호출자·의존 파일을 직접 추가한다.
미해결 symbol·파싱 실패·관련 finding 부재는 `INSUFFICIENT_EVIDENCE`, finding이 있으면 `REVIEW_REQUIRED`다.
NOT_APPLICABLE은 입력의 업무 판단을 보존한다. `codeRevision`은 입력이 선언한 값이고 실제 버전 확인은 파일 해시로 보완한다.
부분적인 분석 실패와 외부 의존성 한계는 diagnostics에 남기며, finding이 없다고 안전 판정하지 않는다.
새 근거는 `staticEvidence`에 분리하고 입력 `analysis.review`의 사용자 판단을 자동 승격하지 않는다.

### 승인된 변경과 검증

CODE_CHANGE 실행 요청은 scope의 `actions`에 WRITE_TESTS/IMPLEMENT/TEST/BUILD 중 승인된 action을 직접 기록해야 한다.
입력 JSON이나 JEV가 이 권한을 부여하지 않는다. CLI 호출자가 기존 사용자 승인에 맞춰 scope를 제공한다.
서명된 권한 시스템이나 OS sandbox를 제공하는 것은 아니다.
기존 테스트를 변경할 때 요구사항 변경·잘못된 테스트의 근거를 확인하는 workflow 규칙도 유지한다.

파일 변경은 `--changes changes.json`으로 제공한 내용을 적용한다. 코드 자동 생성은 수행하지 않는다.

```json
{
  "changes": [
    {
      "path": "src/test/java/example/OrderTests.java",
      "expectedSha256": "ABSENT",
      "content": "package example;\nclass OrderTests {}\n"
    }
  ]
}
```

위 content는 파일 교체 형식 예제다. 실제 테스트에는 요구사항의 기대 결과를 검증하는 assertion이 필요하다.
기존 파일에는 정확한 현재 SHA-256(소문자 64자리), 신규 파일에는 `ABSENT`를 쓴다.
변경 파일은 scope.files에 명시한다. 상대 경로 탈출·symlink·중복 경로·해시 불일치를 거부하며,
TEST_WRITING에서는 production·기존 동작·신규 동작 코드 변경을 허용하지 않는다.

```sh
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements change-input.json \
  --actions WRITE_TESTS --phase TEST_WRITING --scope scope.json --changes changes.json
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements change-input.json \
  --actions TEST,BUILD --phase VERIFICATION --scope scope.json
```

동일 실행기를 이용하는 writer는 프로젝트별 advisory file lock으로 직렬화하고 lock 안에서 전체 해시를 먼저 검사한다.
같은 신규 파일 계획의 재시도는 ABSENT 불일치로 거부한다. 외부 편집기는 lock을 공유하지 않으므로
동시에 수동 편집하는 상황의 충돌을 완전히 방지하지는 않는다.
각 파일 교체는 atomic move를 사용하지만 여러 파일 변경은 하나의 transaction이 아니다.
도중 실패 시 적용한 파일 목록을 남기고 후속 action을 중단한다. 기록과 현재 해시를 확인해 새 계획을 작성한다.
검증 실패도 후속 action을 중단한다. 테스트 삭제·assertion 약화·skip 추가는 자동으로 수행하지 않는다.

TEST/BUILD는 승인된 프로젝트의 실행 가능한 `gradlew`로 각각 `test`/`build --no-daemon --rerun-tasks`를 수행한다.
해당 프로젝트의 전체 Gradle 작업이 실행되며, scope.files만 테스트하는 기능은 아니다.
timeout은 10분이며 timeout·중단 시 관측한 자식 프로세스를 종료한다. 별도 OS process sandbox는 없다.
보고서는 exit code와 상태를 남기며 subprocess 로그는 저장하거나 출력하지 않는다.
실패 원인 상세 로그가 필요하면 승인된 환경에서 동일 Gradle 명령을 직접 실행한다.

### 선택적 JEV

```sh
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements \
  docs/requirements-analysis/order-inventory.conflict.analysis.json \
  --actions DOCUMENT_ANALYSIS,DOC_JEV
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar requirements input.json \
  --actions CODE_COMPARE,CODE_JEV --scope scope.json
```

공식 SDK의 `TYPESAFE_API_KEY` 환경 변수를 사용한다. 키를 scope·입력·명령 인수·로그에 넣지 않는다.
DOC_JEV에는 정의서 발췌·REQUIREMENT 근거·조건·시나리오·질문만 전달하며 mappings·소스 버전·정적 관측은 보내지 않는다.
CODE_JEV는 별도 질문에 정적 근거와 선언된 소스 버전을 함께 전달한다.
기존 IDEM-001 scan 질문과 독립적이며 finding을 승격하거나 권한·모드·phase를 바꾸지 않는다.
모든 JEV 확률은 `REVIEW_REQUIRED`이고 원문·근거를 다시 확인할 질문을 남긴다.
호출 실패·키 부재는 `INSUFFICIENT_EVIDENCE`로 기록하고 이미 얻은 정적 결과를 보존한다.
30초 timeout, 자동 재시도 없음. 원격 확률을 동작 정확성이나 문서 완전성의 증명으로 쓰지 않는다.

출력 wrapper는 `mode`, `analysis`, `documentChecks`/`staticEvidence`, `jevReviews`, `executions`를 구분한다.
root `COMPLETED`는 요청 action을 끝냈다는 뜻이며 요구사항 검증 완료를 뜻하지 않는다.
종료 코드: 0 실행 완료, 1 변경·검증 실패, 2 입력·계획 거부, 3 JEV 미실행/실패로 추가 검토 필요.
코드 근거 부족과 JEV 검토 후보는 종료 코드와 별도로 결과의 개별 status를 확인한다.

## 개발 검증

산출물 형식 개발을 검증할 때 저장소 루트에서 실행한다.
SPEC_ONLY 분석 작업을 수행하는 사용자가 이 저장소의 테스트·빌드를 실행해야 한다는 뜻은 아니다.

```sh
./gradlew test --tests '*Requirements*Tests' --tests '*LocalRequirementsCodeToolsTests' --no-daemon
./gradlew build --no-daemon
```

기존 json-schema-validator 2.0.4를 implementation으로 옮겨 CLI에서도 사용한다.
schema는 문서의 단일 파일을 JAR resource로 포함하고 동기화 테스트로 확인한다.
정상·충돌 문서 형식, 모드별 금지 근거, 필수값 누락, 검토 근거·질문 부재,
테스트 계획 누락과 미지원 버전을 확인한다.

형식 참고: [JSON Schema Validation 2020-12](https://json-schema.org/draft/2020-12/json-schema-validation).
`CODE_CHANGE`의 변경 전 기준선·기존 테스트 보호·실제 서비스 검증·완료 판정은
[회귀 검증 실행 절차](regression-workflow.md)를 따른다. 별도 runner를 명시적으로 선택하며
`SPEC_ONLY`에는 이 코드 검증 절차를 적용하지 않는다.
다음 확장 대상은 요구사항 의미 대조의 정밀도와 지원 테스트 경로 확대다.
