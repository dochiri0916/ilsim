# Runner Protocol (v1)

Runner가 서버로 outbound HTTP만 사용한다. 모든 요청은 `X-Ilsim-Token` 헤더가 필요하다.
프로젝트 경로와 테스트·분석 명령은 Runner 로컬 설정(`~/.ilsim/runner.json`)이 소유하며 서버로 보내지 않는다.

| 요청 | 설명 |
|---|---|
| `POST /runner/api/poll` `{runnerId, projects:[{id, name}]}` | 프로젝트 보고 + 작업 요청. 작업이 있으면 `200 Assignment`, 없으면 `204` |
| `POST /runner/api/tasks/{id}/events` `{stage, type, data}` | 진행 이벤트. type: `log`, `claude` 등 스트림 요약 |
| `POST /runner/api/tasks/{id}/result` `StageResult` | 단계 결과 보고 |
| `GET /runner/api/secrets/jev` | 웹에서 등록한 JEV 키 `{key}`. 등록된 키가 없으면 `204`. Runner 는 작업을 받을 때마다 조회해 메모리에만 둔다 |

```
Assignment  { taskId, projectId, stage, attempt, feature, model, effort, prompt }
StageResult { passed, retryable, diff?, error?, artifacts{path: 내용}, validation{검증명: 상태}, branch? }
```

- `model`은 서버가 정한 Claude 5.5 세대 모델 ID(`claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`)이고, `effort`는 추론 강도(기본 `medium`, 서버의 `ILSIM_EFFORT`로 변경)다. Runner 는 `claude --model <model> --effort <effort>`로 실행한다. 별칭은 최신 모델로 바뀔 수 있어 쓰지 않는다.
- `retryable=true`로 실패하면 서버가 `error`를 피드백으로 같은 단계를 다시 배정한다. 최대 3회, 초과하면 작업이 BLOCKED 된다.

## 단계

`REQUIREMENTS`(승인) → `TEST_CASES`(승인) → `TEST_CODE` → `IMPLEMENTATION`

- 단계 상태: `PENDING → RUNNING → WAITING_APPROVAL | PASSED | FAILED`
- 작업 상태: `ACTIVE | COMPLETED | BLOCKED`

## Runner가 단계 안에서 강제하는 검증

Claude의 판단이 아니라 Runner가 워크트리(`~/.ilsim/worktrees/<projectId>-<taskId8>`)에서 직접 실행한다.

1. 허용 경로: 단계별로 변경 가능한 파일 범위를 diff로 검사한다. 벗어나면 `git reset --hard`로 되돌리고 재시도한다.
2. `TEST_CASES`: 요구사항 ID와 테스트 케이스의 추적성(`traceability`)을 검사한다.
3. `TEST_CODE`, `IMPLEMENTATION`: 정적 분석 명령(`analysis`) → 아키텍처 규칙(`architecture`) → 코드 컨벤션(`convention`) → 트랜잭션·동시성 위험(`risk`) → 테스트 무력화 검사(`test-integrity`, `TEST_CODE`만) → 테스트(`tests`) → 변이 테스트(`mutation`, `IMPLEMENTATION`만) 순으로 실행한다.
   - `TEST_CODE`에서 테스트가 모두 통과하면 실패다(구현이 없는데 통과). 실패해야 `RED`다.
   - `IMPLEMENTATION`에서는 테스트가 통과해야 한다.

`validation` 값:

| 값 | 의미 | 실패 여부 |
|---|---|---|
| `PASSED` | 검증을 실행했고 통과 | 아님 |
| `FAILED` | 검증을 실행했고 실패 | 실패 (재시도) |
| `RED` | `TEST_CODE`에서 테스트가 의도대로 실패 | 아님 |
| `UNKNOWN` | 실행할 근거가 없음: 명령 미설정, 승인된 아키텍처 규칙 없음, 규칙 모델이 다른 프로젝트용 | 아님 (화면에 회색 표시) |
| `CONCERN` | AI 변경 검토가 누락·무관한 변경·테스트 우회를 지적함 | 아님 (화면에 경고, 산출물 확인) |
| `LOW` | 변이 점수가 기준 미만 (테스트가 구현을 충분히 검증하지 못함) | 아님 (화면에 경고) |
| `SKIPPED` | 이 프로젝트는 해당 검증 대상이 아니라고 판정됨 (아키텍처 적용 가능성 `NOT_APPLICABLE`) | 아님 (화면에 회색 표시) |

`architecture`는 사용자가 승인한 규칙만 적용하며 `BLOCK` 심각도 위반만 실패로 본다. `REVIEW` 위반은 막지 않는다.
적용 가능성이 `PARTIAL`이면 일관된 영역만 검사하고 로그에 제외 사유를 남긴다.

`convention`과 `risk`는 이번 작업에서 바뀐 파일에만 적용한다(기존 코드의 예외는 막지 않는다).
`convention`은 승인된 규칙의 `BLOCK` 위반만, `risk`는 프로젝트 설정으로 `BLOCK`인 규칙(기본: TX-003, CON-002, CON-003)의 위반만 실패로 본다.
`risk`의 `REVIEW` 후보는 로그에 권고와 함께 남기며, 사용자가 승인한 프로젝트 메모리의 오탐 학습에 해당하는 후보는 제외한다.

`test-integrity`는 바뀐 테스트 파일에서 비활성화(`@Disabled`, `@Ignore`, 주석 처리), 단언 없음, 항상 통과하는 단언, 삼킨 예외를 찾아 실패로 본다. 테스트 실행 후 바뀐 테스트 클래스에 건너뛴 케이스가 있어도 `tests`가 `FAILED`다.
`mutation`은 프로젝트에 변이 테스트 명령(`set <id> mutation <명령>`)이 있을 때만 실행하며 PIT의 `mutations.xml`에서 바뀐 소스의 점수를 계산한다. 구현 단계에서는 테스트를 고칠 수 없으므로 막지 않고, 점수가 기준(60%) 미만이면 `LOW`로 기록하고 살아남은 변이를 로그에 남긴다.

`ai-audit`는 정적 검사를 모두 통과한 `TEST_CODE`·`IMPLEMENTATION` 시도마다 AI가 변경을 독립적으로 한 번 더 본 결과다. 지적 유형은 `OMISSION`(요구사항·테스트 케이스 누락), `UNRELATED`(무관한 변경), `TEST_BYPASS`(테스트 약화·우회), `RULE`(정적 검사가 놓친 규칙 위반), `WEAK_TEST`(변이 테스트에서 살아남은 중요한 동작)이다. 상태는 `PASSED`, `CONCERN`, `UNKNOWN`(호출 실패)이며 결과는 `ai-audit.md` 산출물과 로그로 남는다.

- `WEAK_TEST`를 뺀 지적은 `error`에 담아 `retryable=true`로 되돌려 Agent가 해당 부분만 고치게 한다. 구현 단계에서는 테스트를 고칠 수 없으므로 `WEAK_TEST`는 승인자에게만 보인다.
- 마지막 시도(3회차)에서는 지적이 있어도 작업을 막지 않고 승인자에게 넘긴다. AI 오판이 작업을 BLOCKED로 만들지 않게 하기 위함이다.
- 변이 테스트의 살아남은 변이는 로그 출력, Kotlin 널 검사 intrinsics, `toString`/`hashCode` 안의 것을 정적으로 거른 뒤 상위 10건만 AI 입력에 넣는다.

## JEV

TypeSafe JEV 는 텍스트를 만들지 않고 yes/no·선택·점수 판단의 확률만 돌려주는 결정 모델이다. 서버와 Runner 모두 `jev-typesafe` 라이브러리로 호출한다. 키는 웹 설정 화면(`PUT /api/settings/jev-key`)에서 등록하며 서버가 AES-256-GCM 으로 암호화해 MySQL `secrets` 테이블에 저장한다. 화면과 API 응답에는 마지막 4자리만 나온다. 마스터 키는 DB 밖에 두며 환경 변수 `ILSIM_SECRET_KEY`, 없으면 `~/.ilsim/server-secret.key`(최초 실행 때 생성, 분실하면 저장된 키를 복호화할 수 없음)다. 등록된 키가 없으면 환경 변수 `TYPESAFE_API_KEY`(또는 `JEV_API_KEY`)를 쓴다. MySQL 이 없어도 서버는 기동하며 키 저장만 503 으로 응답한다. 키가 없거나 호출이 실패하면 규칙·정적 동작으로 되돌아간다. JEV 점수만으로 승인이나 통과를 정하지 않는다.

- **모델 라우팅(서버):** 단계마다 요구사항의 난이도(0~3)와 변경 위험도(0~2)를 JEV 로 평가하고, 정책이 모델을 정한다. 최소 모델(`ILSIM_MIN_MODEL`, 기본 `sonnet`) 아래로 내리지 않고, 재시도마다 한 단계씩 올리며, 마지막 시도는 항상 `opus`다. 같은 단계의 재시도에서는 평가를 다시 하지 않는다. 시도마다의 선택 이유는 단계의 `routingLog`, 평가값은 `routing`에 남는다.
- **문서 선택(Runner):** 아키텍처·컨벤션 검토에 쓸 프로젝트 문서를 이름·경로·제목으로 후보를 줄인 뒤 JEV 가 관련도를 판단해 고른다.
- **위험 후보 1차 판정(Runner):** 승인된 규칙의 REVIEW 후보를 JEV 가 먼저 판정해 확률이 0.2 이하면 오탐, 0.8 이상이면 실제 위험으로 정하고, 사이의 애매한 후보만 Claude 가 본다. 한 번 실행에 JEV 호출은 20회까지다.
