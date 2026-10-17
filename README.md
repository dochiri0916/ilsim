# ilsim

코드 위험 정적 분석기와 Gradle Convention Plugin을 독립적으로 개발·사용하는 저장소다.

| 프로젝트 | 역할 | 실행 환경 |
|---|---|---|
| [Code Risk Analyzer](code-risk-analyzer/README.md) | Java/Spring/JPA 코드 위험·테스트 적정성 정적 분석 | Java 21 이상, 독립 Gradle 빌드 |
| [Build Convention](build-convention/README.md) | Java/Spring 아키텍처·품질·테스트·커버리지·변이 검증을 수행하는 Gradle 플러그인 | Java 21, 독립 Gradle 빌드 |

`build-convention`은 필요한 대상 프로젝트에서 선택적으로 적용한다. `code-risk-analyzer`는 플러그인 적용 여부와 관계없이 독립적으로 사용할 수 있다. 현재 두 프로젝트의 빌드·배포와 보고서 형식은 각각 유지한다.

## 빠른 시작

```bash
cd code-risk-analyzer
./gradlew clean build --no-daemon
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan /path/to/project
```

Build Convention은 저장소 루트에서 다음처럼 빌드·검증하고 로컬 Maven에 배포한다.

```bash
cd build-convention
./gradlew clean check publishToMavenLocal --no-daemon
```

빌드에는 JDK 21이 필요하며, 분석기의 동시성 진행표 검증에는 Python 3을 사용한다. 소비 프로젝트의 플러그인 적용 방법은 [Build Convention README](build-convention/README.md#usage)를 따른다.

## 문서

[전체 문서 목차](docs/README.md)에서 사용·분석·컨벤션·개발 문서를 찾을 수 있다. 상세 설명은 주제별 기준 문서에서 관리한다.

| 필요한 것 | 문서 |
|---|---|
| 분석기 명령·CI 정책·클래스패스·실행 증거 | [분석기 참고서](code-risk-analyzer/docs/reference/analysis.md) |
| Java/Spring·Kotlin 소비 프로젝트 규칙과 템플릿 | [컨벤션 안내](build-convention/docs/conventions/README.md) |
| 저장소 빌드와 검증, 이전 이력과 브랜치 | [저장소 개발과 이력](docs/repository.md) |
