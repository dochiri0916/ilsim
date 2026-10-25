# ilsim

일심(Ilsim)은 MCP 서버 기반 AI Agent 개발 플랫폼이다. 이 저장소 전체가 하나의 서비스이며, 루트의 Gradle 빌드 하나로 서버·Runner·분석기를 함께 빌드한다.

| 프로젝트 | 역할 | 실행 환경 |
|---|---|---|
| `ilsim-server` | 작업·단계·승인·비밀 저장을 맡는 Spring Boot 서버(Kotlin, 헥사고날) | Java 25 toolchain |
| `ilsim-runner` | Claude 앱이 거치는 일심 MCP 서버·훅과 분석·검증 CLI(Kotlin) | JVM 17 이상 |
| [Code Risk Analyzer](code-risk-analyzer/README.md) | Java/Spring/JPA 코드 위험·테스트 적정성 정적 분석 | Java 21 이상, 루트 Gradle 빌드의 모듈 |
| [Build Convention](build-convention/README.md) | Java/Spring 아키텍처·품질·테스트·커버리지·변이 검증을 수행하는 Gradle 플러그인 | Java 21, 루트에 복합 빌드로 연결 |

`build-convention`은 필요한 대상 프로젝트에서 선택적으로 적용한다. `code-risk-analyzer`는 플러그인 적용 여부와 관계없이 독립적으로 사용할 수 있다. `build-convention`의 배포와 보고서 형식은 그대로 유지한다.

## 빠른 시작

```bash
./gradlew clean build --no-daemon
java -jar code-risk-analyzer/build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan /path/to/project
```

Build Convention은 저장소 루트에서 다음처럼 빌드·검증하고 로컬 Maven에 배포한다.

```bash
cd build-convention
./gradlew clean check publishToMavenLocal --no-daemon   # 자체 Wrapper(Gradle 9.0.0) 사용
```

빌드에는 JDK 21이 필요하며, 분석기의 동시성 진행표 검증에는 Python 3을 사용한다. 소비 프로젝트의 플러그인 적용 방법은 [Build Convention README](build-convention/README.md#usage)를 따른다.

## 문서

[전체 문서 목차](docs/README.md)에서 사용·분석·컨벤션·개발 문서를 찾을 수 있다. 상세 설명은 주제별 기준 문서에서 관리한다.

| 필요한 것 | 문서 |
|---|---|
| 분석기 명령·CI 정책·클래스패스·실행 증거 | [분석기 참고서](code-risk-analyzer/docs/reference/analysis.md) |
| Java/Spring·Kotlin 소비 프로젝트 규칙과 템플릿 | [컨벤션 안내](build-convention/docs/conventions/README.md) |
| 저장소 빌드와 검증, 이전 이력과 브랜치 | [저장소 개발과 이력](docs/repository.md) |

## Claude 앱에서 쓰기

진입점은 Claude 앱(Claude Code) 하나다. 작업은 반드시 일심 MCP 서버를 거친다([설계](docs/mcp-design.md)).

1. 서버를 띄우고(`./gradlew :ilsim-server:bootRun`) Runner를 설치한다(`./gradlew :ilsim-runner:installDist`). `ilsim-runner/build/install/ilsim-runner/bin`을 PATH에 넣거나 `ILSIM_RUNNER_BIN`으로 실행 파일을 지정한다.
2. 프로젝트를 등록한다: `ilsim-runner add <id> <경로>`, 필요하면 `ilsim-runner set <id> test "<명령>"`. JEV를 쓰려면 `ilsim-runner jev-key set`으로 키를 등록한다(표준 입력으로 받아 서버에 암호화 저장).
3. Claude 앱에서 이 저장소를 마켓플레이스로 추가하고 `ilsim` 플러그인을 설치한다. 플러그인이 MCP 서버(`ilsim-runner mcp`)와 훅(`ilsim-runner hook`)을 등록한다.
4. 등록한 프로젝트에서 요청하면 Agent가 `start_task` → 단계 작업 → `submit_result`로 진행한다. 요구사항·테스트 케이스 단계는 Claude 앱에서 승인한다(`approve_stage`·`reject_stage`, 매번 사용자 확인 창). 일심 작업 없이 파일을 고치거나 미제출 상태로 끝내려 하면 훅이 막는다.

독립 검토와 분석의 Claude 호출은 PC의 기본 로그인(`~/.claude`)을 쓴다.

## 프로젝트 문서 지침

- 작업을 시작하면 그 프로젝트에서 Git이 추적하는 문서(`.md`·`.markdown`·`.adoc`·`.rst`)와 추가한 문서를 제목 단위로 나누고, JEV가 요청에 적용되는 구간을 골라 단계 지시에 원문으로 넣는다. 다른 등록 프로젝트의 경로와 현재 작업 산출물은 빼며, 어떤 언어·구조의 프로젝트에도 같게 동작한다.
- 제출할 때마다 고른 지침을 어겼는지 JEV로 판정해 어기면 제출이 실패한다. 판정이 애매하거나 JEV를 쓸 수 없으면 구현 단계에서 지침을 넣은 독립 검토를 한다.
- `ilsim-runner guidelines <id> "<요청>" [기준 Git ref]`로 JEV가 고르는 지침과 점수, 작업 트리 diff의 위반 판정을 미리 볼 수 있다.
- 문서 목록은 `ilsim-runner docs <id>`로 보고, `docs add <id> <경로>`(저장소 밖 파일·폴더 가능)·`docs exclude <id> <경로>`·`docs remove <id> <경로>`로 고친다.

## 아키텍처·컨벤션 추천

- 프로젝트 분석(`ilsim-runner architecture <id>`·`convention <id>`)은 코드에서 추출한 규칙과 함께 헥사고날·DDD·클린 아키텍처·객체지향·객체지향 생활 체조 기준의 추천 규칙(`recommend:` id)을 만든다. 의존 방향(도메인 → 애플리케이션 → 어댑터 금지), 도메인 순수성·캡슐화, 식별자 값 객체, 함수 길이·중첩, 파일 크기, 주석 없음, 생성자 주입 등이다. 추천은 AI 자동 승인 대상이 아니며 사용자가 Claude 앱에서 `review_rules`로 보고 `decide_rules`로 승인·거절한다(매번 사용자 확인 창).
- 지금 다 지키는 추천은 BLOCK, 위반이 있으면 REVIEW로 제안한다. 위반이 있는 추천은 Claude 앱에서 리팩터링 작업으로 진행하고, 다시 분석해 위반이 없어지면 승인을 유지한 채 BLOCK으로 강화된다.
- `ilsim-runner recommend <id|경로>`는 AI 없이 추천만 출력한다.

## 접속과 토큰

- Runner 토큰: 서버가 처음 뜰 때 `~/.ilsim/token`(소유자만 읽기)을 만들고, 일심 MCP 서버가 같은 파일을 읽는다. 환경 변수 `ILSIM_TOKEN`을 주면 그 값이 우선한다.
- 서버는 기본으로 `127.0.0.1`에만 열린다. Tailscale처럼 다른 기기에서 직접 붙으려면 `ILSIM_BIND=0.0.0.0`.
