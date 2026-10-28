# Runner Protocol (v1)

일심 MCP 서버(`ilsim-runner mcp`)가 서버로 outbound HTTP만 사용한다. 모든 요청은 `X-Ilsim-Token` 헤더(`~/.ilsim/token` 또는 `ILSIM_TOKEN`)가 필요하다.
프로젝트 경로와 테스트·분석 명령은 Runner 로컬 설정(`~/.ilsim/runner.json`)이 소유하며 서버로 보내지 않는다. 설계는 [MCP 설계](../docs/mcp-design.md)를 따른다.

| 요청 | 설명 |
|---|---|
| `POST /runner/api/projects` `{runnerId, projects:[{id, name}]}` | 등록 프로젝트 보고 |
| `POST /runner/api/tasks` `{projectId, feature?, requirement}` | `start_task`가 작업을 만든다 |
| `GET /runner/api/tasks/{id}` | 작업 상태(단계·단계 상태·시도·피드백·브랜치) |
| `POST /runner/api/tasks/{id}/claim` `{runnerId}` | 현재 단계를 맡는다. 맡을 단계가 없으면 `204` |
| `POST /runner/api/tasks/{id}/events/batch` `[{stage, type, data, eventId?}]` | 진행 이벤트 |
| `POST /runner/api/tasks/{id}/result` `StageResult` | 단계 결과 보고(`submit_result`) |
| `POST /runner/api/tasks/{id}/approve`·`reject` `{feedback}`·`retry` `{instruction?}` | Claude 앱의 승인·반려·재시도 |
| `POST /runner/api/tasks/{id}/reopen` `{stage, feedback}` | 통과한 코드 단계로 되돌린다(그 단계부터 시도 1, 이후 단계 초기화) |
| `GET`·`PUT`·`DELETE /runner/api/secrets/jev`, `GET .../status` | JEV 키 조회·저장·삭제(`ilsim-runner jev-key`) |

```
StageResult { passed, retryable, diff?, error?, artifacts{path: 내용}, validation{검증명: 상태}, branch?, decisions[] }
```

- `retryable=true`로 실패하면 서버가 `error`를 피드백으로 같은 단계를 다시 맡긴다. 최대 3회, 초과하면 작업이 BLOCKED 된다.
- `decisions`는 단계 처리 판단 기록(용도·처리자·이유·근거)이다: `REQUIREMENT_CHECK`, `GUIDELINE_SELECT`, `GUIDELINE_CHECK`, `CHANGE_AUDIT`.

## 단계

`REQUIREMENTS`(승인) → `TEST_CASES`(승인) → `TEST_CODE` → `IMPLEMENTATION`

- 단계 상태: `PENDING → RUNNING → WAITING_APPROVAL | PASSED | FAILED`
- 작업 상태: `ACTIVE | COMPLETED | BLOCKED`

## Runner가 단계 안에서 강제하는 검증

Claude의 판단이 아니라 일심 MCP 서버가 `submit_result` 때 작업 브랜치에서 직접 실행한다.

1. 허용 경로: 단계별로 변경 가능한 파일 범위를 diff로 검사한다. 벗어나면 `git reset --hard`로 되돌리고 재시도한다.
2. `TEST_CASES`: 요구사항 ID와 테스트 케이스의 추적성(`traceability`)을 검사한다.
3. `TEST_CODE`, `IMPLEMENTATION`: 정적 분석 명령(`analysis`) → 아키텍처 규칙(`architecture`) → 코드 컨벤션(`convention`) → 프로젝트 문서 규칙(`doc-rules`) → 트랜잭션·동시성 위험(`risk`) → 테스트 무력화 검사(`test-integrity`, `TEST_CODE`만) → 테스트(`tests`) → 변이 테스트(`mutation`, `IMPLEMENTATION`만) 순으로 실행한다.
   - `TEST_CODE`에서 테스트가 모두 통과하면 실패다(구현이 없는데 통과). 실패해야 `RED`다.
   - `IMPLEMENTATION`에서는 테스트가 통과해야 한다.

`guidelines`는 작업 시작 때 고른 프로젝트 문서 지침의 JEV 준수 판정이다(모든 단계, `PASSED`·`VIOLATED`·`UNCERTAIN`·`UNKNOWN`·`NONE`, 어기면 실패). `requirements`는 구현 단계 요구사항(`REQ-n`) 충족 판정이다(누락이면 실패).

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

`convention`·`doc-rules`·`risk`는 이번 작업에서 바뀐 파일에만 적용한다(기존 코드의 예외는 막지 않는다). `doc-rules`는 그 프로젝트 문서에서 만든 규칙(`~/.ilsim/projects/<id>/doc-rules.json`) 중 승인된 것의 위반 가운데 커밋된 코드에 없던 것만 실패로 본다.
`convention`은 승인된 규칙의 `BLOCK` 위반만, `risk`는 프로젝트 설정으로 `BLOCK`인 규칙(기본: TX-003, CON-002, CON-003)의 위반만 실패로 본다.
`risk`의 `REVIEW` 후보는 로그에 권고와 함께 남기며, 사용자가 승인한 프로젝트 메모리의 오탐 학습에 해당하는 후보는 제외한다.

`test-integrity`는 바뀐 테스트 파일에서 비활성화(`@Disabled`, `@Ignore`, 주석 처리), 단언 없음, 항상 통과하는 단언, 삼킨 예외를 찾아 실패로 본다. 테스트 실행 후 바뀐 테스트 클래스에 건너뛴 케이스가 있어도 `tests`가 `FAILED`다.
`mutation`은 프로젝트에 변이 테스트 명령(`set <id> mutation <명령>`)이 있을 때만 실행하며 PIT의 `mutations.xml`에서 바뀐 소스의 점수를 계산한다. 구현 단계에서는 테스트를 고칠 수 없으므로 막지 않고, 점수가 기준(60%) 미만이면 `LOW`로 기록하고 살아남은 변이를 로그에 남긴다.

`ai-audit`는 작업한 Agent와 다른 Claude 호출이 변경을 독립적으로 본 결과다. 테스트 코드 단계는 항상, 구현 단계는 JEV 판정이 불확실하거나 위험 후보가 있거나 diff가 크거나 표본으로 뽑혔을 때만 한다(그 밖에는 `SKIPPED`). 지적 유형은 `OMISSION`(요구사항·테스트 케이스 누락), `UNRELATED`(무관한 변경), `TEST_BYPASS`(테스트 약화·우회), `RULE`(정적 검사가 놓친 규칙·문서 지침 위반), `WEAK_TEST`(변이 테스트에서 살아남은 중요한 동작)이다. 상태는 `PASSED`, `CONCERN`, `SKIPPED`, `UNKNOWN`(호출 실패)이다.

- `WEAK_TEST`를 뺀 지적은 `error`에 담아 `retryable=true`로 되돌려 Agent가 해당 부분만 고치게 한다. 구현 단계에서는 테스트를 고칠 수 없으므로 `WEAK_TEST`는 승인자에게만 보인다.
- 마지막 시도(3회차)에서는 지적이 있어도 작업을 막지 않고 승인자에게 넘긴다. AI 오판이 작업을 BLOCKED로 만들지 않게 하기 위함이다.

## JEV

TypeSafe JEV 는 텍스트를 만들지 않고 yes/no·선택·점수 판단의 확률만 돌려주는 결정 모델이다. 매우 저렴하므로 JEV로 할 수 있는 판단은 모두 JEV로 하고 Agent 호출을 줄인다. 키는 `ilsim-runner jev-key set`으로 등록하며 서버가 AES-256-GCM 으로 암호화해 저장한다(마스터 키는 `ILSIM_SECRET_KEY` 또는 `~/.ilsim/server-secret.key`). 일심 MCP 서버는 시작할 때 키를 받아 메모리에만 두고, 서버가 아직 안 떠 있어 못 받았으면 JEV가 필요할 때 30초 간격으로 다시 받는다. 키가 없거나 호출이 실패하면 정적 동작이나 독립 검토로 넘어간다. 판단은 `~/.ilsim/decisions`에 캐시한다.

- **문서 지침:** 프로젝트 문서를 문서·구간 단위로 골라 단계 지시에 넣고, 제출마다 준수를 판정한다([MCP 설계 6장](../docs/mcp-design.md)).
- **요구사항 충족:** 구현 단계 diff가 `REQ-n`마다 충족하는지 판정한다(0.2 이하 누락, 0.8 미만 불확실).
- **영향 범위:** 테스트 코드·구현 단계에서 바뀐 파일 중 요구사항 문서에 적히지 않은 파일이 요구사항을 구현·테스트하는 데 필요한 범위 안인지 판정한다(`scope`, 0.2 이하면 범위 밖으로 제출 실패, 0.8 미만은 불확실로 독립 검토). 사용자가 `record_decision`으로 허락한 파일은 문서에 적힌 것으로 본다.
- **미결정 사항:** 테스트 케이스·테스트 코드·구현 단계 제출이 요구사항 문서의 `## 미결정 사항` 중 사용자가 답하지 않은 질문의 답을 정했는지 판정한다(`undecided`, 0.6 이상이면 추측으로 제출 실패, 0.4 초과는 불확실로 독립 검토·승인 안내). 사용자가 승인 뒤 답하면 `record_decision`으로 기록해 판정에서 뺀다.
- **위험 후보 1차 판정:** 승인된 규칙의 REVIEW 후보를 JEV 가 먼저 판정해 0.2 이하면 오탐, 0.8 이상이면 실제 위험으로 정하고, 사이의 애매한 후보만 Claude 가 본다.
- **분석 문서 선택:** 아키텍처·컨벤션 분석에 쓸 프로젝트 문서를 JEV 관련도로 고른다.
