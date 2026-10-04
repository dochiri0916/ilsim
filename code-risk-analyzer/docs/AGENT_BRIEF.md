# Agent 요약

에이전트가 작업을 시작할 때 먼저 읽는 한 장 요약이다.

## 무엇인가

- `ai` 명령([`scripts/agent_pick.py`](../scripts/agent_pick.py)): 프로젝트 폴더에서 `ai 작업 내용`을 받으면 Claude Code·Codex 중 요금제 한도가 유리한 쪽을 화면 없이 실행하고, 진행·답과 검사 결과만 보여 준다.
- Java 25 Spring Boot 분석기(`code-risk-analyzer`): Java/Spring/JPA 코드의 위험 규칙과 테스트 적정성을 정적으로 분석한다. `ai`가 작업 뒤 바뀐 파일에 `scan`을 실행한다.
- build-convention 검사([`scripts/convention_check.py`](../scripts/convention_check.py)): 대상 프로젝트의 빌드 파일을 고치지 않고 Gradle init script로 플러그인을 붙여 `check`를 실행한다.

## `ai` 흐름

```bash
ai 회원 탈퇴 API 추가해줘     # 고른 CLI가 이 폴더에서 작업 → 바뀐 파일이 있으면 검사
ai 검사 결과 고쳐줘           # 같은 폴더·같은 CLI면 3시간 안의 대화를 이어서, 직전 검사 실패 내용을 한 번 함께 보냄
ai --new ... / ai claude ... / ai codex ... / ai --why / ai --mcp ...
```

- 선택(토큰 0, 로컬 기록만): 한도 소진은 제외 → 곧 초기화될 남은 한도 먼저 → Codex 주간 소모가 일정보다 빠르거나 5시간 여유가 적으면(Claude 기록이 더 나쁘지 않을 때) Claude → 그 외 Codex. Codex는 로그의 한도 %·초기화 시각, Claude는 `ai` 실행 중 `claude -p`가 알려 준 한도 %(`~/.local/state/ai-orchestration/claude-limits.json`), 기록이 없거나 초기화가 지났으면 한도 도달 메시지와 5시간 창으로 추정한다.
- 모델·추론: 곧 초기화될 여유가 30% 이상이면 TIER_4 경로의 가장 높은 설정, 아니면 요청의 위험 단어(결제·동시성·트랜잭션 등)면 TIER_3, 그 외 TIER_2. 경로는 [`config/models/`](../config/models/)(PC별 덮어쓰기 `~/.config/code-risk/`)이고, 이름에 `astra`·`fable`·`opus`가 든 모델과 `max` effort는 실행 전에 거부한다.
- 실행: Claude는 `-p` stream-json, `acceptEdits`와 gradlew·git 조회 명령만 허용. Codex는 `exec --json`, `workspace-write` 샌드박스. MCP는 끄고(`--mcp`로 유지) 답변은 핵심만 쓰게 한다. 작업 중 한도에 걸리면 다른 CLI가 같은 작업을 이어받는다.
- 검사(파일이 바뀐 작업 뒤, 모델 호출 없음): build-convention `check`와 바뀐 `src/main` Java 파일 `scan`. `TYPESAFE_API_KEY`가 있으면 정적으로 확정 못 한 항목을 JEV가 검토하고 의견만 덧붙인다(호출 상한: `.code-risk.yml`의 `jev.max-calls`, 없으면 20). 요청에 "컨벤션 없이"·"위험 분석 빼고"·"검사 없이"가 있으면 그 명령에서만 건너뛴다.
- 업데이트: 작업을 받으면 먼저 이 checkout을 fast-forward하고 바뀐 프로젝트만 다시 빌드한다([`scripts/self_update.py`](../scripts/self_update.py), 끄기 `ASK_NO_UPDATE=1`).
- 기타: 사용량·한도 % `ai --usage`(Claude 한도는 마지막 `ai` 실행 시점 값), 폰 원격 [`remote/`](../remote/README.md)(`ask-remote`가 폰 요청을 `ai`로 실행).

## 분석기 명령

`CRA` = `java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar` (`./gradlew bootJar`로 생성)

| 명령 | 용도 |
|---|---|
| `CRA scan <project> [--files-file f] [--baseline b] [--jev true] [--fail-on] [--format json --output f]` | 위험 21개 규칙 + `TEST-001` 적정성. 종료 1 게이트 실패, 2 입력 오류, 3 분석 실패 |
| `CRA baseline create <project>` | 기존 finding 기준선(`scan --baseline`용) |
| `CRA rules [ID]` | 규칙 목록·설명 |
| `CRA validators <project>` | `.code-risk.yml`의 `validators` 설정 검증(JSON, null: 미설정). 컨벤션 검사가 아키텍처·나머지 검사 on/off에 쓴다 |

규칙: `SEC-001~007`(SQL·Command·Path·SSRF·비밀값·객체 권한·Tenant), `CON-001~004`, `IDEM-001~004`, `TX-001~005`, `REL-001`.

## 지켜야 할 것

- JEV 결과는 참고 근거다. 확률로 검사를 건너뛰거나 결과를 바꾸지 않는다.
- REVIEW·PARTIAL·UNKNOWN은 통과로 보고하지 않는다.
- 키(`TYPESAFE_API_KEY` 등)를 출력하거나 파일에 남기지 않는다.

## 더 읽을 곳

| 필요한 것 | 문서 |
|---|---|
| `.code-risk.yml` 통과 정책 | [CI_POLICY.md](guides/CI_POLICY.md) |
| 외부 JAR·클래스 디렉터리 | [CLASSPATH.md](guides/CLASSPATH.md) |
| 실행 증거 입력 | [EXECUTION_EVIDENCE.md](guides/EXECUTION_EVIDENCE.md) |
| 다른 PC 설치 | [WORK_PC_INSTALL.md](guides/WORK_PC_INSTALL.md) |
