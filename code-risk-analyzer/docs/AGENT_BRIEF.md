# Agent 요약

에이전트가 작업을 시작할 때 먼저 읽는 한 장 요약이다. 긴 문서는 아래 "더 읽을 곳"에서 필요한 절만 연다. `DEEP_RESEARCH.md`와 `docs/*.pdf`는 사용자가 요청할 때만 읽는다.

## 무엇인가

Java 25 Spring Boot CLI(`code-risk-analyzer`)와 Python 단계 실행기(`scripts/`)다. Java/Spring/JPA 대상 프로젝트의 위험 분석, 테스트 적정성, 변이 검증과 CODE_CHANGE 7단계 실행을 제공한다. `build-convention` 적용은 필요하지 않다.

## 가장 쉬운 사용: 자연어 한 줄 (`ask`)

한 번 `./install.sh`(저장소 루트)를 실행하면 도구 확인, 분석기 jar 빌드, build-convention을 `~/.m2`에 설치, `~/.local/bin/ask` 등록을 한다. 대상 프로젝트에는 아무것도 추가하지 않는다. start.spring.io에서 받은 프로젝트(Java 21 이상) 폴더에서 바로 쓴다.

```bash
ask --spec spec.json "회원 가입 기능 추가해줘"  # 사람이 쓴 명세로 요구사항·plan·scope를 만들고 계획을 보여준 뒤 멈춘다
ask --yes                       # 확인한 계획을 실행한다 (baseline → 테스트 생성 → 구현 → 검증 → 컨벤션)
ask "검증 다시 해줘"            # 현재 세션 재검증
ask "결제 코드 위험 분석해줘"    # 분석 + 컨벤션 검사, 코드 변경 없음
```

- 설정 없는 실행: `gradlew`가 있는 가장 가까운 상위 폴더를 프로젝트로 본다. 변경 요청마다 `<project>/build/ask/<시각>/`에 새 세션을 만들고 `build/ask/current`가 현재 세션을 가리킨다. 이후 요청(이어서·검증·PR·상태·비용)은 현재 세션을 쓴다. `--config`·`--state`를 주면 아래 기존 방식으로 동작한다.
- 명세: `ask`는 설계와 프롬프트를 쓰지 않는다(2026-10-04 사용자 결정). 코드 변경은 `--spec` 명세 파일이 필요하고, 없으면 아무것도 만들지 않고 거부한다. `scripts/ask_draft.py`가 모델 호출 없이 형식을 검사해 requirements·plan·scope·request.md를 만들고 분석기 `requirements` 검증을 통과해야 저장한다. 회귀 기준은 기존 테스트 전체다(리포트 이름 기준: `@DisplayName` 문구 또는 메서드 이름; 테스트가 하나도 없으면 거부). 형식·작성 요령: [SPEC_FILE.md](guides/SPEC_FILE.md). 모델 판단(JEV 작업 판단·Tier 판단)과 테스트·구현 생성은 그대로다.
- 모델·정책 기본값: [`config/ask/`](../config/ask/). `~/.config/code-risk/models.json`·`routing-policy.json`(또는 `CODE_RISK_CONFIG_HOME`)이 있으면 그 파일만 대신 쓴다.
- 컨벤션(기본 켬, 문장에 "컨벤션 없이"·"코드 스타일 검사 빼고"가 있으면 끔): 켜면 명세를 받은 직후 `scripts/convention_check.py`가 Gradle init script로 build-convention을 붙여 `check --continue`를 실행하고(build.gradle 수정 없음, 이미 적용된 프로젝트는 그대로) 기존 위반 파일을 보여준다. `ask --yes`는 baseline 전에 `scripts/convention_fix.py`로 그 파일만 고치고 기존 테스트가 통과해야 유지한다(실패하면 원복하고 수정안과 실패 내용을 `cleanup-rejected-<n>.json`에 남긴 뒤 실패 내용을 넣어 한 번 더 시도, 원본은 세션의 `cleanup-original/`). 구현 후 검증 전에 모든 검사를 돌려, 실패하면 범위 안 기능 코드(`src/main`) 위반만 같은 모델로 고쳐 워크플로우 `implementation` 단계로 쓰고 다시 검사한다(최대 2번). 수락된 생성 테스트와 기존 테스트는 고치지 않고 남은 위반으로 보여준다. 전부 통과해야 verification으로 넘어간다(Git이 없으면 변경 코드 커버리지만 참고). 테스트·컴파일이 실패해도 그와 무관한 기능 코드 위반은 먼저 고친 뒤 verification이 원인을 보고하게 두고(JUnit 결과가 없으면 ask가 javac 오류 줄을 보여 줌), 통과하면 한 번 더 검사한다. 검사가 리포트 전에 멈추면 실패로 보고, build-convention을 찾지 못할 때만 건너뛴다. 끄면 기존 스타일을 따르고 검사하지 않는다. 생성 단계는 build-convention의 `docs/AGENTS_GUIDANCE.md`, `docs/templates` 파일 트리와 종류별 틀 코드를 세션의 `conventions.md` 문서로 받는다(`scripts/convention_templates.py`). `ask --yes`는 기존 위반 정리 전에 프로젝트에 없는 `global` 공통 클래스와 그 테스트를 틀에서 `@SpringBootApplication` 패키지로 복사하고(의존성이 없거나 같은 이름 클래스가 있으면 건너뜀), `application.properties`에 `spring.aop.proxy-target-class=false` 등을 더하며, 회귀 계획의 기존 테스트 목록을 갱신한다.
- 검증 on/off: 대상 프로젝트 `.code-risk.yml`의 `validators.architecture.enabled`·`validators.convention.enabled`로 build-convention의 아키텍처(`architecture`·`archUnit`, `ARCH-*`)와 나머지 검사를 따로 끈다. 꺼진 검사도 실행은 되지만 리포트에 사유와 함께 `skipped`로 남고 실패·수정 대상이 아니다. 둘 다 끄면 Gradle을 돌리지 않는다. `architecture` 미설정은 `convention`을 따르고, 요청 문장의 켬·끔이 설정보다 우선한다. 명시적으로 `true`인데 build-convention을 찾지 못하면 건너뛰지 않고 실패한다. 세션 시작 뒤 설정이 바뀌면 `--yes`·이어서·검증을 거부한다(새 요청 필요).
- 작업 판단: `TYPESAFE_API_KEY`가 있으면 JEV가 요청 문장만 보고 작업을 고르고, 키워드 규칙과 결과가 같아야 진행한다. 둘이 다르거나 JEV 신뢰도가 0.8 미만이거나 UNKNOWN이면 실행하지 않고 다시 묻는다. JEV 호출이 실패하면 읽기 전용 작업(분석·상태·비용·규칙·변이)만 키워드로 진행하고, 코드 변경·검증·PR·이어서는 거부한다(재시도하거나 `--no-jev`). 키가 없거나 `--no-jev`면 키워드만 쓴다(토큰 0). 코드를 바꾸는 단계는 `--yes`를 붙여야 실행한다.
- 작업 종류: 변경 / 분석 / 상태 / 이어서 / 검증 / PR / 비용 / 규칙 / 변이.
- JEV 위험 검토: 분석 요청은 키가 있으면 `scan --jev true`로 실행한다. 정적 규칙이 확정하지 못한 위험(IDEM·CON·TX·SEC-001~004·SEC-006/007·REL)을 finding이 가리키는 메서드 소스와 함께 물어 확률을 근거로 붙인다(정적 상태 불변, SEC-005가 있는 파일의 소스는 보내지 않음, 2026-10-04 사용자 결정으로 코드 전송 허용).
- 자동 업데이트: 새 요청 전에 `scripts/self_update.py`가 이 checkout을 최대 10분에 한 번 fetch해 fast-forward pull하고(커밋 안 한 수정·갈라진 이력이면 건너뜀), 소스가 바뀐 쪽만 `bootJar`·`publishToMavenLocal`로 다시 빌드한 뒤 같은 명령을 새 코드로 다시 실행한다. `--yes`·이어서·검증·PR 요청과 `--no-update`·`ASK_NO_UPDATE=1`이면 하지 않는다. 다른 PC 설치는 [WORK_PC_INSTALL.md](guides/WORK_PC_INSTALL.md).
- 단계: 세션 `phase`에서 다음 사람 확인 지점(verification → review → pull-request)까지만 실행한다. 검증 실패 시 `repair` 설정이 있으면 repair를 한 번 실행한다.
- 모델: `routing`에 Tier·capability가 없으면 새 세션 설정을 `<session>/ask-config.json`에 저장하고 이후 재사용한다. JEV 키가 있으면 `capability: {}`로 단계마다 JEV가 요구사항·코드 범위를 보고 Tier를 정한다(해당 내용이 TypeSafe로 전송된다). 키가 없으면 고위험 단어(결제·재고·동시성·트랜잭션·보안 등)가 있을 때 TIER_3, 아니면 TIER_2이며 TIER_4는 고르지 않는다.
- 모델 선택 근거: `config/ask/model-benchmarks.json`에 Artificial Analysis의 모델·effort별 Intelligence Index와 과제당 비용, Tier별 최소 지수를 둔다. `python3 scripts/model_select.py --write`가 Tier마다 최소 지수를 넘는 가장 싼 조합 → 더 강한 조합 → 다른 CLI 순으로 `models.json`·`routing-policy.json`을 만든다(금지 모델·max 제외, 손으로 고친 파일은 테스트가 거부). 바꾸려면 수치를 고쳐 다시 만들고 push하면 자동 업데이트로 모든 PC에 퍼진다(PC별 덮어쓰기 `~/.config/code-risk/models.json`). 교체 전 비교: `python3 scripts/golden_benchmark.py --routes general/medium,review/high`(숨은 검사가 있는 Java 버그 수정 과제 4개, models.json은 바꾸지 않음). `ask --check-models`가 routing이 쓰는 모델마다 가장 낮은 effort로 짧은 구조화 요청을 한 번 보내(토큰 소량) 응답 여부와 Tier별 영향(fallback으로만 동작·쓸 모델 없음)을 보여 준다. 종료 코드 0 전부 정상, 1 실패 있음, 2 설정 거부(금지 모델 포함 시 호출 전 거부). 사용 중인 파일이 벤치마크에서 나온 경로와 다르면 알린다. Shadow·Canary와 자동 승격은 없다.
- 금지: 도달 가능한 모든 경로(fallback 포함)에 이름에 `astra`·`fable`·`opus`가 든 모델이나 `max` effort가 있으면 실행 전에 거부한다. `--allowed-models a,b`로 허용 목록을 더 좁힐 수 있다.
- 직접 설정(`--config`): [`docs/examples/ask/config.json`](examples/ask/config.json)처럼 쓴다. config 안의 경로는 config 파일 위치 기준 상대경로(또는 `~`)로 적을 수 있어 여러 PC에서 같은 파일을 쓴다. `analyzer`를 빼면 이 checkout의 `build/libs` jar를 쓴다. plan의 `project`만 PC별 절대경로다.
- 인증: codex·claude는 각 CLI에 한 번 로그인한다(API 키를 넣으면 종량제 과금이 될 수 있다). JEV 키는 환경변수가 없으면 `~/.secrets`(파일의 `TYPESAFE_API_KEY=` 줄, 또는 디렉터리 안 같은 이름의 파일, 경로는 `CODE_RISK_SECRETS`로 변경)에서 읽어 하위 단계에도 전달한다. 키를 저장소에 커밋하지 않는다.
- 판단 품질: `python3 scripts/eval_ask.py scripts/eval/ask_intents.json --repeat 2 --output r.json`(키 필요, 문장만 전송). 2026-10-03 jev-1.13.0 기준 조정 세트 116/116, 조정에 쓰지 않은 holdout 40/44(나머지 4건은 키워드 불일치로 안전하게 거부), 다른 작업 실행 0건. 판단 기준·키워드를 바꾸면 두 세트를 다시 돌리고 holdout 문장으로 조정하지 않는다.
- `--config` 방식에서는 config의 requirements JSON이 기준이고 자연어 요청은 작업 판단과 Tier에만 쓴다. 설정 없는 방식에서는 승인한 명세 파일이 그 JSON이 된다.

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
| `CRA validators <project>` | `.code-risk.yml`의 `validators` 설정을 검증해 JSON으로 출력(null: 미설정) |
| `CRA impact <project> [--files-file f] [--depth 3] [--compare before.json] [--output f]` | 변경 파일을 간접 호출하는 메서드·테스트, 계약 선언 스냅샷과 이전 스냅샷 대비 변경(API 삭제·시그니처·annotation·엔티티 제약) |

규칙: `SEC-001~007`(SQL·Command·Path·SSRF·비밀값·객체 권한·Tenant), `CON-001~004`, `IDEM-001~004`, `TX-001~005`, `REL-001`.

## CODE_CHANGE 단계 (`scripts/code_change_workflow.py <stage> --config c.json --state <project>/build/<id>/state.json`)

`baseline` → `test-writing`(또는 `generate-tests`) → `implementation`(또는 `generate-implementation`) → `verification`(또는 `repair`) → `local-service` → `review` → `complete` → `pull-request`

- 각 단계는 이전 단계 성공과 입력·소스 해시가 그대로일 때만 실행된다. 바뀌면 STALE이 되고 다시 실행해야 한다. 단, 테스트·구현 생성 모델이 답을 내지 못하거나 답이 거부돼 아무것도 쓰지 않고 STALE이 된 경우는 `ask --yes`가 그 생성 단계부터 같은 세션에서 다시 한다(2026-10-03 사용자 결정). 입력·소스가 바뀐 STALE은 여전히 새 세션이 필요하다.
- 최종 검증: 회귀 비교(+ 계약 변경·영향 범위 경고) → HIGH/CRITICAL 정적 위험 → 품질 게이트(LINE 90%·BRANCH 80%·변이 90%·생존 0, `qualityGates` 설정 시. `ask`는 명세의 변경 production 클래스에 기본 적용, 요청에 "품질 게이트 없이"·"커버리지 빼고"가 있으면 끔) → 별도 서비스 4개 시나리오(normal·duplicate·concurrency·rollback, 각 명령이 `SCENARIO_REPORT`에 통과한 assertion 수를 남겨야 통과) → 근거 검토.
- 계약·영향: baseline이 `contracts-before.json`(public API·트랜잭션/보안/비동기 annotation·엔티티 제약 선언)을 남기고, verification이 `impact.json`에 비교 결과와 scope 파일을 간접 호출하는 메서드·테스트(3단계)를 남긴다. 결과의 `contractChanges`·`impactedTests`는 경고이며 차단하지 않는다.
- 서비스: MySQL fixture는 `fixtureAdapter`, 그 외 프로젝트는 `serviceAdapter`(네 시나리오는 서로 다른 프로젝트 안 검사 스크립트). adapter 없이 `--service`로 넘긴 결과는 NEEDS_REVIEW로 남아 complete·PR에 가지 못한다.
- 테스트 먼저: test-writing 뒤 구현 전 빌드로 `newFeatures`·`expectedFailures` 테스트가 아직 통과하지 않는지 `red.json`에 기록한다. 이미 통과하면 implementation이 막힌다(기존 동작이면 `features`로 옮긴다). implementation은 `src/main/`만 바꾸고, test-writing에서 잠근 테스트·빌드 파일이 바뀌면 verification이 거부한다.
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
