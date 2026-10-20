# ilsim

일심(Ilsim)은 MCP 서버 기반 AI Agent 개발 플랫폼이다. 이 저장소 전체가 하나의 서비스이며, 루트의 Gradle 빌드 하나로 서버·Runner·분석기를 함께 빌드한다.

| 프로젝트 | 역할 | 실행 환경 |
|---|---|---|
| `ilsim-server` | 작업·승인·모델 선택·로그인을 맡는 Spring Boot 서버(Kotlin, 헥사고날) | Java 25 toolchain |
| `ilsim-runner` | 로컬에서 Claude Code를 git worktree로 실행하는 Runner(Kotlin) | JVM 17 이상 |
| `ilsim-web` | 승인·진행 현황 웹 화면(React) | Node, npm |
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

## 웹 계정

웹 로그인 계정은 관리자가 DB(`accounts` 테이블)에 직접 만든다. 화면에서는 가입이나 계정 추가를 할 수 없고, 로그인한 사용자가 자기 비밀번호만 바꿀 수 있다. 비밀번호는 BCrypt 해시로 넣는다.

```bash
htpasswd -bnBC 10 "" '비밀번호' | tr -d ':\n'   # 해시 생성
```

```sql
INSERT INTO accounts (version, username, password_hash, created_at) VALUES (0, '아이디', '<해시>', NOW(3));
```

## Claude 계정과 공통 설정

- 웹 설정 → "Claude 계정"에서 이름을 넣고 추가하면 Runner PC가 `claude auth login`을 띄운다. 화면의 링크로 claude.ai에 로그인(승인)하고 나온 인증 코드를 넣으면 등록된다. 계정마다 `~/.ilsim/claude-accounts/<이름>`을 `CLAUDE_CONFIG_DIR`로 써서 로그인·세션 기록·키체인 항목이 분리된다. 로그인 정보는 그 PC에만 있으므로 다른 PC Runner는 그 PC에서 따로 등록한다.
- 새 세션을 열 때 남은 5시간·주간 사용량이 가장 여유 있는 계정으로 실행하고, 이어 가는 세션은 처음 계정으로 계속한다. 실행 중 한도에 걸리면 다른 계정으로 넘기지 않고 단계를 멈춘다. 구독이 없는 계정은 쓰지 않는다. 등록한 계정이 없으면 PC의 기본 로그인(`~/.claude`)을 쓴다.
- 웹 설정 → "Claude 공통 설정"의 CLAUDE.md·settings.json을 등록한 모든 계정 디렉터리에 똑같이 쓴다. 계정 실행에서는 `ANTHROPIC_API_KEY`처럼 API 과금으로 새는 변수를 지운다.
- 웹 설정 → "Claude 스킬"에 등록한 스킬(SKILL.md와 부속 파일, 폴더째 가져오기 가능)은 서버 DB(`claude_skills`)에 두고, Runner가 모든 계정 디렉터리의 `skills/<이름>`에 똑같이 설치한다. 일심이 설치한 스킬만 관리하고(`skills/.ilsim-skills`), 지우면 다음 동기화 때 PC에서도 지운다.
- 공통 설정과 스킬에는 PC마다 다른 절대 경로(`/Users/<이름>`, `/home/<이름>`, `C:\Users\<이름>`)를 저장할 수 없다. 스킬 폴더 기준 상대 경로나 `~`·`$HOME`을 쓴다.

## 아키텍처·컨벤션 추천

- 프로젝트 분석은 코드에서 추출한 규칙과 함께 헥사고날·DDD·클린 아키텍처·객체지향·객체지향 생활 체조 기준의 추천 규칙(`recommend:` id)을 만든다. 의존 방향(도메인 → 애플리케이션 → 어댑터 금지), 도메인 순수성·캡슐화, 식별자 값 객체, 함수 길이·중첩, 파일 크기, 주석 없음, 생성자 주입 등이다. 추천은 AI 자동 승인 대상이 아니며 사용자가 규칙·메모리 화면에서 승인·거절한다.
- 지금 다 지키는 추천은 BLOCK, 위반이 있으면 REVIEW로 제안한다. 위반이 있는 추천은 "업그레이드 작업 만들기"로 리팩터링 작업을 만들고, 다시 분석해 위반이 없어지면 승인을 유지한 채 BLOCK으로 강화된다.
- `ilsim-runner recommend <id|경로>`는 AI 없이 추천만 출력한다.

## 접속과 토큰

- Runner 토큰: 서버가 처음 뜰 때 `~/.ilsim/token`(소유자만 읽기)을 만들고, Runner가 같은 파일을 읽는다. 환경 변수 `ILSIM_TOKEN`을 주면 그 값이 우선한다.
- 서버는 기본으로 `127.0.0.1`에만 열린다. Tailscale처럼 다른 기기에서 직접 붙으려면 `ILSIM_BIND=0.0.0.0`.
- 터널(Cloudflare Tunnel 등) 뒤에서 쓰면 `ILSIM_FORWARD_HEADERS=native`(접속자 IP를 `X-Forwarded-For`에서 읽음, 루프백에서 온 요청만 신뢰)와 `ILSIM_SECURE_COOKIE=true`(HTTPS 전용 쿠키)를 함께 준다. 개발 서버(Vite)는 `xfwd`로 헤더를 붙인다.
- 로그인 실패 제한: 접속 IP당 5회 실패 시 1분, 계정당 20회 실패 시 15분 잠근다.
