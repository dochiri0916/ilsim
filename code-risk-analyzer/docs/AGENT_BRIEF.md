# Agent 요약

에이전트가 작업을 시작할 때 먼저 읽는 한 장 요약이다. 긴 문서는 아래 "더 읽을 곳"에서 필요한 절만 연다. `DEEP_RESEARCH.md`와 `docs/*.pdf`는 사용자가 요청할 때만 읽는다.

## 무엇인가

Java 25 Spring Boot CLI(`code-risk-analyzer`)와 Python 단계 실행기(`scripts/`)다. Java/Spring/JPA 대상 프로젝트의 위험 분석, 테스트 적정성, 변이 검증과 CODE_CHANGE 7단계 실행을 제공한다. `build-convention` 적용은 필요하지 않다.

## 가장 쉬운 사용: 자연어 한 줄 (`scripts/ask.py`)

```bash
python3 scripts/ask.py "주문 취소에 중복 요청 방지 추가해줘" --config c.json --state <project>/build/<id>/state.json
```

- 작업 판단: `TYPESAFE_API_KEY`가 있으면 JEV가 요청 문장만 보고 작업을 고르고, 키워드 규칙과 결과가 같아야 진행한다. 둘이 다르거나 JEV 신뢰도가 0.8 미만이거나 UNKNOWN이면 실행하지 않고 다시 묻는다. JEV 호출이 실패하면 키워드로만 판단하고 그 사실을 출력한다. 키가 없거나 `--no-jev`면 키워드만 쓴다(토큰 0). 코드를 바꾸는 단계는 `--yes`를 붙여야 실행한다.
- 작업 종류: 변경 / 분석 / 상태 / 이어서 / 검증 / PR / 비용 / 규칙 / 변이.
- 단계: 세션 `phase`에서 다음 사람 확인 지점(verification → review → pull-request)까지만 실행한다. 검증 실패 시 `repair` 설정이 있으면 repair를 한 번 실행한다.
- 모델: `routing`에 Tier·capability가 없으면 새 세션 설정을 `<session>/ask-config.json`에 저장하고 이후 재사용한다. JEV 키가 있으면 `capability: {}`로 단계마다 JEV가 요구사항·코드 범위를 보고 Tier를 정한다(해당 내용이 TypeSafe로 전송된다). 키가 없으면 고위험 단어(결제·재고·동시성·트랜잭션·보안 등)가 있을 때 TIER_3, 아니면 TIER_2이며 TIER_4는 고르지 않는다.
- 금지: 도달 가능한 모든 경로(fallback 포함)에 이름에 `astra`·`fable`·`opus`가 든 모델이나 `max` effort가 있으면 실행 전에 거부한다. `--allowed-models a,b`로 허용 목록을 더 좁힐 수 있다.
- 요구사항 내용 자체는 여전히 config의 requirements JSON이 기준이다. 자연어 요청은 작업 판단과 Tier에만 쓴다.

## 분석기 명령

`CRA` = `java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar` (`./gradlew bootJar`로 생성)

| 명령 | 용도 |
|---|---|
| `CRA scan <project> [--files-file f] [--baseline b] [--jev true] [--fail-on]` | 위험 21개 규칙 + `TEST-001` 적정성. 종료 1 게이트 실패, 2 입력 오류, 3 분석 실패 |
| `CRA baseline create <project>` | 기존 finding 기준선 |
| `CRA route --change "<text>" --files a,b` | LIGHT/FULL 경로 판정 |
| `CRA prompt <requirements.json>` | 범위·완료 조건을 담은 압축 Agent 프롬프트 |
| `CRA requirements <input.json>` | 요구사항 산출물 검증 |
| `CRA mutation <project>` | PIT 변이 검증 |
| `CRA rules [ID]` | 규칙 목록·설명 |

규칙: `SEC-001~007`(SQL·Command·Path·SSRF·비밀값·객체 권한·Tenant), `CON-001~004`, `IDEM-001~004`, `TX-001~005`, `REL-001`.

## CODE_CHANGE 단계 (`scripts/code_change_workflow.py <stage> --config c.json --state <project>/build/<id>/state.json`)

`baseline` → `test-writing`(또는 `generate-tests`) → `implementation`(또는 `generate-implementation`) → `verification`(또는 `repair`) → `local-service` → `review` → `complete` → `pull-request`

- 각 단계는 이전 단계 성공과 입력·소스 해시가 그대로일 때만 실행된다. 바뀌면 STALE이 되고 다시 실행해야 한다.
- 최종 검증: 회귀 비교 → HIGH/CRITICAL 정적 위험 → 품질 게이트(LINE 90%·BRANCH 80%·변이 90%·생존 0, `qualityGates` 설정 시) → 별도 서비스 4개 시나리오(normal·duplicate·concurrency·rollback) → 근거 검토.
- 서비스: MySQL fixture는 `fixtureAdapter`, 그 외 프로젝트는 `serviceAdapter`.
- PR: `pull-request`는 본문 초안만 만든다. `submit: true`는 plan의 `PULL_REQUEST` 승인과 커밋·푸시가 끝난 경우에만 draft PR을 연다.
- 종료 코드: 0 통과, 1 실패, 2 거부, 3 검토 필요.

## 지켜야 할 것

- 테스트를 약화·삭제·skip하지 않는다. 테스트 작성 단계에서 production 코드를 바꾸지 않는다.
- JEV 결과는 참고 근거다. 확률로 검사를 건너뛰거나 예외를 등록하지 않는다.
- REVIEW·PARTIAL·UNKNOWN은 통과로 보고하지 않는다.
- 키(`TYPESAFE_API_KEY` 등)를 출력하거나 artifact에 남기지 않는다.

## 토큰 절약

- 모델 선택은 Tier 라우팅(`scripts/model_router.py`)에 맡기고, 낮은 Tier로 충분한 단계에 큰 모델을 지정하지 않는다.
- 사용량 확인: `python3 scripts/cost_report.py <session-dir>...` (모델별 COMPLETED 세션당 토큰·비용).
- 실패 로그 전체 대신 `regression.json`의 `failures`와 `classification`만 읽는다.

## 더 읽을 곳

| 필요한 것 | 문서 |
|---|---|
| 단계별 설정·복구 | [code-change-workflow.md](requirements-analysis/code-change-workflow.md) |
| 모델 라우팅·JEV·비용 | [model-routing.md](requirements-analysis/model-routing.md) |
| 요구사항 JSON 형식 | [requirements-analysis/README.md](requirements-analysis/README.md) |
| 실행 증거 입력 | [EXECUTION_EVIDENCE.md](guides/EXECUTION_EVIDENCE.md) |
| 구현 현황·남은 작업 | [WORKFLOW_IMPLEMENTATION_STATUS.md](status/WORKFLOW_IMPLEMENTATION_STATUS.md) 상단 "단계별 요약" |
