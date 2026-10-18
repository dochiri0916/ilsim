# 세션 인수인계

AI 작업 세션이 이어받을 상태를 적는다. 새 세션은 이 파일을 먼저 읽고, 작업을 마치면 갱신한다. `.claude/settings.json`의 훅이 시작할 때 이 파일을 보여 주고, 작업 트리에 이 파일보다 나중에 바뀐 파일이 있으면 종료를 막는다.
여러 세션이 동시에 쓰므로 바꿀 부분만 Edit로 고치고 파일 전체를 다시 쓰지 않는다. 다른 세션이 쓴 항목은 건드리지 않는다([AGENTS.md](../AGENTS.md)).

마지막 갱신: 2026-10-18 (진행 로그, 규칙 파일 잠금, 분석 게이트 E2E, 일심 자체 등록, 규칙 장단점 툴팁, 커밋·푸시)

## 작업 규칙 (사용자 지시)

- 항상 한국어. 보고는 [AGENTS.md](../AGENTS.md) 형식("완료" / "결정 필요")으로 짧게.
- 커밋·푸시는 사용자가 하라고 할 때만 한다. 이력 재작성 금지.
- 웹은 디자인이 중요해 백엔드를 먼저 한다. 서버는 로컬(+터널/모바일)에서 쓴다.
- 어느 PC에서 써도 같은 기능을 제공해야 한다. PC 전용 설정(`settings.local.json`, `~/.claude` 메모리)에 기능을 두지 않는다.
- 비밀번호·토큰·키는 이 파일에 적지 않는다.

## 지금까지

- e672453까지(푸시됨): 웹 로그인, 서버 Kotlin 헥사고날+JPA, 루트 Gradle 통합, 실패 경로 테스트, Claude 비용 집계(단계·작업·모델별, 웹 표시).
- 이번 세션(10-17~18): 작업 트리를 기능별로 나눠 커밋하고 origin/main에 푸시했다. 아래 "이번 세션" 참고.
- 92532ff(커밋, 푸시 안 함): 아래 1~13.
- 커밋 안 함: 아래 "92532ff 이후". 이번 세션이 건드리지 않은 미커밋 변경은 `ilsim-runner`(Main.kt 포함), `ilsim-web`, `ilsim-server`의 auth·usage·rules·secret·task 일부다. 누가 바꿨는지는 확인되지 않았다.

## 이번 세션 (2026-10-17~18, AI)

- 작업 트리를 기능별로 나눠 커밋하고 origin/main에 푸시했다. 포함: 레거시 ai 스크립트·원격 러너와 `install.sh` 제거(`b729ccf`), validators 설정·명령 제거, 분석기 참고서 정리, build-convention 보강과 ai 번들 제거, 루트 문서, 개발 환경(`.gitignore`, `docker-compose.yml`, `scripts/`), 실행기 프로토콜 계약, ilsim-runner·ilsim-server·ilsim-web 추가, 구현 계획서(`c90eb49`).
- `task/domain` 불변화와 VO: 식별자·값 VO(`TaskId`, `ProjectId`, `RunnerId`, `Requirement`, `Feature`, `Branch`, `RunCount`, `Attempt`)를 `task/domain/vo`에 두고 enum(`Stage`, `StageStatus`, `TaskStatus`)도 옮겼다. `Task`·`StageState`는 불변이다. 생성자는 private이고 전이 메서드는 새 인스턴스를 돌려주며, `TaskService.update`가 맵을 교체하고 저장한다. `ModelSelector.select`는 `RouteDecision`(등급 + 새 단계 상태)을 반환한다. `ValueObjectTest`를 추가했다. 이 변경은 92532ff에 들어 있다.
  - 정정: 항목 9의 "vo/ 리팩터링은 AI가 한 것이 아니라 이미 있던 변경"은 이 세션 기록과 맞지 않는다. `vo/`는 이 세션에서 만들었다(확인 필요).
- 도메인 패키지 분류(코드 변경 없음, 결정 대기): `model`(Task, StageState, Project), `vo`(값·enum·ID·Assessment·ProjectStatus·StageOutcome·TaskStats·ModelTier), `policy`(RoutingPolicy, ReasoningEffort, PromptTemplates, FailureSignature, ArtifactHash), `event`(TaskEvent), `exception`(유지). 분리 필요 파일: `Assessment.kt`, `Project.kt`, `ModelTier.kt`. 아직 이동하지 않았다.

## 92532ff에 들어간 변경 (검증 통과)

1. 검토 호출(AI 변경 검토·위험 검토) 비용 집계: `ClaudeCli.askWithCost`, `StageResult.reviewCostUsd/reviewModel`. 수동 명령은 `CostTally`로 콘솔 출력.
2. 계정: 웹 가입·계정추가 제거, 비밀번호 변경(`POST /api/auth/password`). 계정은 DB에 직접 INSERT(README).
3. 서버 config를 `*/adapter/out/config`로 이동, system은 `adapter/config`.
4. 인증 HTTP 테스트(`AuthHttpTest`), 토큰 테스트.
5. 규칙·메모리 승인 API: 컨텍스트 `rules`(`FileKnowledgeStore`가 `~/.ilsim/projects/<id>/*.json`을 직접 수정). `GET /api/projects/{id}/knowledge`, `POST .../knowledge/rules/{architecture|convention}/approve|reject`, `.../knowledge/memory/approve|reject`.
6. 토큰 자동 공유(`~/.ilsim/token`), 서버 기본 바인딩 127.0.0.1, `ILSIM_FORWARD_HEADERS`/`ILSIM_SECURE_COOKIE`, 계정별 로그인 잠금(20회/15분), Vite `xfwd`.
7. `GET /api/stats`(모델별·단계별 집계, 승격 단계 수).
8. `.gitignore`: `*.tsbuildinfo`, `.claude-login`.
9. 요구사항 단계 대화: 승인 전 `POST /api/tasks/{id}/messages {"text"}`로 말을 걸면 Runner가 같은 Claude 세션을 `--resume`으로 이어 `requirements.md`를 고친다(최대 10번, 승인·거절하면 세션 폐기, 이어 가기 실패 시 새 세션으로 대체). 다른 단계는 항상 새 세션. 실제 Claude E2E 2턴 검증.
   - `task/domain`의 vo/ 리팩터링(TaskId 등)은 AI가 한 것이 아니라 작업 트리에 이미 있던 변경이다.
10. 웹 디자인(밝은 미니멀): 상단 내비(작업·규칙·메모리·통계·설정), `Knowledge.tsx`(승인/거절), `Stats.tsx`, TaskDetail 요구사항 대화 채팅, styles.css 전면 교체. 진행 로그는 도구 호출 한 줄 요약(`summarizeTool`)·텍스트 말풍선.
11. 산출물 표시: `.md`는 `react-markdown`+`remark-gfm`으로 렌더링(`.markdown` 스타일). 산출물·diff·모델 선택 이력·진행 로그는 버튼 → 공용 `Dialog.tsx`(네이티브 `<dialog>`, 닫기/배경 클릭/Esc).
12. 웹 "프로젝트 분석" 버튼: 규칙·메모리 화면 → `POST /api/projects/{id}/knowledge/analysis`(진행 중 409, 30분 멈추면 재요청 가능) → Runner가 poll 전에 `/runner/api/analysis/claim`으로 가져가 `analyzeArchitecture`+`analyzeConvention`(Main.kt, CLI와 공용) 실행 → `/runner/api/analysis/{id}/result`. 규칙 추출과 검사는 정적, AI는 후보 승인 판단만 한다. 서버의 분석 상태는 인메모리(사용자 결정: 보고서는 보존 불필요, 프로젝트에 연결된 정적 분석 규칙 파일만 유지되면 된다). 승인된 규칙은 작업 단계에서 정적 게이트로 쓰인다(`TaskExecutor`).
13. 인수인계를 저장소로 이동: 이 파일, `.claude/settings.json`(SessionStart·Stop 훅), `.claude/hooks/handoff-check.sh`, `AGENTS.md` 작업 원칙 한 줄, `docs/README.md` 목차 한 줄. Stop 훅이 실제 세션에서 종료를 막는 것까지 확인했다.

## 92532ff 이후 (커밋 안 함, 검증 통과)

- 플랜 사용량(토큰 0): Runner 의 별도 스레드 `PlanUsageReporter`가 5분마다, 그리고 웹 "지금 확인"(`POST /api/usage/{runnerId}/refresh` → `/runner/api/usage/claim`) 때 `claude -p /usage --output-format json`을 실행해 문구를 파싱(`UsageCommand`, 모델 호출 없음·비용 0 확인). 작업 실행 중 stream-json 의 `rate_limit_event`도 관찰해 반영. 바뀌었거나 직접 조회했으면 `POST /runner/api/usage`. 서버 `usage` 컨텍스트, 인메모리(Runner=PC의 Claude 계정별). 웹 사용량 탭(`Usage.tsx`). 실제 E2E 통과. `/usage` 문구가 바뀌면 파서가 깨질 수 있다(재설정 시각을 못 읽으면 사용률만 쓴다). 키체인 OAuth 토큰으로 사용량 API를 부르는 방식은 자격 증명 접근이라 쓰지 않았다.
- 프로젝트 우선 내비게이션(사용자 요청: 프로젝트 선택 → 탭 이동): 상단바에 프로젝트 드롭다운(`ProjectPicker.tsx`, 연결 상태 점, 선택은 localStorage 기억)·설정·로그아웃, 그 아래 프로젝트 탭(작업·규칙·메모리·사용량·통계). 모든 탭이 선택 프로젝트 기준이고 사용량은 그 프로젝트의 Runner 값. 작업 상세에서 프로젝트를 바꾸면 작업 탭으로 간다. 대시보드 입력은 명령 textarea와 "확인" 버튼만(제목·기능 이름 제거, ⌘/Ctrl+Enter). 서버 `GET /api/stats?projectId=`.

- 자동 로그인: 로그인 화면 "자동 로그인 (30일)"(기본 체크) → `login_tokens` 테이블(series + secret SHA-256 해시, 30일), 쿠키 `ILSIM_REMEMBER`(HttpOnly·Lax·Secure는 `ILSIM_SECURE_COOKIE`). 세션이 없으면 `SessionFilter`가 쿠키로 세션 복원. 로그아웃은 그 토큰, 비밀번호 변경은 모든 토큰 폐기.
- 2단계 인증(Google Authenticator, 계정별 선택): 설정 화면에서 QR(`qrcode` npm) 등록. TOTP 비밀은 `secrets` 테이블에 `totp:<user>`로 암호화 저장(`secret/api/SecretWriter` 추가). 비밀번호로 새로 로그인할 때만 `POST /api/auth/otp` 코드 단계(5분, 실패 5회 잠금, 같은 코드 재사용 거절). 자동 로그인 기기는 코드 없이 들어온다. AI 계정 `claude`는 2단계 인증을 켜지 않는다(curl 로그인 유지). 사용자 결정: `claude` 계정의 접속처(127.0.0.1) 제한은 하지 않는다. 실제 E2E 통과.

- 진행 로그: 요구사항 대화 메시지를 서버가 `message` 이벤트로 남겨 로그에 내 말풍선으로 보인다. 연속 도구 호출은 "도구 호출 N건"으로 접힌다(`<details>`). 텍스트·결과·Runner 로그는 그대로 보인다.
- 규칙·메모리 파일 동시 쓰기: 서버(`FileKnowledgeStore`)와 Runner(`KnowledgeFiles`)가 `~/.ilsim/projects/<id>/.lock` OS 파일 잠금을 잡고 읽고-고치고-쓴다. Runner 쓰기도 임시 파일+원자적 이동. 프로젝트 분석의 AI 검토가 도는 동안 웹에서 내린 사용자 결정(decidedBy=user)은 검토 결과 저장 때 유지된다(`keepUserDecisions`). 실제 두 프로세스 동시 실행 E2E는 안 했다(단위 테스트만).

## 검증 상태

루트 `./gradlew check` 통과, 웹 빌드 통과, 실제 Claude+JEV E2E 4단계 통과. osiv 분석 E2E 통과(아키텍처 11·컨벤션 9 규칙), 위반 주입 시 `check`가 5건 검출.

- 2026-10-18 일심으로 일심 작업: 이 PC Runner에 프로젝트 `ilsim`(저장소 루트, test `./gradlew check -q`) 등록, Runner 재시작함. Runner가 띄우는 모든 프로세스에 `ILSIM_RUNNER=1`을 넣고 저장소 훅(SessionStart·Stop)은 이 값이면 건너뛴다. `AGENTS.md`에 Runner 단계 작업은 인수인계를 건드리지 않는다는 규칙 추가. worktree는 HEAD에서 만들어지므로 커밋 안 한 변경은 작업에 안 들어간다. 실제 `ilsim` 작업 E2E는 안 했다.
- 2026-10-18 웹 제목·상단 로고를 `ILSIM`으로 변경.
- 2026-10-18 규칙·메모리 화면: 승인 대기 항목을 맨 위로 정렬, 항목마다 "장단점" 툴팁(마우스 올리기/탭). 문구는 규칙 종류별 고정 설명(`ilsim-web/src/ruleTradeoffs.ts`, BLOCK이면 단계 차단 안내 추가). 루트 `./gradlew check`·웹 빌드 통과. 이 시점까지의 작업 트리 전체를 커밋·푸시했다.
- 2026-10-18 대시보드 입력: Enter 한 번에 작업 하나(새 Claude 세션)를 만들고 입력 칸을 비운 채 대시보드에 남아 다음 요청을 바로 받는다. Shift+Enter 줄바꿈, 한글 조합 중 Enter는 무시. 웹 빌드 통과, 브라우저 확인은 안 함.
- 2026-10-18 sandbox `greeter-shout` 실제 Claude E2E 4단계 완료($0.67): 요구사항 대화 1턴(`message` 이벤트 기록 확인), TEST_CODE·IMPLEMENTATION에서 code-risk-analyzer 게이트(`analysis`) PASSED. 서버·Runner는 새 코드로 재시작해 둔 상태(로그는 세션 scratchpad).
- 2026-10-18 `./gradlew :ilsim-server:test` 통과(109건, 실패 0). 이번 세션에서 웹과 Runner는 다시 검증하지 않았다.

## 사용자 확인 대기

- task 도메인 패키지 분류 승인: 추천은 `ReasoningEffort`, `ArtifactHash`, `PromptTemplates`를 policy에 두는 것이다. 승인하면 이동하고 import를 고친다(약 21개 파일 영향).

진행 로그(내 메시지·도구 호출 접기), 사용량 화면·프로젝트 드롭다운과 탭(모바일 폭 포함), 자동 로그인·2단계 인증 등록(실제 Google Authenticator), `greeter-title` 요구사항 채팅(2턴), 규칙·메모리 탭과 분석 버튼, 통계 합계, 모바일 폭(~400px), 로그인/설정, 산출물 다이얼로그.

## 다음 후보

- 결정 대기: 2단계 인증을 켤 때 다른 기기의 자동 로그인 토큰을 모두 끊을지(추천: 끊기). 지금은 켜기 전에 받은 토큰이 코드 없이 통과한다.
- 일심으로 일심 자체를 개선(이 저장소를 프로젝트로 등록).
- 후순위: Spec Kit 연동, 기존 도구와 비교.

## 로컬 실행 환경

PC마다 따로 준비한다. 이 PC의 상태는 저장소에 없으므로 다른 PC에서는 아래 순서로 다시 만든다.

- 서버: `./gradlew :ilsim-server:bootRun`. MySQL(도커 `ilsim-mysql`, DB/계정/비밀번호 기본값 `ilsim`)이 필요하다. 처음 띄우면 `~/.ilsim/token`, `~/.ilsim/server-secret.key`가 생긴다.
- 계정: 웹 가입이 없으므로 DB에 직접 INSERT한다(README). AI 전용 계정 `claude`의 비밀번호는 저장소 루트 `.claude-login`(gitignore, 한 줄 `아이디:비밀번호`)에 두고, 로그인은 `/api/auth/login`(헤더 `X-Requested-With: ilsim`)으로 한다.
- JEV 키: 웹 설정 화면에서 등록하면 DB에 암호화 저장되고, Runner는 작업마다 서버에서 받는다. 환경 변수는 필요 없다. 서버 비밀 키(`server-secret.key`)가 PC마다 다르므로 DB를 옮기면 키를 다시 등록한다.
- Runner: `./gradlew :ilsim-runner:installDist` 후 `ilsim-runner/build/install/ilsim-runner/bin/ilsim-runner run`. 프로젝트는 `ilsim-runner add <id> <경로>`, `set <id> test "<명령>"`으로 등록하고(`~/.ilsim/runner.json`), 시작할 때만 읽으므로 등록 후 재시작한다.
- 웹: `ilsim-web`에서 `npm install && npm run dev`(5173).
- 테스트 프로젝트(삭제하지 않고 E2E 재검증에 쓴다):
  - `sandbox`: `Greeter.java` 하나인 Java 프로젝트. 이 PC 경로 `../ilsim-sandbox`, test `./gradlew test -q`, analysis `java -jar <저장소>/code-risk-analyzer/build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan .`(먼저 `./gradlew :code-risk-analyzer:bootJar`).
  - `osiv`: `~/programming/study/osiv`(레이어드 Spring Boot 4/JPA/Java 21)를 `../ilsim-osiv`로 복제해 origin을 끊은 것. 원본은 건드리지 않는다. test `./gradlew test -q`.
- 새 세션에서는 `curl localhost:8080/api/auth/me`로 서버가 떠 있는지 먼저 확인한다.
