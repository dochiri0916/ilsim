# CODE_CHANGE 단계 실행 제어

[실행기](../../scripts/code_change_workflow.py)는 기존 requirements 작성 실행기와 회귀·근거 검토 실행기를 한 세션으로 연결한다. `SPEC_ONLY`·`SPEC_CODE_REVIEW`는 대상 코드 해시·탐색·빌드 전에 거부한다. 기존 독립 CLI는 유지하며, 이 세션을 거치지 않는 외부 편집기·명령까지 제한하는 OS sandbox는 아니다.

단계 이름과 옵션을 직접 고르지 않으려면 [`scripts/ask.py`](../../scripts/ask.py)에 자연어 요청을 넘긴다. 같은 단계를 같은 검사로 실행하며, 판단 규칙은 [AGENT_BRIEF](../AGENT_BRIEF.md)의 "가장 쉬운 사용"에 정리했다.

## 순서와 완료 조건

| 명령 | 선행 조건 | 수행과 차단 조건 |
|---|---|---|
| baseline | 새 전용 세션, 승인된 CODE_CHANGE | JSON Schema 검증, 기존 전체 테스트·빌드 실제 실행. 실패하면 작성 차단. 기준선 덮어쓰기 금지 |
| test-writing | 성공한 기준선 | 승인된 `src/test/` 파일만 `WRITE_TESTS/TEST_WRITING`으로 작성. production·기존 동작·신규 동작 코드 작성 금지 |
| implementation | 성공한 test-writing | 승인된 변경을 `IMPLEMENT/IMPLEMENTATION`으로 적용 |
| generate-tests / generate-implementation | 해당 작성 단계의 선행 조건과 명시적 agent 또는 routing 설정 | 독립 CLI 세션으로 변경을 생성하고 기존 작성 실행기로 적용. [로컬 Agent 생성 연결](agent-generation.md)과 [모델 라우팅](model-routing.md) 참고 |
| verification | 구현 단계 완료 | 같은 기존 테스트·새 기능 테스트를 전체 재실행, 기준선 비교, 최종 정적 scan. 실패·skip·삭제·검토 없는 기존 테스트/빌드 변경은 후속 검토 차단 |
| repair | 구현 단계 완료, 명시적 repair·qualityGates 설정 | 실제 검증 → 실패 분류 → 승인된 production 구현 수정 → 재검증. 수락한 테스트·빌드 입력 고정, 반복·시간·모델 호출 한도 유지 |
| local-service | 성공한 verification, MYSQL fixture adapter 또는 `serviceAdapter` | 최종 JAR를 별도 JVM으로 기동하고 HTTP·독립 MySQL 조회·종료 검증(fixture) 또는 선언한 네 시나리오 명령 실행(일반). 실패·중단·소스 변경 시 과거 서비스/검토 수락 제거 |
| review | 성공한 verification | 최종 실행에 연결된 assertion·요구사항·소스·실행 해시 검토, 별도 서비스 검증 결과 확인. 미해결 위험과 실패는 완료 차단 |
| complete | 성공한 review | 현재 소스·문서·환경·JUnit·JAR·scan·검토 파일을 다시 확인하고 요구사항/assertion 근거 재검토 |
| pull-request | COMPLETED 세션 | 검토 파일을 다시 확인하고 완료 근거로 `pull-request.md` 본문 작성. `submit: true`는 plan의 `PULL_REQUEST` 승인, 커밋·푸시된 feature 브랜치, 검증 소스 일치를 확인한 뒤 `gh pr create --draft`만 호출 |

test-writing 성공은 **파일 작성** 성공이다. 예상한 원인의 테스트 실패나 assertion의 의미를 자동 증명하지 않는다. 테스트를 직접 설계하고 필요한 구현 전 실행을 별도로 기록한다. 도메인·MySQL 테스트 수준과 영향 범위도 plan의 명시적 매핑이며 자동 추론하지 않는다.

일반 프로젝트의 서비스 검증은 프로젝트별 별도 프로세스/DB adapter가 수행하고 `review --service`로 결과를 전달한다. 실행기는 서비스의 PASS, 정상·중복·동시성·rollback, 종료 여부, 최종 소스와 JAR 해시를 검사한다. 모든 프로젝트의 서비스를 자동 기동하는 adapter는 제공하지 않는다.

저장소의 MySQL fixture는 config에 `"fixtureAdapter": "MYSQL"`을 추가해 새 실행기가 직접 연결한다. 이 preset은 정확히 `integration-fixtures/spring-risk-sample`에만 허용한다. verification은 기존 명시적 실행 근거 연결을 재사용하고 그 scan을 세션에 고정한다. local-service는 전용 이름·소유 label을 확인한 컨테이너와 환경 변수의 DB 설정을 사용한다. 이 preset에서는 성공한 local-service 없이 review할 수 없고, 외부 서비스 JSON으로 실행 결과를 교체할 수 없다.

```sh
python3 scripts/code_change_workflow.py local-service \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json \
  --container code-risk-sample-012345abcdef
```

컨테이너 이름은 해당 실행에서 생성한 실제 이름을 전달한다. 서비스 DB URL의 loopback 포트·DB 이름·사용자도 그 컨테이너와 대조해 다른 DB를 거부한다. credential은 환경 변수로만 전달하며 metadata·명령 인자·보고서에 저장하지 않는다. preset의 review에는 `--service`를 생략하면 세션의 `service.json`을 사용한다. fixture 정책은 별도로 명시해야 하며 예외 대상은 확대하지 않는다.

## 설정과 실행

```json
{
  "plan": "/absolute/metadata/plan.json",
  "requirements": "/absolute/metadata/requirements.json",
  "analyzer": "/absolute/analyzer/build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar",
  "scope": "/absolute/metadata/scope.json",
  "documents": ["docs/definition.md"]
}
```

metadata 경로는 절대 경로를 사용한다. documents는 대상 프로젝트 기준 상대 경로로, 관련 원문 문서를 빠짐없이 선언한다. 누락한 문서의 변경을 감지한다고 보장하지 않는다. scope와 plan의 프로젝트는 같은 canonical 절대 경로여야 한다. plan은 BASELINE·REGRESSION·LOCAL_SERVICE·COMPLETE를 승인하고, scope는 필요한 WRITE_TESTS·IMPLEMENT와 정확한 변경 파일을 승인한다. plan·changes·scope·review bundle 형식은 [회귀 검증](regression-workflow.md), [작성 실행](README.md), [근거 검토](evidence-review.md)를 따른다.

세션은 대상 프로젝트의 `build/` 아래 **새 전용 디렉터리**에 저장한다. 각 명령에 같은 config/state를 전달한다.

```sh
python3 scripts/code_change_workflow.py baseline \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json
python3 scripts/code_change_workflow.py test-writing \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json \
  --changes /absolute/test-changes.json
python3 scripts/code_change_workflow.py implementation \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json \
  --changes /absolute/implementation-changes.json
python3 scripts/code_change_workflow.py verification \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json
```

기존 테스트·빌드 입력을 변경했다면 verification에 `--approvals`를 추가한다. 기존 테스트의 실패·skip·삭제 결과는 승인 기록으로 통과시키지 않는다. verification의 실제 `after.json`과 `scan.json`을 기준으로 검토 bundle을 만들고, 최종 JAR로 별도 서비스 검증을 수행한다.

```sh
python3 scripts/code_change_workflow.py review \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json \
  --bundle /absolute/reviews.json --service /absolute/service.json \
  --open-evidence /absolute/open-evidence.json
python3 scripts/code_change_workflow.py complete \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json
python3 scripts/code_change_workflow.py pull-request \
  --config /absolute/config.json --state /absolute/project/build/change-001/state.json \
  --pull-request /absolute/pull-request.json
```

`pull-request.json`은 `{"title": "...", "base": "main", "submit": false}` 형식이다. `submit`을 생략하거나 `false`로 두면 본문 초안만 만들고 외부 호출을 하지 않는다. 실행기는 커밋·푸시를 하지 않으며 PR은 항상 draft로 연다. 본문은 모델 호출 없이 세션 근거에서 결정적으로 생성한다.

### 일반 서비스 adapter

fixture가 아닌 프로젝트는 config에 `serviceAdapter`를 두면 `local-service`를 실행할 수 있다.

```json
"serviceAdapter": {
  "jar": "build/libs/app.jar",
  "readyPath": "/actuator/health",
  "scenarios": {
    "normal": ["python3", "checks/normal.py"],
    "duplicate": ["python3", "checks/duplicate.py"],
    "concurrency": ["python3", "checks/concurrency.py"],
    "rollback": ["python3", "checks/rollback.py"]
  }
}
```

실행기는 verification에서 기록한 JAR를 `127.0.0.1`의 빈 포트로 기동하고, 준비 경로가 200을 반환하면 각 시나리오 명령을 `SERVICE_BASE_URL` 환경변수와 함께 실행한다. 종료 코드 0만 PASSED로 기록하고 프로세스 출력은 저장하지 않는다. 마지막에 프로세스 종료와 포트 반환을 확인한다. 시나리오가 참조하는 프로젝트 파일은 해시로 고정되어 바뀌면 세션이 무효화된다. 이 adapter를 쓰면 `review`에 외부 `--service` 결과를 대신 넣을 수 없다. 시나리오 명령의 assertion과 DB 상태 확인은 프로젝트가 책임진다. `serviceAdapter`의 선택 항목은 `arguments`, `readyTimeoutSeconds`(기본 60), `scenarioTimeoutSeconds`(기본 120)이며 `--server.port`·`--server.address`는 지정할 수 없다.

일반 프로젝트에는 fixture 정책을 전달하지 않는다. fixture만 별도 승인한 정확한 예외 목록을 `review --fixture-policy`로 전달한다. production 위험은 일반 프로젝트에서 계속 차단한다. fixture의 승인된 결함을 재현해 COMPLETED가 되더라도 `targetRiskStatus=UNRESOLVED`를 유지한다.

## 근거 무효화와 복구

- 승인된 작성 작업을 시작할 때 기존 검증·검토·완료 수락을 제거한다. 부분 작성 실패와 중단은 후속 단계를 열지 않는다.
- 외부 소스·테스트 변경을 감지하면 STALE로 기록하고 검증·검토·완료 수락을 제거한다. 첫 요청은 거부한다. 현재 변경을 확인하고 다시 test-writing/implementation을 거치거나, 이미 구현 단계가 성공한 세션에서 verification을 재실행한다.
- JUnit·JAR·환경·scan·검토 파일 변경은 STALE로 기록한다. verification 재실행 → 현재 실행에 대한 새 review → complete가 필요하다. report 파일만 고쳐 승인 상태를 만들 수 없다.
- 선언된 문서·구조화 요구사항·plan·scope·분석기 변경은 INPUTS 무효화다. 기존 권한·검토를 자동 갱신하지 않으며 새 승인 세션이 필요하다. 이미 코드를 변경했다면 그 상태를 변경 전 기준선이라고 기록하지 말고, 원래 변경 전 상태/기준선과 변경 범위를 보존해 재계획한다.
- review를 새로 시작하면 이전 검토 수락을 제거한다. 새 검토가 실패해도 과거 승인으로 complete를 실행할 수 없다.
- local-service를 새로 시작하거나 verification을 다시 실행하면 과거 서비스 수락도 제거한다. 서비스 실패·중단 후에는 local-service → review → complete를 다시 수행한다. 소스/실행 근거가 바뀌었다면 verification부터 다시 수행한다.

`state.json`은 단계 이력·무효화 이유·입력 해시를 기록하며 `baseline.json`은 최초 실제 실행이다. 최종 결과는 state의 `completion`과 `review-result.json`에 저장한다. 반환 코드는 성공 0, 검증 실패 1, 잘못된 요청/오래된 근거 2, 검토 필요 3이다. 이력은 서명된 감사 기록이 아니며 metadata와 세션 파일을 신뢰하는 협업 절차다. 같은 프로젝트의 회귀 실행과 advisory lock을 공유하지만 외부 편집기나 독립 Java 명령을 완전히 직렬화하지 않는다.

근거 해시는 선언된 파일의 변경을 검출한다. 전이적 업무 영향, assertion 의미, 실제 DB 버전의 진위, 모든 위험의 부재를 증명하지 않는다.

## 적용 후 검증

새 연결의 전체 검증 명령은 저장소 루트에서 `python3 integration-fixtures/spring-risk-sample/scripts/verify_workflow.py`다. 전용 MySQL에서 실제 baseline → test-writing → implementation → verification → local-service → review → complete를 호출한다. 기존 가격 정책 테스트와 production 파일은 **동일 내용으로 재작성**해 작성 경로를 확인한다. 신규 업무 기능을 구현하거나 의도된 결함을 수정하는 검증이 아니다. 이후 구현 단계에서 할인율을 일시적으로 11로 바꿔 기존 assertion의 실제 실패를 확인하고 서비스·완료 차단을 검증한 뒤 원래 10으로 복구한다. 테스트를 수정하거나 약화하지 않는다. 기동 실패, 실제 SIGTERM 중단, 서비스 실행 중 임시 소스 변경과 복구도 검증하며, 소스 변경 시 다른 편집자의 변경을 덮어쓰지 않는다. 일시 변경을 수행하므로 해당 fixture를 다른 검증/편집과 동시에 사용하지 않는다.

각 실행의 전용 `build/workflow-session-*/state.json`, `failure-checks.json`, `review-result.json`과 요약 `build/workflow-session-summary.json`을 남긴다. 세션 summary는 실제 JVM 종료와 전용 컨테이너 종료를 함께 확인한 뒤 완료된다. `SPEC_ONLY` 등 다른 모드는 Docker·코드·서비스 실행 전에 거부한다. SIGTERM/정상 예외 경로의 정리를 검증하며 SIGKILL·호스트 종료까지 자원 정리를 보장하지 않는다.

- 최초 단계 실행기 적용 시 Java 204개, Python 46개 테스트와 Gradle build 통과. 당시 Python에는 제어기 테스트 17개가 포함됐다.
- fixture 예외 없는 일반 프로젝트의 완료 경로, 회귀·skip·미해결 위험·실패한 재검토·근거 변경·세션 경로 탈출 차단을 테스트했다. 이 테스트의 서비스 결과는 제어기 계약 검증용 입력이며 실제 서비스 실행이라고 주장하지 않는다.
- 격리된 Java 프로젝트에서 실제 requirements CLI로 테스트 작성과 구현을 수행하고, Gradle 전체 테스트 2개 및 실제 scan까지 통과했다. 시작 로그와 JSON 응답이 섞이는 문제도 수정했다. 이 실행은 기존 주문 예제를 schema/CLI 입력 검증용으로 사용했으며 주문 업무 검증은 아니다.
- 기존 MySQL 8.4.8 fixture 테스트 17개와 별도 JVM 서비스의 정상·중복·동시성·rollback, 독립 DB 상태 조회 및 프로세스/컨테이너 종료를 재검증했다. 기존 완료 판정은 COMPLETED이고 의도된 production 위험은 UNRESOLVED로 보존했다. 일반 프로젝트별 서비스 adapter의 실제 실행은 별도 연결이 필요하다.
- MYSQL adapter 연결 후 Python 59개가 통과했다. 실제 CLI 전체 세션과 기존 assertion 실패·기동 실패·SIGTERM·서비스 중 소스 변경의 4개 실패/복구 검증도 통과했다. 최종 세션은 COMPLETED, verification/evidence review는 PASSED, targetRiskStatus는 UNRESOLVED다. 기존 production·테스트·요구사항의 승인된 해시가 모두 복구/보존됐고 마지막 JVM과 전용 컨테이너도 종료했다. 복구 명령이 거부되더라도 검증기가 임시로 바꾼 내용만 복원하는 경로도 별도 테스트했다.

## 제한된 구현 수정과 최종 품질 게이트

기존 단계 실행을 유지하고, 자동 수정에는 다음 설정을 명시한다. `qualityGates.targets`는 승인된 `src/main/` 파일과 정확한 Java 클래스 이름을 연결한다. 현재 coverage 수집은 루트 Java 프로젝트의 `test`·`jacocoTestReport`에 한정한다.

```json
"repair": {"maxIterations": 3, "maxModelCalls": 6, "timeoutSeconds": 900},
"qualityGates": {
  "targets": {"example.Boundary": "src/main/java/example/Boundary.java"},
  "minimumLineCoverage": 0.9,
  "minimumBranchCoverage": 0.8,
  "minimumMutationScore": 0.9,
  "maxSurvivingMutations": 0,
  "timeoutSeconds": 600
}
```

```sh
python3 scripts/code_change_workflow.py repair --config /absolute/config.json --state /absolute/project/build/session/state.json
```

`repair`는 VERIFICATION 통과까지만 수행한다. 별도 서비스 검증, 근거 검토와 `complete`는 기존 선행 조건을 그대로 적용한다. 새 세션을 만들지 않고 `repair`를 다시 실행해도 누적 호출·반복 횟수와 시작 시각을 초기화하지 않는다. 최대 수정은 3회, 설정 가능한 모델 호출은 1~12회, 전체 실행 시간은 1~3600초다. 호출 한도에는 수정 과정의 JEV와 CLI 호출 및 응답 계약 재시도가 모두 포함된다. 각 실행기는 남은 시간 이하의 timeout을 사용하고 종료 정리 시간은 별도로 필요할 수 있다.

| 실패 | 자동 수정 |
|---|---|
| 실제 JUnit assertion 실패 | 승인된 production 파일만 수정 |
| 기존 production Java 파일의 javac 오류 | 로그에 검증된 파일·행이 있을 때 수정 |
| 제한된 런타임 예외 | production stack 위치가 확인된 경우만 수정 |
| 승인된 production 파일의 CONFIRMED HIGH/CRITICAL finding | 구현 수정 후 정적 분석 재실행 |
| 환경·의존성·인증·잔액·timeout·입력 변경·누락/skip 테스트·검토 없는 계약 변경 | 중단, NEEDS_REVIEW |
| 낮은 JEV confidence·UNKNOWN·scope 위반·선언되지 않은 fallback | 중단, NEEDS_REVIEW |
| coverage 부족·살아남은/미커버 변이·PIT 미완료·오래된 근거 | 중단, NEEDS_REVIEW |

실패에 원본 로그나 credential을 저장하지 않고 유형, 테스트 ID와 검증된 production 파일·행만 전달한다. 수락된 테스트와 빌드 입력의 해시는 수정 전후에 동일해야 한다. Registry·Policy 경로는 이전보다 낮은 Tier로 돌아가지 않고 선언된 다음 fallback만 사용한다. 자동 `max` 선택은 계속 금지한다.

품질 설정을 활성화하면 기준선과 최종 검증의 전체 테스트 실행에 임시 JaCoCo init script를 적용한다. 최종 테스트·회귀·정적 검증이 통과한 뒤 기존 `mutation --files-file` CLI로 새로운 PIT 실행을 수행한다. 빌드 파일이나 테스트 설정을 덮어쓰지 않는다. 목표 클래스마다 LINE·BRANCH와 mutation score를 검사하고, 변이가 없거나 결과가 미완료이면 통과시키지 않는다. `TIMED_OUT`, `RUN_ERROR` 등 미확정 변이는 killed로 세지 않는다. 변이 예외나 Agent 판단으로 이 게이트를 면제하지 않는다.

`quality.json`은 최종 snapshot·소스·정책, JaCoCo XML/exec, mutation 응답, main/test 바이트코드 해시에 연결된다. `review`·`complete`에서 이를 다시 검사한다. `repair-N/`에는 각 검증과 생성 artifact의 해시 보호 사본을 남기며, 이력이 변경되면 재개를 차단한다. 미설정된 기존 수동 경로는 기존 검증 계약을 유지하지만, 새 `repair` 경로에는 품질 설정이 필수다.
