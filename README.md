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

### 새 PC에서 설치하기

준비물: JDK 25와 JDK 21 이상, Docker(실행 중), Claude Code(Claude 앱). 아래 블록을 순서대로 터미널에 붙여 넣는다. 클론 위치는 `~/ilsim`이다.

**1. 설치**

```bash
[ -d ~/ilsim ] || git clone git@github.com:dochiri0916/ilsim.git ~/ilsim
cd ~/ilsim
docker compose up -d
./gradlew :ilsim-server:classes :ilsim-runner:installDist
scripts/dev-up.sh
grep -q 'ilsim-runner/bin' ~/.zshrc 2>/dev/null || echo 'export PATH="$HOME/ilsim/ilsim-runner/build/install/ilsim-runner/bin:$PATH"' >> ~/.zshrc
export PATH="$HOME/ilsim/ilsim-runner/build/install/ilsim-runner/bin:$PATH"
python3 -c 'import json,os; p=os.path.expanduser("~/.claude/settings.json"); s=json.load(open(p)) if os.path.exists(p) else {}; s.setdefault("env",{})["ILSIM_RUNNER_BIN"]=os.path.expanduser("~/ilsim/ilsim-runner/build/install/ilsim-runner/bin/ilsim-runner"); os.makedirs(os.path.dirname(p),exist_ok=True); json.dump(s,open(p,"w"),indent=2)'
claude plugin marketplace add dochiri0916/ilsim
claude plugin install ilsim@ilsim
claude plugin enable ilsim@ilsim
```

**2. JEV 키 등록** (키 입력을 기다리므로 따로 붙여 넣는다)

```bash
ilsim-runner jev-key set
```

그다음 Claude 앱을 완전히 종료했다가 다시 연다.

**3. 프로젝트 등록** (프로젝트마다. 경로, `my-app`(영문 소문자·숫자·하이픈), 테스트 명령만 바꾼다)

```bash
cd ~/path/to/my-app
ilsim-runner add my-app "$PWD"
ilsim-runner set my-app test "./gradlew test -q"
```

**4. 규칙 승인**: 그 프로젝트를 Claude 앱에서 열고 "프로젝트 분석해줘"라고 한 뒤, 나온 BLOCK 규칙을 보고 "전부 승인해줘"라고 한다.

**업데이트**

```bash
cd ~/ilsim && git pull && ./gradlew :ilsim-server:classes :ilsim-runner:installDist && scripts/dev-up.sh
claude plugin marketplace update ilsim && claude plugin update ilsim@ilsim
```

**막히면**

- 서버 확인: `curl -s localhost:8080/api/status`. 재부팅 뒤에는 `cd ~/ilsim && docker compose up -d && scripts/dev-up.sh`
- `dquote>`가 뜨면 Ctrl+C를 누르고 블록을 다시 붙여 넣는다.
- `~/ilsim`이 아닌 곳에 클론했다면 블록의 `~/ilsim`을 그 경로로 바꾼다.
- 3333 포트가 이미 쓰이면 `.env`에 `MYSQL_PORT=<포트>`를 적고, 서버는 `ILSIM_DB_URL='jdbc:mysql://127.0.0.1:<포트>/ilsim?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Seoul' scripts/dev-up.sh`로 띄운다.

토큰·JEV 키·등록 프로젝트·승인한 규칙은 PC마다 `~/.ilsim`과 로컬 MySQL에 따로 저장된다.

### 작업 흐름

1. 등록한 프로젝트에서 요청하면 Agent가 `start_task` → 단계 작업 → `submit_result`로 진행한다. 요구사항·테스트 케이스 문서는 프로젝트에 만들지 않고 `~/.ilsim/projects/<id>/specs/`와 서버에 저장하며, Claude 앱에서 승인한다(`approve_stage`·`reject_stage`, 매번 사용자 확인 창). 앞 단계 테스트가 틀려 구현을 통과할 수 없으면 `reopen_stage`로 테스트 코드 단계로 되돌린다(사용자 확인 창, 구현 변경은 보관했다가 되살림). 일심 작업 없이 파일을 고치거나 미제출 상태로 끝내려 하면 훅이 막는다.

독립 검토와 분석의 Claude 호출은 PC의 기본 로그인(`~/.claude`)을 쓴다. 이 저장소 자체는 `.claude/settings.json`이 ilsim 플러그인을 끄므로, 일심으로 일심을 고치려면 별도 복제본의 `.claude/settings.local.json`에서 `"enabledPlugins": {"ilsim@ilsim": true}`로 켠다.

## 프로젝트 문서 지침

- 작업을 시작하면 그 프로젝트에서 Git이 추적하는 문서(`.md`·`.markdown`·`.adoc`·`.rst`)와 추가한 문서를 제목 단위로 나누고, JEV가 요청에 적용되는 구간을 골라 단계 지시에 원문으로 넣는다. 다른 등록 프로젝트의 경로와 현재 작업 산출물은 빼며, 어떤 언어·구조의 프로젝트에도 같게 동작한다.
- 구간을 묻기 전에 문서 단위로 규칙 문서인지(작업 기록·계획·분석은 제외), 요청이 건드리는 모듈에 적용되는지 걸러 낸다. 고른 구간이 이 작업에서 읽으라고 한 문서는 점수가 낮아도 다시 고른다.
- 제출할 때마다 고른 지침을 어겼는지 JEV로 판정해 어기면 제출이 실패한다. 판정이 애매하거나 JEV를 쓸 수 없으면 구현 단계에서 지침을 넣은 독립 검토를 한다.
- 문서에 적힌 규칙 중 기계적으로 확인되는 것(이름 형식, 필수·금지 애너테이션, 금지 import·코드, 파일 위치, 크기 제한 등)은 `analyze_project`(`convention <id>`) 때 정적 규칙(`doc:`)으로 옮긴다. Claude가 옮기고 JEV가 원문과 같은지 판정하며, 승인하면 제출 때 바꾼 코드에 새로 생기는 위반을 막는다. 옮기지 못한 문장은 위 JEV 판정으로 확인한다(Java·Kotlin).
- `ilsim-runner guidelines <id> "<요청>" [기준 Git ref]`로 JEV가 고르는 지침과 점수, 작업 트리 diff의 위반 판정을 미리 볼 수 있다.
- `ilsim-runner jev-eval guidelines`는 정답이 있는 평가 세트(`ilsim-runner/corpus/jev-guideline`: 실제·가상 프로젝트 4개의 문서·요청·변경·문서 규칙 후보)로 운영과 같은 JEV 질문의 정확도를 재고, 질문별 놓침·오경보와 기준값 제안을 보인다(`--repeat`로 반복 안정성, `--sections`로 구간 정답 확인).
- 문서 목록은 `ilsim-runner docs <id>`로 보고, `docs add <id> <경로>`(저장소 밖 파일·폴더 가능)·`docs exclude <id> <경로>`·`docs remove <id> <경로>`로 고친다.

## 아키텍처·컨벤션 추천

- 프로젝트 분석(`ilsim-runner architecture <id>`·`convention <id>`)은 코드에서 추출한 규칙과 함께 헥사고날·DDD·클린 아키텍처·객체지향·객체지향 생활 체조 기준의 추천 규칙(`recommend:` id)을 만든다. 의존 방향(도메인 → 애플리케이션 → 어댑터 금지), 도메인 순수성·캡슐화, 식별자 값 객체, 함수 길이·중첩, 파일 크기, 주석 없음, 생성자 주입 등이다. 추천은 AI 자동 승인 대상이 아니며 사용자가 Claude 앱에서 `review_rules`로 보고 `decide_rules`로 승인·거절한다(매번 사용자 확인 창).
- 지금 다 지키는 추천은 BLOCK, 위반이 있으면 REVIEW로 제안한다. 위반이 있는 추천은 Claude 앱에서 리팩터링 작업으로 진행하고, 다시 분석해 위반이 없어지면 승인을 유지한 채 BLOCK으로 강화된다.
- `ilsim-runner recommend <id|경로>`는 AI 없이 추천만 출력한다.

## 접속과 토큰

- Runner 토큰: 서버가 처음 뜰 때 `~/.ilsim/token`(소유자만 읽기)을 만들고, 일심 MCP 서버가 같은 파일을 읽는다. 환경 변수 `ILSIM_TOKEN`을 주면 그 값이 우선한다.
- 서버는 기본으로 `127.0.0.1`에만 열린다. Tailscale처럼 다른 기기에서 직접 붙으려면 `ILSIM_BIND=0.0.0.0`.
