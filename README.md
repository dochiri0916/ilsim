# AI Orchestration

요구사항 분석, Agent 실행, 코드 위험 분석과 검증 흐름을 관리하는 통합 저장소다.

| 프로젝트 | 역할 | 실행 환경 |
|---|---|---|
| [Code Risk Analyzer](code-risk-analyzer/docs/README.md) | 요구사항·코드 위험·테스트 적정성 분석과 개발 검증 워크플로우 | Java 25, 독립 Gradle 빌드 |
| [Build Convention](build-convention/README.md) | Java/Spring 아키텍처·품질·테스트·커버리지·변이 검증을 수행하는 Gradle 플러그인 | Java 21, 독립 Gradle 빌드 |

`build-convention`은 필요한 대상 프로젝트에서 선택적으로 적용한다. `code-risk-analyzer`는 플러그인 적용 여부와 관계없이 독립적으로 사용할 수 있다. 현재 두 프로젝트의 빌드·배포와 보고서 형식은 각각 유지한다.

## 빠른 시작

```bash
./install.sh                  # 한 번만: 도구 확인, 빌드, ~/.local/bin/ask 설치
cd ~/work/my-spring-project   # start.spring.io 프로젝트 그대로, 설정 파일 없음
ask --spec spec.json "회원 가입 기능 추가해줘"  # 명세 파일 검증·계획 확인 (형식: code-risk-analyzer/docs/guides/SPEC_FILE.md)
ask --yes                     # 실행
```

자세한 동작은 [Agent 요약](code-risk-analyzer/docs/AGENT_BRIEF.md)의 "가장 쉬운 사용"을 본다.
회사 PC처럼 개인 GitHub 계정이 없는 곳에는 [다른 PC에 설치](code-risk-analyzer/docs/guides/WORK_PC_INSTALL.md)를 본다 (읽기 전용 Deploy key, 이후 `ask`가 알아서 pull·재빌드).
폰에서 상태 보기·요청·승인은 [폰에서 ask 쓰기](code-risk-analyzer/remote/README.md)를 본다 (텔레그램 봇, 실행은 PC에서만).

## 검증

각 프로젝트의 Gradle Wrapper를 해당 디렉터리에서 실행한다.

```bash
cd code-risk-analyzer
./gradlew clean build --no-daemon
```

```bash
cd build-convention
./gradlew clean check --no-daemon
```

상세 실행 방법과 지원 범위는 각 프로젝트 문서를 따른다.

## 저장소 이전

기존 프로젝트를 `git subtree`로 가져왔으며 `--squash`를 사용하지 않았다. 기존 커밋 SHA와 작성자·작성일을 유지하고 두 프로젝트의 이력을 이 저장소의 `main`에 연결했다. 분석기의 기존 미추적 소스는 별도 복사본에서 신규 커밋으로 기록한 뒤 가져왔으며, 이후 원본 저장소에 푸시된 정식 커밋도 병합했다.

기존 GitHub 브랜치는 `history/<project>/<branch>`에 보존한다. 기존 태그가 있으면 `<project>/<tag>` 이름으로 보존한다.

분석기 원본 저장소는 유지한다. Build Convention 원본 GitHub 저장소는 사용자 요청으로 삭제했으며, 코드·커밋·브랜치는 이 저장소에 보존한다. 원본 PR 기록은 이전되지 않았다.

- Build Convention 원본 Git 이력: `history/build-convention/main`
- [Code Risk Analyzer 원본](https://github.com/dochiri0916/code-risk-analyzer)

이 저장소에서 수정한 코드는 `ai-orchestration`의 `main`으로 푸시한다. 분석기 원본 저장소의 추가 변경은 `git subtree`로 병합한다.

분석기 원본의 비공개 설정에 맞춰 이 통합 저장소도 비공개로 생성했다. 프로필에 비공개 기여를 표시하려면 GitHub의 private contributions 표시 설정을 사용한다.
