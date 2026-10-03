# AI Orchestration

요구사항 분석, Agent 실행, 코드 위험 분석과 검증 흐름을 관리하는 통합 저장소다.

| 프로젝트 | 역할 | 실행 환경 |
|---|---|---|
| [Code Risk Analyzer](code-risk-analyzer/docs/README.md) | 요구사항·코드 위험·테스트 적정성 분석과 개발 검증 워크플로우 | Java 25, 독립 Gradle 빌드 |
| [Build Convention](build-convention/README.md) | Java/Spring 아키텍처·품질·테스트·커버리지·변이 검증을 수행하는 Gradle 플러그인 | Java 21, 독립 Gradle 빌드 |

`build-convention`은 필요한 대상 프로젝트에서 선택적으로 적용한다. `code-risk-analyzer`는 플러그인 적용 여부와 관계없이 독립적으로 사용할 수 있다. 현재 두 프로젝트의 빌드·배포와 보고서 형식은 각각 유지한다.

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

기존 프로젝트를 `git subtree`로 가져왔으며 `--squash`를 사용하지 않았다. 기존 커밋 SHA와 작성자·작성일을 유지하고 두 프로젝트의 이력을 이 저장소의 `main`에 연결했다. 분석기의 기존 미추적 소스는 별도 복사본에서 신규 커밋으로 기록한 뒤 가져왔다.

기존 GitHub 브랜치는 `history/<project>/<branch>`에 보존한다. 기존 태그가 있으면 `<project>/<tag>` 이름으로 보존한다.

원본 저장소의 코드·Git 이력·공개 범위는 변경하지 않았다. 원본 GitHub PR·이슈·릴리즈 등의 기록은 원본 저장소에 남아 있다.

- [Build Convention 원본](https://github.com/dochiri0916/build-convention)
- [Code Risk Analyzer 원본](https://github.com/dochiri0916/code-risk-analyzer)

분석기 원본의 비공개 설정에 맞춰 이 통합 저장소도 비공개로 생성했다. 프로필에 비공개 기여를 표시하려면 GitHub의 private contributions 표시 설정을 사용한다.
