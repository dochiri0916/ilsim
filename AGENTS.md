# AI Orchestration Repository Guidance

이 저장소는 독립적인 두 Gradle 프로젝트를 하나의 Git 이력으로 관리한다.

- `code-risk-analyzer/`: 요구사항·코드 위험 분석과 개발 검증 워크플로우. Java 25를 사용한다.
- `build-convention/`: 선택적으로 적용하는 Gradle Convention Plugin. Java 21을 사용하며 해당 디렉터리의 `AGENTS.md`를 우선한다.

## 작업 원칙

- 각 프로젝트의 기존 구조, 문서, 빌드와 배포 계약을 따른다.
- `code-risk-analyzer` 사용에 `build-convention` 적용을 필수 조건으로 추가하지 않는다.
- 각 프로젝트의 Gradle Wrapper를 해당 디렉터리에서 실행한다.
- 요청 범위 밖 리팩터링이나 검증 규칙 완화를 하지 않는다.
- 기존 커밋 SHA와 이력을 보존한다. 이력 재작성이나 squash는 명시적인 요청 없이 하지 않는다.

## 전체 검증

저장소 구조를 변경한 경우 두 프로젝트를 각각 검증한다.

```bash
cd code-risk-analyzer
./gradlew clean build --no-daemon
```

```bash
cd build-convention
./gradlew clean check --no-daemon
```

각 프로젝트 내부 변경에는 해당 프로젝트가 요구하는 추가 검증을 적용한다.
