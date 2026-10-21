# 세션 인수인계

AI 작업 세션이 이어받을 상태를 적는다. 새 세션은 이 파일을 먼저 읽고, 작업을 마치면 갱신한다. `.claude/settings.json`의 훅이 시작할 때 이 파일을 보여 주고, 작업 트리에 이 파일보다 나중에 바뀐 파일이 있으면 종료를 막는다.
여러 세션이 동시에 쓰므로 바꿀 부분만 Edit로 고치고 파일 전체를 다시 쓰지 않는다. 다른 세션이 쓴 항목은 건드리지 않는다([AGENTS.md](../AGENTS.md)).

마지막 갱신: 2026-10-20 (웹 다크 모드, osiv E2E와 규칙 생성기 보강, 추천 규칙 자동 OFF)

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
- 2026-10-18 AI 비용 분석(서비스 코드·설정 변경 없음): [분석 보고서](ai-cost-analysis.md). 기본 최소 Sonnet·재시도 승격, 검토 도구 미제한, 위험 검토 순서·중복 입력·JEV 재사용을 확인했다. CLI 2.1.302의 `--resume` 누적 비용을 실행별로 더하는 중복 집계와 검토 모델을 승격으로 세는 통계를 재현했다. Gradle은 sandbox 소켓 권한으로 시작 실패, 기존 컴파일 산출물을 JUnit Launcher로 직접 실행해 관련 97건 통과(재컴파일·유료 AI 호출 없음). 실제 청구·운영 환경 변수·모델 품질 비교는 미검증이다.
- 2026-10-18 사용자 추가 요구사항을 [AI 비용 분석](ai-cost-analysis.md#추가-요구사항-모델추론-강도-공동-선택과-jev-지속-보정)에 반영했다: Haiku를 실제 후보로 사용하고 모델·effort를 함께 평가해 기능·품질을 만족하는 최소 예상 완료 비용 조합을 선택한다. JEV 선택을 실행 결과·제한된 비교 평가로 계속 검증하고 과소·과대 선택을 보정한다. 평가·재시도·검토 비용, 환경 오류 분리, 서버 영속 이력·정책 버전·롤백과 수용 기준을 명시했다. 문서만 변경했으며 선택·보정 기능은 미구현이다. 문서 내용·링크·공백 검증 통과.
- 2026-10-18 [JEV 우선 처리 요구사항](ai-cost-analysis.md#추가-요구사항-jev-우선-처리로-ai-agent-사용-최소화)을 추가했다: 정적 처리·결과 재사용 후 JEV로 가능한 모든 판단·선별·분류·점수화를 우선 처리하고, 생성·수정·불확실한 부분만 Agent로 이관한다. 적용 범위, 동일 기능·결과 계약, 독립 검토·사용자 승인 보존, JEV 완료 판정의 표본 검증·지속 보정, Agent 호출·도구·토큰·총비용 지표와 수용 기준을 명시했다. Agent가 필요한 경우 기존 모델·effort 선택 정책을 적용한다. 문서만 변경했으며 기능은 미구현이다. 문서 링크·공백 검증 통과.

- 2026-10-18 브랜치·승인 동작을 소스로 확인했다: Runner는 등록 저장소의 현재 HEAD에서 `ilsim/<feature>-<taskId 앞 8자>` 브랜치와 worktree를 만들고 단계 성공 시 로컬 커밋한다. 웹 승인은 요구사항·테스트 케이스를 통과시켜 다음 단계로 진행하는 기능이다. 테스트 코드·구현은 별도 승인 없이 완료되며, main 병합·원격 push 경로는 현재 없다. 이번 조회에서 서비스 코드는 수정하지 않았다.
- 2026-10-18 작업 중지·멈춘 단계에 답하기(커밋 안 함): 웹 작업 상세 "중지"(`POST /api/tasks/{id}/stop`, 대기·실행 중 단계 → FAILED "사용자가 중지했습니다" + BLOCKED). Runner는 실행 중 3초마다 `GET /runner/api/tasks/{id}/stop`(단계가 RUNNING이 아니면 true)을 묻고 `StopSignal`로 작업 스레드가 띄운 Claude·테스트 프로세스(자식 포함)를 끝낸다. 중지 뒤 늦은 결과는 비용·세션만 반영. 모든 단계에서 Claude 세션을 기록하고, 차단된 단계에 말을 보내면(`POST .../retry {"instruction"}`) 그 세션을 `--resume`으로 이어 실패 사유와 사용자 말을 전한다(CLI처럼). 말 없이 재시도하면 새 세션+마지막 실패 사유. 작업 상세는 단계 탭 없이 작업 하나 = 대화창 하나다(사용자 요청: CLI처럼). 단계가 바뀌는 곳에 구분선, Claude 텍스트(마크다운)·내 메시지·도구 호출 접기·시도마다 검증 신호등(서버가 보고마다 `verdict` 이벤트: 첫 줄 PASSED|FAILED, 다음 줄 `항목=결과`)·실패 내용(`failure` 이벤트, 접힘). 요구사항 문서·테스트 케이스·단계별 diff·검증 결과·모델 선택은 상단 버튼으로만 연다. 맨 아래 입력창 하나(Enter 전송): 차단 시 답하기/다시 시도, 요구사항 승인 대기 시 문서 수정 대화, 다른 승인 대기 시 수정 요청(reject), 승인은 입력창 위 막대. 모든 단계 지시문 끝에 "마무리" 보고(한 일·검증·결정·사용자 결정 사항) 요청을 붙였고, TEST_CODE·IMPLEMENTATION은 Runner가 프로젝트 테스트 명령을 알려 Claude가 직접 돌리고 고치게 한다(`Gate.selfCheck`, 그 명령을 `--allowedTools`에 추가. Runner는 끝난 뒤 다시 검증). 작업 상세는 화면 전체 스크롤 없음(`.app-fixed`), 대화 영역만 스크롤하고 처음 열면 맨 아래, 입력창 하단 고정, `result`(비용 JSON) 이벤트는 숨김. 끝난 작업에서 보내면 `POST /api/tasks {continueFrom}`으로 같은 프로젝트에 새 작업을 만들고 이동하며, 새 작업은 `Task.baseBranch`(직전 작업 브랜치, `TaskRecord`에 저장) → `Assignment.baseBranch` → Runner가 HEAD 대신 그 브랜치에서 worktree를 만든다. 작업 이름은 `taskTitle`(요구사항 문서 첫 줄 `# 제목`, 없으면 지시문 첫 줄 40자). 요구사항 지시문에 요약 제목 첫 줄 규칙을 추가했다.
  - 검증(이 시점): 제 범위 서버 테스트(TaskStopRetry·FailurePath·RequirementsConversation·Cost)와 웹 빌드는 통과. 서버 전체 테스트는 6건 실패인데 모두 다른 세션의 진행 중 변경(ModelSelector 비용 라우팅, `poll`의 `store.saveTask` 직접 호출로 PersistenceTest "db down")이다. Runner는 다른 세션이 고치던 `jev/JevRiskScreen.kt:46`의 `"r$i의"`(Kotlin이 `i의`를 식별자로 읽음, `${i}`여야 함) 때문에 컴파일이 안 돼 마지막 변경 후 Runner 테스트를 다시 돌리지 못했다.
  - 질문 입력 처리(사용자 결정: Claude 판단, 다른 세션과 충돌 없게): 별도 실행을 늘리지 않고 요구사항 단계 지시문에 "질문·설명·조사면 파일을 바꾸지 말고 답만 하고 마지막 줄에 `[ILSIM:ANSWERED]`"를 넣었다(`PromptTemplates.ANSWERED_MARK` = Runner `Gate.ANSWERED_MARK`). Runner는 그 표시를 보면 worktree 변경을 되돌리고 `StageResult.answered=true`로 보고, 서버는 요구사항 PASSED + 작업 COMPLETED(남은 단계 PENDING 유지). 웹은 표시를 지우고 "질문에 답했습니다"를 보여 준다. Claude 텍스트 이벤트 상한 2000→20000자(`StreamSummarizer`). 테스트 `TaskAnswerTest`, `TaskExecutorAnswerTest` 통과. 전체 check는 `IlsimApplicationTests.contextLoads` 1건 실패(다른 세션 변경: `JpaTaskStore`가 final이라 CGLIB 프록시 불가). `5d1e67dc`의 worktree(`~/.ilsim/worktrees/ilsim-5d1e67dc`)와 브랜치 `ilsim/feat-5d1e67dc-5d1e67dc`(일심 단계 커밋 4개)는 사용자 승인으로 삭제했다. 서버의 작업 기록 자체와 `cc739305`는 그대로다.
  - 작업 삭제(사용자 요청): 작업 상세 "삭제" → `DELETE /api/tasks/{id}`(실행 중이면 400, 먼저 중지). 메모리·DB(작업+이벤트, `TaskEventJpaRepository.deleteAllOfTask`에 트랜잭션을 둬 다른 세션이 고치는 `JpaTaskStore` 클래스 트랜잭션과 분리)에서 지우고, 정리 일감(`Cleanup`)을 인메모리 큐에 넣는다. Runner는 루프마다 `POST /runner/api/cleanup/claim`으로 가져가 `TaskExecutor.cleanup`: worktree 제거+prune, 끝나지 않은 작업만 브랜치 삭제(끝난 작업 브랜치는 결과라 유지). 서버 재시작 시 큐는 사라진다. 테스트 `TaskDeleteTest`, `TaskCleanupTest` 통과. 전체 check는 `contextLoads` 1건만 실패(다른 세션의 `JpaTaskStore` final 문제). `5d1e67dc`·`cc739305`는 재시작 후 사용자 승인으로 API 삭제했고, Runner가 정리 일감을 받아 `cc739305` worktree·브랜치를 지웠다(실제 E2E로 삭제·정리 동작 확인).
  - 2026-10-18 사용자 지시로 두 세션의 작업 트리 전체를 `74fa418`로 커밋하고 origin/main에 푸시했다(루트 `./gradlew check`·웹 빌드 통과 확인 후). 서버(`bootRun`)·Runner(`installDist` 후 `run`)를 새 코드로 재시작했고 프로젝트 sandbox·osiv·ilsim 온라인 확인. 로그는 세션 scratchpad. 이후 `ClaudeAgent.kt` 등 Runner 파일 변경은 다른 세션 작업이다. 구현 단계 테스트 실패는 JUnit XML에서 실패 테스트·메시지·위치를 뽑는다(`TestFailures`, 리포트 없으면 잡음 줄 제거한 출력). 사용자 결정: 별도 AI 실패 진단은 하지 않는다. 루트 `./gradlew check`·웹 빌드 통과, 실제 Claude E2E·브라우저 확인은 안 함. Runner·서버 재시작 필요.

- 2026-10-18 [AI 비용 추가 점검](ai-cost-analysis.md#추가-점검-재실행전송판단-입력-최적화): 기능 보존 조건과 함께 작성·검증의 부분 재개, 세션 오류 분류, 결과 보관·멱등 재전송, 위험 검토 묶음별 저장, 로그 전송 분리, 분석 전 JEV 키 갱신, 중복 발췌·도구·프롬프트 정리를 문서에 추가했다. 빈 응답의 PASS·완료 캐시 처리와 캐시 무효화 누락도 재현했으며, `--bare`의 구독 인증 제약을 확인했다. 서비스 코드·설정 변경 없음. 기존 컴파일 산출물로 관련 67건 중 66건 통과, 프로세스 중지 1건은 sandbox의 sysctl 권한 제한으로 실패. Gradle은 소켓 권한으로 시작 실패(서비스·기존 테스트 재컴파일 없음), 임시 재현 6항목 확인. 실제 운영 비용·모델 품질 동등성은 미검증이다.

## 2026-10-18 AI 비용 최적화 적용·검증 (커밋 안 함)

- 사용자 요청으로 [AI 비용 분석](ai-cost-analysis.md#적용-현황-2026-10-18)을 구현했다. 누적 비용·토큰 차액과 원본 호출·실행 이력, 비용 미확인·과거 원본 미검증 구분, 프로젝트 분석·JEV 비용, 실행 ID 기반 결과 멱등 처리와 늦은 결과 격리, Runner의 작업 결과·진행 이벤트 보관·재전송, 작성·검증·검토·커밋 완료 지점 재개를 반영했다. 서버도 JEV 평가 전에 시작·미확인 기록을 저장하고 저장 실패 뒤 같은 작업을 바로 재배정하지 않는다. 단발·작성 CLI 도구/MCP 범위, stdin 입력, 세션 부재만 새 세션 fallback, 정적·테스트 우선, 위험 검토 부분 저장·지문·중복 발췌, 유효한 검토 캐시와 필수 응답 검증을 포함한다.
- 작성은 Haiku·Sonnet·Opus × 지원 effort 5종의 예상 완료 비용과 실제 검증·승인·회귀 자료로 보정한다. 검토는 별도 목적·작업군의 외부 정확·누락·오탐 피드백으로 보정하며 JEV 자기 확신이나 모델 간 일치를 정답으로 쓰지 않는다. 제한된 변경 검토 표본의 주기·횟수·총/건별 예약 예산, 원문·연결 ID 보존, 정책 롤백, 규칙별 근거·공유 의존 정보의 부분 재사용과 전역 누락·충돌 재검토를 추가했다. 웹 통계에 사용량·검토 품질·표본 예약을 표시한다. 품질 자료가 없는 저가 조합·JEV 규칙 완료는 기본 비활성, 추가 유료 표본 횟수·총예산도 기본 0이다. 설정·피드백 API는 분석 문서 적용 현황 참고.
- 앞 세션이 보고한 `JpaTaskStore` final 프록시 문제를 수정했다(클래스와 구현 메서드 open). 기존 삭제·질문 응답·후속 브랜치·중지·재시도 변경을 유지했다. 최종 재컴파일 후 직접 JUnit 실행: Runner 144/144 통과, 서버 139건 중 138건 통과(아키텍처·클래스 프록시 위임·트랜잭션 롤백 포함). 실패 1건은 `contextLoads`의 MySQL 소켓 권한 제한이다. `./gradlew :ilsim-runner:test :ilsim-server:test --offline`은 파일 잠금 소켓 권한으로 시작 실패. Python 비용 비교 테스트 6/6, 웹 `npm run build`, 문서 링크·`git diff --check` 통과. 실제 유료 AI·브라우저·운영 청구 대조는 하지 않았다.
- [절감률 계산 도구](../scripts/ai_cost_report.py)는 변경 전후 완료 작업당 총비용을 비교하고 미확인·과거 원본 미검증 비용이면 확정 비율을 내지 않는다. 조건부 비캐시 동일 토큰 단가 효과는 Sonnet→Haiku 95%, Opus→Sonnet 50%, Opus→Haiku 97.5%다. 누적 집계 예시의 표시액 40% 보정은 실제 사용 절감이 아니다. 운영 절감률·모델 품질 동등성은 자료 수집 후 검증해야 한다. 서버·Runner를 함께 업데이트·재시작해야 적용된다. 이 세션에서 재시작·커밋·푸시는 하지 않았다.

## 2026-10-18 Codex 연동 (만들었다가 사용자 지시로 전부 제거)

- Codex 실행기·계정별 Agent(`accounts.agent`, `Task.agent`, 프로젝트 `agent`/`codex-model`)·Codex 사용량을 만들었으나, 사용자가 Claude 두 번째 계정을 결제하고 "코덱스 관련은 다 지워"라고 해 코드·테스트·README·웹에서 모두 제거했다(커밋된 적 없음). DB에서도 웹 계정 `codex`를 지우고 `accounts.agent` 열을 삭제했다. 남은 것은 Claude 계정별 사용량을 위한 `PlanUsage.provider`(`claude`, `claude@<계정>`)뿐이다. 작업 저장본 1건에 남은 `"agent"` 필드는 역직렬화가 무시한다.
- `PLAN.md`의 Codex 문구도 사용자 지시로 지웠다(Claude Code만). 이 PC의 brew `codex` 제거(의존성 `openssl@3`도 자동 제거됨)와 PC의 테스트 폴더 삭제는 권한 분류기가 거부해 사용자가 직접 실행해 끝냈고, `~/.codex`(2.1GB)도 사용자가 직접 지웠다(ChatGPT 데스크톱 앱이 실행 중인 상태라 앱이 다시 만들 수 있다).

## 2026-10-18 Claude 여러 계정·공통 설정 (`c0e1987`, 푸시됨)

- 사용자 결정: 본인 소유 Claude 구독 계정 여러 개를 일심에 등록하고, 새 세션을 열 때 남은 사용량을 비교해 계정을 고른다. 한도 초과가 난 뒤 다른 계정으로 우회하지 않는다(실행 중 한도 초과 → NETWORK → 단계 차단). 토큰·API 키가 아니라 `claude auth login` 로그인을 일심이 중계한다. 약관은 명시적 위반 근거는 없으나 공식 승인도 없다(위험 0 아님, 사용자 인지).
- 확인한 사실(공식 문서 code.claude.com/docs/en/iam): `CLAUDE_CONFIG_DIR`별로 로그인·세션 기록·설정이 분리되고 macOS 키체인 항목도 디렉터리별로 따로 생긴다. `-p`에서 `ANTHROPIC_API_KEY`가 있으면 API 과금이 우선. `claude auth status --json`에 loggedIn·email·subscriptionType. CLI 바이너리 내부 조사는 권한 분류기가 거부해 하지 않았다.
- 서버: 새 컨텍스트 `claude`. 공통 설정(`claude_environment` 테이블, CLAUDE.md·settings.json, JSON 객체 검사, 내용 해시 버전), 계정·로그인 세션·삭제 일감은 인메모리. 웹 `/api/claude/{environment,accounts,logins}`, Runner `/runner/api/claude/...`(로그인 claim → URL → 코드 1회 전달 → 결과, 계정 보고 응답으로 삭제 일감). 사용량 provider에 `claude@<계정>` 허용.
- Runner: `ClaudeAccounts.kt`. 계정 디렉터리 `~/.ilsim/claude-accounts/<이름>`, `Shell.process`가 `claude` 실행 시 스레드의 계정(`ClaudeAccountScope`)으로 `CLAUDE_CONFIG_DIR`를 넣고 API 키 변수 3개를 지운다. 공통 설정을 계정 디렉터리에 `CLAUDE.md`·`settings.json`으로 씀(버전 마커). 세션 id는 `claude@<계정>:<id>`, 이어 가기는 그 계정, 새 세션은 5시간·주간 중 큰 쪽이 가장 낮은 usable 계정(구독 없으면 제외), 계정이 없으면 기본 `~/.claude`. `PlanUsageReporter`가 5분마다 설정 동기화·계정 상태 보고·계정별 `/usage`. `ClaudeLoginWorker` 스레드가 `claude auth login --claudeai`(BROWSER 비활성)를 띄워 URL·코드를 중계.
- 웹: 설정에 "Claude 계정"(추가·로그인 카드·삭제)·"Claude 공통 설정" 편집, 사용량 탭은 계정별 카드.
- 이 PC 상태: 서버·Runner를 새 코드로 재시작(로그 scratchpad). 사용자 `~/.claude`의 CLAUDE.md·settings.json을 공통 설정으로 등록. 계정 `personal` 로그인을 시작해 URL 중계와 설정 파일 적용까지 확인했고, 사용자가 웹에서 로그인·코드 입력을 해야 끝난다(15분 지나면 실패 처리, 디렉터리는 남으므로 웹에서 삭제). 두 번째 계정은 아직 구독 없음.
- 검증: 루트 `./gradlew check` 통과(`ClaudeAccountsTest` 5, `ClaudeAccountTest` 3), 웹 빌드 통과. 사용자가 웹에서 `personal`(pro) 로그인 완료 → DONE·usable. 키체인에 `Claude Code-credentials-edde1da4`가 따로 생기고 기본 `~/.claude` 로그인은 유지됨. 계정별 `/usage`(claude@personal 5시간 35%·주간 50%)와 Codex 사용량 보고 확인. 실제 작업에서의 `rate_limit_event`·두 계정 번갈아 실행은 미검증(두 번째 계정 구독 전).
  - 결제 전 확인(사용자 결정): sandbox 질문 작업 `4fe95bc9`(답변만, $0.024)이 `Claude 계정: personal`로 실행되고 세션 기록이 `~/.ilsim/claude-accounts/personal/projects`에 남았으며 서버에 `claude@personal:<id>`로 저장됨. 이후 사용자가 두 번째 계정을 결제하고 웹에서 `second`(pro)를 등록해 두 계정 모두 usable이고, Codex 제거 후 재시작한 서버·Runner에서 계정별 사용량(`claude@personal`, `claude@second`) 보고 확인. 처음 시도한 `second` 로그인은 claude.ai 승인 화면이 "Max 또는 Pro 구독 필요"로 막아 구독 없이 등록할 수 없음을 확인했고, 웹 API로 삭제했다(대기 중이던 로그인 프로세스는 15분 뒤 끝나고 Runner가 다음 동기화 때 디렉터리를 지운다).

## 2026-10-18 JEV 모델 선택 평가·조정 (`c0e1987`, 푸시됨)

- 사용자 요청: 모델별 작업 이력으로 JEV 선택이 맞았는지 파악하고 조정하는 기능. 서버 `task/domain/RoutingReview.kt`: 완료 실행마다 판정(FIT, OVER=같은 작업군에서 기준을 넘은 20% 이상 싼 조합이 있음, UNDER=실패 후 더 높은 조합으로 성공, UNKNOWN=환경 장애·근거 없음), 조합별 성공률·JEV 예측 평균·예측 차이·Brier·실측 비용·권장 보정(표본 ≥ minimumSamples일 때 −차이×n/(n+10), ±0.3), 작업군별 최다 사용·기준 통과 최저가 조합. 추가 AI 호출 없음.
- 조정 `RoutingAdjustments`(조합별 사용 여부·성공 예측 보정)를 `routing_adjustments` 테이블에 쌓고 마지막 것을 `ModelSelector.adjustments`로 적용(`CostRoutingPolicy.choose`가 끈 조합 제외·보정 후 품질 기준 비교). 정책 버전은 바꾸지 않아 보정 표본이 이어지고, `ExecutionRecord.adjustmentVersion`에 적용 버전을 남긴다. API `GET /api/routing[?projectId]`, `PUT /api/routing/adjustments`, `POST .../recommended`, `POST .../{version}/restore`("none"=해제). 웹 "모델 선택" 탭.
- 검증: 루트 `./gradlew check` 통과(`RoutingReviewTest` 5), 웹 빌드 통과. 서버 재시작함(Runner는 변경 없음). 실제 이력은 아직 1건뿐이라 화면 확인·보정 효과는 미검증.
- Artificial Analysis 조사(Claude Code 5.5 코딩 에이전트 지수·작업당 비용, 모델 페이지의 Intelligence Index·실행 비용): 페이지 HTML에 내장된 JSON을 코드로 읽었다(AI 토큰 없음). 추천 후보는 응답에 정리. 사용자 결정: 외부 사이트(AA 무료 API 가능 확인: 키 필요·하루 1,000회·출처 표기, 코딩 에이전트 데이터는 없음)는 쓰지 않고 Claude Code 실측 비용·일심 결과로 조정한다. 같은 계열 새 버전(5.5→5.6)은 적극 도입, Fable은 잠금.
  - 새 버전 감지: `claude -p "/model <별칭>"`은 로컬 명령이라 비용 0("Set model to `Sonnet 5.5`"), 로그인 없는 빈 디렉터리 `~/.ilsim/model-probe`에서 실행. Runner `ModelDiscovery`가 1시간마다 확인해 `ClaudeModels`(이제 가변, REVIEW=현재 Sonnet)를 올리고 `POST /runner/api/models`로 보고. 서버 `ModelCatalog`가 같은 계열의 더 높은 버전만 받아 `ModelTier.modelId`가 그 ID를 돌려준다(메모리, Runner가 다시 보고). 라우팅 이력·조정·보고서는 계열(`ModelTier.of`) 단위라 새 버전이 이전 버전 성적을 이어받는다.
  - Fable 잠금: 서버 `ModelCatalog.LOCKED`(받지 않음·조정 거부), Runner `Shell.process`가 `--model`에 fable이 있으면 실행 거부.
  - 실수 기록: 확인 중 `claude -p "/models"`(없는 명령)를 실행해 기본 로그인에서 Opus 호출 1회($0.09)가 발생했다.
  - 검증: 루트 `./gradlew check` 통과(`ModelUpgradeTest` 4, `ModelDiscoveryTest` 3), 웹 빌드 통과. 서버·Runner 재시작 후 실제 감지 결과 5.5 그대로(도입 기록 없음), 웹 "모델 선택" 탭에 사용 모델·잠금 표시.

## 2026-10-18 주석 제거·추천·스킬·개인정보 (커밋·푸시: `d145216` 주석 제거, `aad2215` 추천, `5c4fefc` 스킬, 이후 docs)

- 사용자 지시: 주석은 요청이 있을 때만 쓴다(`AGENTS.md` 규칙 추가). 저장소의 kt·kts·java·groovy·gradle·ts·tsx·css·xml·yml·properties·toml·sh·py 주석을 모두 지웠다. 제외: 분석기 테스트 픽스처(`src/test/resources`), Gradle Wrapper(`gradlew*`, `gradle-wrapper.properties`), 셰뱅, `vite-env.d.ts`의 `/// <reference>`(지시자). 문자열 속 주석 기호는 유지.
- 추천·업그레이드: Runner `standards/Recommendations.kt`(`ArchitectureRecommender`, `ConventionRecommender`, `RecommendationReport`), 새 Probe `convention/DesignProbes.kt`(no-comments, domain-encapsulation, identifier-value-object, max-function-lines 30, max-nesting 2, max-file-lines 300)와 `CodeText`(문자열·주석 제거 보기, 함수 범위). 규칙에 `recommendation{principle,rationale}` 필드, id `recommend:` 접두사. 분석 시 채굴 규칙 뒤에 붙이고 AI 검토·자동 승인에서는 뺀다. 위반 0이면 BLOCK, 있으면 REVIEW. 재분석 시 승인은 유지되고 위반이 사라지면 BLOCK. CLI `ilsim-runner recommend <id|경로>`(AI 없음). 서버 `RuleView`에 principle·rationale·compliant·total·examples. 웹 규칙 화면에 "추천" 표시와 "업그레이드 작업 만들기"(리팩터링 작업 생성 후 작업 화면으로 이동).
- 이 저장소에 `recommend` 실행 결과: 주석 307/307, 도메인 캡슐화 40/40 준수. 식별자 값 객체 3/31, 함수 30줄 999/1061, 중첩 949/1061, 파일 300줄 301/307 위반 있음.
- Claude 스킬: 서버 `claude_skills` 테이블(`JdbcClaudeSkillStore`), `ClaudeSkill`(이름 규칙, SKILL.md+description 머리말 필수, 상대 경로만, 50파일·50만 자), `PortablePaths`(공통 설정·스킬에 `/Users/<x>`, `/home/<x>`, `C:\Users\<x>` 거절). API `GET /api/claude/skills`, `PUT|DELETE /api/claude/skills/{name}`, Runner용 환경 응답에 `skills` 포함(버전 해시에 포함). Runner `ClaudeSkills.install`이 계정 디렉터리 `skills/<이름>`에 설치하고 `.ilsim-skills` 목록으로 일심 설치분만 정리(`synced`·사용자 스킬은 유지). 웹 설정 "Claude 스킬"(추가·편집·삭제·폴더에서 가져오기). 계정 미등록 PC의 기본 `~/.claude`에는 설치하지 않는다(공통 설정과 같은 동작).
- 개인정보 점검: 작업 트리 추적 파일에는 이 파일의 이메일 2개·절대 경로 1개만 있어 가렸다.
- 이력 재작성(2026-10-19, 사용자 지시): `git filter-repo`로 파일 내용의 `/Users/<이름>`→`~`, 두 번째 계정 이메일→`second-account`, 작성자 실명→`dochiri0916`으로 바꾸고 origin 브랜치 7개를 강제 푸시했다(`history/code-risk-analyzer/main`은 해당 없음). 모든 SHA가 바뀌었다. 이 파일의 옛 SHA 대응: `d6985af`→`e2b32cf`, `c0e1987`→`5c0674d`, `74fa418`→`3b3b0bd`, `3220b81`→`64ac088`, `92532ff`→`b8154b7`, `e672453`→`3ef7c14`. 다른 PC의 클론은 `git fetch && git reset --hard origin/main`(미커밋 변경 백업 후) 필요. 별도 저장소 원격(`build-convention-origin`, `integration-sync`) 이력은 건드리지 않았다.
- 스킬은 일심(DB)에만 저장하고 기본 `~/.claude`에는 설치하지 않는다(사용자 결정).
- 공통 CLAUDE.md에 "주석은 요청 시에만" 추가는 사용자 결정(A)이나 미적용: `claude` 테스트 계정 로그인이 401(비밀번호 불일치)이라 API로 넣지 못했다.
- 검증: 주석 제거 후 루트 `./gradlew check`·`build-convention` 자체 `./gradlew check`·웹 빌드 통과. 기능 추가 후 루트 `./gradlew check`(새 테스트 `RecommendationsTest` 5, `ClaudeSkillsTest` 3, `ClaudeSkillTest` 2 포함)·웹 빌드 통과. 서버·Runner 재시작과 브라우저·실제 E2E는 안 했다.

## 2026-10-20 웹 다크 모드·osiv E2E·규칙 생성기 보강·추천 자동 OFF (모두 커밋 안 함)

- 사용자 지시: 일심 화면을 클로드 코드 앱과 비슷하게, 다크 모드 기준. `ilsim-web/src/styles.css`만 수정: 색 토큰을 다크(배경 `#262624`, 강조 테라코타 `#d97757`)로 바꾸고 `--ok-bg`·`--bad-bg`·`--warn-bg`·`--bad-line`·`--warn-line` 토큰을 추가해 하드코딩된 라이트 색을 치환. 작업 대화는 AI 응답을 말풍선 없이, 내 메시지를 테두리 카드로 표시.
- osiv E2E(2026-10-20, 사용자 지시): osiv 등록·규칙 파일·복제본(`../ilsim-osiv`)을 지우고 원본에서 새로 복제해 처음부터 등록했다. 작은 프로젝트(파일 1개 단위)에서 규칙이 모자라 생성기를 보강했다(커밋 안 함). 아키텍처: leaf-unit·absent-edge·pure/layer-externals의 단위 최소 파일 수 3→1. 컨벤션: 새 Probe `stereotype-package`(@Service 등은 같은 패키지에), `package-suffix`(패키지별 타입 접미사), `annotation-companion`(@Entity+@Getter처럼 함께 쓰는 어노테이션), `annotation-suffix` 2, `no-field-injection` 3, `no-wildcard-import` 5로 하향, `annotation-confinement`에서 @Entity 제외, 표본 5 미만이면 BLOCK 대신 REVIEW, `ConventionModel.inventory`(패키지별 타입·어노테이션 목록)를 AI 검토 입력에 추가해 누락 관행 제안을 받는다. AI 검토 기준은 "예외 없고 계층상 자연스러우면 표본이 적어도 KEEP". osiv: 아키텍처 24·컨벤션 27 규칙, 위반 주입(도메인→서비스 순환, 와일드카드, System.out, controller→repository, @Getter 누락) 모두 검출. `./gradlew :ilsim-runner:check` 통과. Runner는 재시작해 둔 상태.
- 자동 끄기 확대와 일급 컬렉션(사용자 지시): 컨벤션 추천 규칙 전부(`Spec.offOnViolation` 제거, 위반 1건이라도 있으면 자동 OFF)에 적용. 새 추천 `first-class-collection`(`FirstClassCollection` Probe, 도메인 클래스에서 컬렉션 필드가 다른 필드와 함께 있으면 위반, Java·Kotlin). 사용자 결정(`decidedBy="user"`)이 없으면 재분석 때 auto 결정을 새로 계산. 아키텍처 추천(`ArchitectureRecommender.evaluate`, `RuleApproval.carryOver`)에도 같은 자동 OFF를 적용(사용자 지시: 의존 방향 등 모든 기본 규칙을 정의해 두고 위반이 있으면 자동 OFF). 웹 규칙 화면: `decidedBy==='auto'`이고 거절 상태면 "자동 꺼짐" 표시와 "켜기" 버튼(승인)만 보인다(`Knowledge.tsx`, 서버 변경 없음, 웹 빌드 통과, 브라우저 확인 안 함). osiv: 일급 컬렉션 3/4·식별자 값 객체 0/5로 자동 OFF, 나머지 5개는 제안. `:ilsim-runner:check` 통과(커밋 안 함).
- setter 자동 끄기(사용자 지시): 추천 규칙 `recommend:domain-encapsulation`은 기본 켜 두되 도메인에서 setter·공개 var가 하나라도 있으면 분석 때 자동으로 거절(`decidedBy="auto"`, 설명에 "자동으로 꺼 둠")한다. 승인하면 켜지고(user 결정 유지), auto 결정은 재분석 때 다시 계산해 setter를 없애면 다시 제안으로 돌아온다. `ConventionRecommender.Spec.offOnViolation`, `ConventionApproval.carryOver`. 테스트 추가, `:ilsim-runner:test` 통과, osiv로 확인(커밋 안 함).
- 다크 고정(라이트/다크 전환 없음). 전환 여부는 사용자 결정 대기. 검증: 웹 빌드 통과, 브라우저 확인 안 함.
- 채굴기 영점 조준 시작(2026-10-20, 사용자 지시, 커밋 안 함): 일심의 목적은 프로젝트의 좋고 나쁨 판단이 아니라 이미 적용된 아키텍처·컨벤션을 파악해 따르게 하는 것이다. 그래서 `recommend`(외부 기준) 위반이 아니라 **채굴 규칙**을 AI 없이(경로로 실행하면 AI·저장 없음) 시험용 프로젝트에 돌려, reference의 `docs/`를 정답으로 대조한다. 시험용 프로젝트: `~/programming`의 java·kotlin-conventions-reference, haru-simgi, multi-module, ilsim-osiv, kafka-traffic-lab. java-ref 대조 결과: 이름·위치·의존·금지 패턴은 잡고 클래스 형태(`final`, VO 비공개 생성자·팩터리, `@FunctionalInterface`, `toCommand()`/`from()`, `@Valid`, `readOnly`, `*Id`·`*Port`·`*Command`·`*Result` 접미사)는 못 잡는다. 수정함: `IndentStyle`은 블록 안쪽 들여쓰기 차이로 단위를 정해 record 인자 줄을 세지 않는다(java-ref 476/487→473/473), `AnnotationConfinement`는 `adapter|application|domain` 이하 패키지로 일반화해 다른 슬라이스의 같은 계층을 허용한다(`ConventionTest` 2개 추가, `:ilsim-runner:test` 통과). 남은 문제(우선순위 순): 중첩 BC(`catalog/category`, `catalog/product`)를 슬라이스가 아닌 `shared:catalog`로, `global`을 슬라이스로 잘못 분류(아키텍처 의존 수준 왜곡, haru-simgi·multi-module은 NOT_APPLICABLE), `usecase` 패키지 접미사가 `UseCase`가 아닌 `Case`로 추출됨, 우연의 일치 규칙(`@Getter`→`Entity`, `@Order`→`Handler`), 새 Probe 후보 위 목록. kotlin-conventions-reference 대조는 아직 안 했다.
  - 같은 날 이어서(커밋 안 함): 아키텍처 `LayoutInference.strict`로 중첩 BC(`catalog.category`·`catalog.product`)를 슬라이스로, 레이어가 없는 모듈(kafka·redis·configserver 등)과 `autoconfigure` 같은 우연한 레이어 이름을 제외, `Locator`는 `A.B` 슬라이스 지원(java·kotlin-ref는 `shared:catalog` 없이 adapter→application→domain, haru-simgi는 NOT_APPLICABLE→PARTIAL, multi-module은 `security`만 레이어 구조라 여전히 NOT_APPLICABLE). 컨벤션: `package-suffix`는 CamelCase 공통 접미사(`UseCase`, 단일 표본은 마지막 단어), `annotation-suffix`는 역할 어노테이션(`Probes.roleAnnotations`)이거나 이름과 단어가 겹칠 때만, 같은 대상 묶음의 대표는 역할 어노테이션 우선(`@Getter`→`Entity`, `@Order`→`Handler` 제거). 새 채굴 Probe(`FormProbes.kt`): `class-final`, `parameter-final`(Java), `functional-interface`, `no-value-annotation`, `vo-private-constructor`, `domain-final-fields`. java-ref에서 `final` 197/197·파라미터 668/671·VO 36/36·도메인 필드 54/54로 문서와 일치, haru·multi-module은 0~69%라 규칙을 만들지 않는다. `@FunctionalInterface`는 java-ref 33/37(89%)로 임계 0.9 미달(`MessageBroker`·`MessageConsumer`·`OutboxRelay`·`OutboxWaker`가 문서와 어긋남). 테스트 추가, `:ilsim-runner:check` 통과. 커밋: `5f0859b`(웹), `d05930a`(Runner), 푸시 안 함. 이어서 추가(커밋 안 함): `package-method`(패키지 안 타입이 공통으로 가진 메서드: `request`→`toCommand` 7/7, `vo`→`of` 36/36), `readonly-transactions`(`@Service`의 public 조회 메서드, private 헬퍼 제외, java-ref 7/7), `domain-identifier-type`(식별자 값 객체, java-ref 13/14). `@Valid`는 `annotation-confinement`의 같은 대상 묶음으로 이미 잡힌다. 시험용 프로젝트 모음(`ilsim-runner/corpus/manifest.json`, `baseline/<id>.json`, 커밋): `ilsim-runner corpus add|update|check`(AI 없음). 프로젝트 경로는 기준 폴더(`--base`, `ILSIM_CORPUS_BASE`, 기본 `~/programming`) 기준 상대경로. 상태 키는 HEAD SHA이고 작업 트리가 더럽면 `SHA+해시`라 기준과 다르면 건너뛴다(java-ref·haru-simgi는 현재 더럽다: 빌드 설정만 바뀐 상태라 그 프로젝트를 커밋하면 `corpus update`로 기준을 다시 잡는다). 6개 프로젝트(java-ref, kotlin-ref, haru-simgi, multi-module, osiv, kafka-traffic-lab) 기준 저장, `check` 변화 없음. 채굴 코드를 고치면 `corpus check`로 규칙 변화를 확인하고 의도한 변화면 `corpus update`. 부분 레이어 프로젝트(커밋 안 함, 2026-10-21): `LayoutInference.anchored`는 반복 레이어가 없을 때 하위 패키지 3개 이상인 모듈(앵커)의 이름을 레이어로 삼고 그 이름을 하나라도 가진 모듈 3개 이상을 슬라이스로 본다. multi-module은 NOT_APPLICABLE→PARTIAL(`application→adapter` 금지, `domain` 순수 등 규칙 5개 추가), 나머지 5개 프로젝트는 `corpus check` 변화 없음, multi-module 기준만 `corpus update`로 갱신. `ArchitectureTest` 추가, `:ilsim-runner:check` 통과. 정답 라벨(커밋, 2026-10-21): `ilsim-runner corpus label <id> correct|wrong|expect <규칙id> [메모]`와 `corpus export <id> <등록한 프로젝트 id>`(웹·CLI에서 사용자가 승인/거절한 규칙을 가져옴, 추천 규칙·자동 결정은 제외). 라벨은 `corpus/labels/<id>.json`: correct=그 id가 계속 채굴돼야 함, wrong=채굴되면 안 됨, expect=그 접두사 규칙이 하나라도 있어야 함. `corpus check`가 라벨 충족 수를 보이고 위반이면 종료 코드 1. java-ref 19개·kotlin-ref 16개를 문서 기준으로 시드했다. 이 과정에서 Kotlin 식 본문 함수(`fun toCommand() = ...`)와 enum이 `package-method`를 흐리던 문제를 고쳤다. 남은 것: `*ReferenceId` 명명, multi-module식 부분 레이어 프로젝트, kotlin-ref 문서 대조, `global` 공유 커널 인식(지금은 슬라이스).

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
