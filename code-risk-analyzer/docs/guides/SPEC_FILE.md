# 명세 파일 (`ask --spec`)

`ask`는 설계와 프롬프트를 쓰지 않는다. 코드 변경은 사람이(또는 함께 쓰는 Claude·Codex 대화에서) 쓴 명세 파일을 받아 시작한다. 모델 판단은 그대로 남는다. JEV(키가 있을 때)가 요청 문장으로 작업 종류를 고르고 단계마다 Tier를 정한다. 테스트·구현 생성 단계는 이 명세로 만든 요구사항을 받아 모델을 호출한다.

```bash
ask --spec spec.json "회원 가입 기능 추가해줘"   # 명세 검증 → 입력 파일 생성 → 계획을 보여 주고 멈춤
ask --yes                                            # 실행
```

[`scripts/ask_draft.py`](../../scripts/ask_draft.py)가 형식을 검사하고 requirements·plan·scope·request.md를 결정적으로 만든다. 분석기 `requirements` 검증까지 통과해야 세션이 생긴다. 틀리면 어느 필드가 왜 틀렸는지(`spec.requirements[0].domain: 필드가 맞지 않습니다 (없음: owner)`) 알려 준다. 회귀 기준은 프로젝트의 기존 테스트 전체이며, 테스트가 하나도 없으면 거부한다.

예시: [`docs/examples/ask/spec.json`](../examples/ask/spec.json)

## 형식

모든 필드가 필요하다. 빈 문자열, 중복 항목, 모르는 필드는 거부한다. `status`·`unknowns`는 생략할 수 있고, 적는다면 `COMPLETED`와 `[]`여야 한다.

| 필드 | 내용 |
|---|---|
| `title` | 변경 이름 |
| `assumptions` | 기본값으로 정한 것들(없으면 `[]`) |
| `database` | `MYSQL`, `OTHER`, 설정된 DB가 없으면 `UNDECIDED` |
| `requirements[]` | 독립적으로 시험할 수 있는 동작마다 하나: `id`(`REQ-1`), `title` |
| `.domain` | `owner`, `aggregates`(1개 이상), `dataOwnership`(1개 이상), `allowedDependencies`, `entryPoints`(1개 이상) |
| `.preconditions` `.postconditions` `.invariants` `.coreLogic` | 각각 1개 이상의 구체적인 문장 |
| `.transaction` `.concurrency` `.idempotency` `.consistency` | `applicability`가 `APPLICABLE`이면 `expectations` 1개 이상, `NOT_APPLICABLE`이면 `reason`만 쓰고 `expectations: []` |
| `.scenarios[]` | `id`(`SCN-1`), `type`(`NORMAL` `DUPLICATE` `CONCURRENCY` `ROLLBACK` `RETRY` `PARTIAL_FAILURE` `VALIDATION` `AUTHORIZATION`), `given`, `when`, `expectedOutcomes`(1개 이상), `testTypes`(`DOMAIN_UNIT` `REPOSITORY_DB` `API_SLICE` `INTEGRATION` `SERVICE_E2E`) |
| `.implementationStatus` / `.existingCode[]` | 새 동작은 `NOT_IMPLEMENTED`와 `[]`, 아니면 `PARTIAL`·`IMPLEMENTED`와 `{path, symbol}` 목록 |
| `files` | 만들거나 고칠 `src/main/`·`src/test/` 경로 전부(각 1개 이상). 테스트·구현 단계는 이 밖의 파일을 만들 수 없다 |

## 작성 요령

테스트는 이 명세만 보고 작성되므로 추측할 것을 남기지 않는다.

- HTTP API는 요구사항과 시나리오마다 method·path, 성공 상태 코드, 실패마다의 상태 코드(예: 400 검증, 404 없음, 409 중복), 오류 응답 형식을 적는다. 프로젝트의 기존 오류 처리를 따르고, 없으면 Spring ProblemDetail과 `code` 필드를 쓴다.
- 영속성은 저장소 연산과 테스트 준비 방식(예: 설정된 DB의 `@DataJpaTest`)을 적는다.
- `files`에는 production 타입마다 파일 하나를 적는다(포트, 값 객체, 예외, 이벤트, 그 adapter 포함). 로직이 있는 클래스(aggregate, 값 객체, command, service, controller, 예외 처리기, adapter)마다 테스트 하나를 두고, 예외·포트·use case·result·request/response record는 테스트 파일을 따로 두지 않는다.
- build 파일은 바꿀 수 없으므로 JDK와 이미 선언된 의존성만 쓴다. 보통 라이브러리가 필요한 기능(예: 비밀번호 해시)은 JDK만으로 하는 방법을 정하고 `assumptions`에 적는다.
- 컨벤션을 켠 경우 패키지 배치는 build-convention의 `docs/templates` 틀을 따른다.
