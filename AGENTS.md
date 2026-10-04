# AI Orchestration Repository Guidance

이 저장소는 독립적인 두 Gradle 프로젝트를 하나의 Git 이력으로 관리한다.

- `code-risk-analyzer/`: 요구사항·코드 위험 분석과 개발 검증 워크플로우. Java 25를 사용한다.
- `build-convention/`: 선택적으로 적용하는 Gradle Convention Plugin. Java 21을 사용하며 해당 디렉터리의 `AGENTS.md`를 우선한다.

## 작업 원칙

- `code-risk-analyzer` 작업은 먼저 [`docs/AGENT_BRIEF.md`](code-risk-analyzer/docs/AGENT_BRIEF.md)를 읽고, 긴 현황·리서치 문서는 필요한 절만 연다.
- 각 프로젝트의 기존 구조, 문서, 빌드와 배포 계약을 따른다.
- `code-risk-analyzer` 사용에 `build-convention` 적용을 필수 조건으로 추가하지 않는다.
- 각 프로젝트의 Gradle Wrapper를 해당 디렉터리에서 실행한다.
- 요청 범위 밖 리팩터링이나 검증 규칙 완화를 하지 않는다.
- 기존 커밋 SHA와 이력을 보존한다. 이력 재작성이나 squash는 명시적인 요청 없이 하지 않는다.

## 응답 형식

토큰을 줄이기 위해 출력은 최소로 한다. 작업 결과 보고는 두 부분만 쓴다.

- 완료: 기능 단위로 한 줄씩(예: 상품 추가 기능, 주문 동시성 문제 해결). 검증 결과는 통과/실패만 덧붙인다.
- 결정 필요: 항목마다 안 2~3개와 각 장단점 한 줄, 추천안. 없으면 생략한다.

과정 설명, 파일별 변경 나열, 반복 요약은 쓰지 않는다. 사용자가 자세히 물을 때만 풀어 쓴다.

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
