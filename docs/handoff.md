# 세션 인수인계

AI 작업 세션이 이어받을 상태를 적는다. 새 세션은 이 파일을 먼저 읽고, 작업을 마치면 갱신한다. `.claude/settings.json`의 훅이 시작할 때 이 파일을 보여 주고, 작업 트리에 이 파일보다 나중에 바뀐 파일이 있으면 종료를 막는다.

마지막 갱신: 2026-10-17 (마크다운·다이얼로그, osiv 등록, 프로젝트 분석 버튼, 인수인계 저장소 이동)

## 작업 규칙 (사용자 지시)

- 항상 한국어. 보고는 [AGENTS.md](../AGENTS.md) 형식("완료" / "결정 필요")으로 짧게.
- 커밋·푸시는 사용자가 하라고 할 때만 한다. 이력 재작성 금지.
- 웹은 디자인이 중요해 백엔드를 먼저 한다. 서버는 로컬(+터널/모바일)에서 쓴다.
- 어느 PC에서 써도 같은 기능을 제공해야 한다. PC 전용 설정(`settings.local.json`, `~/.claude` 메모리)에 기능을 두지 않는다.
- 비밀번호·토큰·키는 이 파일에 적지 않는다.

## 지금까지 (HEAD e672453, 푸시됨)

웹 로그인, 서버 Kotlin 헥사고날+JPA, 루트 Gradle 통합, 실패 경로 테스트, Claude 비용 집계(단계·작업·모델별, 웹 표시).

## 미커밋 변경 (작업 트리에 있음, 검증 통과)

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

## 검증 상태

루트 `./gradlew check` 통과, 웹 빌드 통과, 실제 Claude+JEV E2E 4단계 통과. osiv 분석 E2E 통과(아키텍처 11·컨벤션 9 규칙), 위반 주입 시 `check`가 5건 검출.

## 사용자 확인 대기

`greeter-title` 요구사항 채팅(2턴), 규칙·메모리 탭과 분석 버튼, 통계 합계, 모바일 폭(~400px), 로그인/설정, 산출물 다이얼로그.

## 다음 후보

- 로그에 내 채팅 메시지 줄 추가, 도구 호출 접기(텍스트·결과만 기본 표시).
- 규칙 파일 동시 쓰기 잠금, 샌드박스에 analysis 명령을 설정해 분석기 게이트도 E2E.
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
  - `sandbox`: `Greeter.java` 하나인 Java 프로젝트. 이 PC 경로 `../ilsim-sandbox`, test `./gradlew test -q`.
  - `osiv`: `~/programming/study/osiv`(레이어드 Spring Boot 4/JPA/Java 21)를 `../ilsim-osiv`로 복제해 origin을 끊은 것. 원본은 건드리지 않는다. test `./gradlew test -q`.
- 새 세션에서는 `curl localhost:8080/api/auth/me`로 서버가 떠 있는지 먼저 확인한다.
