# 로컬 Agent 생성 연결

`scripts/agent_runner.py`는 설치된 Codex·Claude CLI를 명시적으로 선택해 호출한다. 외부 ai-router 디렉터리나 추가 Python dependency는 사용하지 않는다. 인증은 각 CLI의 로컬 인증 또는 실행 환경에서 제공하며 credential을 설정 JSON에 넣지 않는다.

기존 [단계 실행기](code-change-workflow.md)의 config에 `agent`를 추가한다. 설정이 없는 기존 수동 작성 경로는 그대로 사용할 수 있다.

```json
"agent": {
  "provider": "codex",
  "timeoutSeconds": 300
}
```

provider는 `codex` 또는 `claude`다. 모델을 생략하면 해당 CLI의 기본 모델을 사용한다. 특정 모델을 지정하려면 `model`과 그 모델을 포함하는 `allowedModels`를 함께 지정한다. `reasoningEffort`는 명시적 모델과 함께 `low`, `medium`, `high`, `xhigh`, `max` 중 지원하는 값으로 지정할 수 있다. 생략하면 기존 CLI 기본 동작을 유지한다. 모델 이름은 현재 계정에서 사용 가능한 값을 명시하며, 작업 중 config를 바꾸면 기존 세션의 입력 근거가 무효화된다. timeout은 1~3600초의 정수다. 임의 명령·CLI 인자·credential 설정은 받지 않는다.

단계별 Tier로 모델을 선택하려면 `agent` 대신 [Model Registry·Routing Policy](model-routing.md)의 `routing` 설정을 사용한다. 선택한 모델·reasoning과 정책 해시를 기존 생성 artifact에 기록한다.

## 실행

기존 CODE_CHANGE 요구사항 JSON, plan, scope, documents와 성공한 기준선이 필요하다. 미해결 질문, 충돌·근거 부족 검토, UNKNOWN 관점은 생성 전에 거부한다. 테스트 작성에는 `WRITE_TESTS`, 구현에는 `IMPLEMENT`와 해당 단계의 변경 파일을 scope에 명시한다.

```sh
python3 scripts/code_change_workflow.py baseline --config /absolute/config.json --state /absolute/project/build/change-001/state.json
python3 scripts/code_change_workflow.py generate-tests --config /absolute/config.json --state /absolute/project/build/change-001/state.json
# 생성된 테스트의 구현 전 실행 결과와 예상 실패 원인을 별도로 확인한다.
python3 scripts/code_change_workflow.py generate-implementation --config /absolute/config.json --state /absolute/project/build/change-001/state.json
python3 scripts/code_change_workflow.py verification --config /absolute/config.json --state /absolute/project/build/change-001/state.json
```

이후에는 기존 `local-service`/`review`/`complete` 절차를 따른다. Agent 응답이나 생성 단계 성공으로 완료를 판정하지 않는다. 기존 테스트·빌드 입력을 바꿨다면 기존 해시 기반 검토 정책도 계속 적용된다.

테스트를 먼저 쓰므로, 아직 없는 production 타입의 공개 모양(패키지, 생성자·factory, 메서드 시그니처, 예외 타입·메시지)은 테스트 작성 모델이 저장소 규칙에 맞춰 정한다. 구현은 테스트가 쓴 모양을 그대로 따른다. 테스트 작성 모델은 입력 규칙, 상태 코드, 결과처럼 요구사항이 정하지 않은 비즈니스 동작이 있을 때만 NEEDS_REVIEW를 반환한다. 단, 테스트는 승인된 파일이나 이미 있는 파일의 프로젝트 타입과 JDK·빌드에 선언된 라이브러리만 쓸 수 있다. 어기면(`@SpringBootApplication` 패키지 아래 없는 타입, 선언되지 않은 Spring Security·Testcontainers 등) 출력 계약 위반으로 거부하고 그 목록을 다음 시도에 알려 준다. 구현은 범위 밖 파일이나 빌드 파일을 만들 수 없어 그런 테스트를 만족시킬 수 없기 때문이다.

테스트와 구현은 매번 새 임시 작업 디렉터리와 독립적인 비영속 세션을 사용한다. 승인된 소스 파일, 선언된 문서, 관련 AGENTS.md와 빌드·분석 설정만 컨텍스트에 포함한다. 원본 경로나 provider 인증 파일을 복사하지 않는다. 테스트 생성에는 이후 구현 결과를 전달하지 않으며, 구현 단계가 시작된 세션에서 테스트 재생성을 거부한다.

Codex는 read-only sandbox와 사용자 config를 제외한 실행을 사용한다. Claude는 safe mode, 도구 비활성화, 빈 MCP 설정을 사용한다. 생성기는 전체 파일 내용의 JSON을 반환하고, 실제 적용은 기존 작성 실행기가 범위·현재 소스 해시를 재확인한 뒤 수행한다. 임시 디렉터리는 파일과 세션의 분리이며, 모든 OS 파일 읽기를 차단하는 별도 컨테이너 sandbox는 아니다.

## 반환 계약과 근거

두 provider의 반환 형식은 다음 필드로 통일한다. CLI의 성공 종료와 정상 완료 이벤트/응답을 확인한 후 로컬에서 필드·타입·중복 키·경로를 다시 검증한다.

| 필드 | 의미 |
|---|---|
| `status` | `COMPLETED` 또는 `NEEDS_REVIEW` |
| `changes` | `{path, content}` 목록. 삭제·임의 명령은 지원하지 않음 |
| `requirementTestMap` | `{requirementId, scenarioId, tests}` 목록. tests는 `package.Class#JUnit 보고 이름` 형식 |
| `expectedFailures` | `{test, kind, reason}` 목록. kind는 `ASSERTION` 또는 `COMPILATION` |
| `unknowns` | 미해결 질문. 값이 있으면 완료·변경 적용 불가 |

테스트 생성은 모든 요구사항 시나리오에 테스트를 연결해야 한다. 구현 생성은 테스트 계약을 변경할 수 없고 `src/main/`만 수정한다. 구현이 이미 계약을 만족하면 변경 없는 결과도 허용한다. verification은 연결된 테스트가 실제 최종 JUnit 결과에서 모두 PASS인지 확인한다. 연결된 테스트의 assertion이 요구사항을 충분히 검증하는지는 기존 근거 검토의 책임이다.

`@DisplayName`이 있으면 생성 계약의 테스트 ID에는 해당 문자열을 그대로 사용한다. 예를 들어 `example.BoundaryTest#하한 경계인 0을 허용한다`다. 표시 이름이 없으면 기존 `Class#method` 형식을 사용한다. 기존 plan·근거 검토의 메서드 ID 형식은 변경하지 않는다. 동적·parameterized 테스트의 보고 이름을 확정할 수 없다면 근거 부족으로 남기며 메서드나 표시 이름을 추측해 통과시키지 않는다.

`agent-test-writing.json`과 `agent-implementation.json`에는 provider, 명시한 모델·reasoning, attempt ID, 입력·소스 해시, prompt 해시와 구조화 결과를 기록한다. effort를 생략한 경우 기록된 null은 명시적 override가 없다는 뜻이다. config·관련 규칙·생성기 코드나 원본 소스가 바뀌면 결과를 적용하지 않는다. 생성 결과 파일의 해시와 수락한 테스트 내용도 verification 및 완료 단계까지 보호한다. CLI의 원시 stdout/stderr는 artifact·상태·오류 응답에 복사하지 않는다.

timeout·취소 시 생성기 프로세스 그룹을 종료한다. 실패 응답, CLI 부재, 계정 잔액 부족은 변경을 적용하지 않고 검토 대기로 남긴다. [모델 라우팅](model-routing.md)에 `fallbacks`와 `maxAttempts`를 지정하면 정상 완료 후 발생한 응답 계약 오류만 최대 세 번의 총 호출 안에서 재시도한다. 직접 `agent` 선택은 한 번만 실행한다. 실패한 단계의 명시적 재호출은 가능하며 무제한 자동 재시도는 하지 않는다. JEV API key는 CLI 자식 프로세스 환경에서 제외한다.

## 구현 범위

이번 연결은 provider adapter와 기존 작성·검증 경로의 통합이다. 구현 전 예상 실패의 자동 판정, 실패별 자동 수정 반복, 비용 스케줄러, 선택형 아키텍처·컨벤션 검증, JaCoCo/PIT 통합 최종 게이트는 후속 작업이다. 최종 서비스 근거가 없는 작업을 완료로 승격하지 않는다.

실행 옵션 기준: [Codex 비대화 실행](https://learn.chatgpt.com/docs/non-interactive-mode), [Claude CLI reference](https://code.claude.com/docs/en/cli-reference).
