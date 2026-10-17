# ilsim Repository Guidance

이 저장소 전체가 하나의 서비스(일심)이며, 루트의 Gradle 빌드 하나로 JVM 모듈을 묶는다.

- `ilsim-server/`: Spring Boot 서버. Kotlin, 헥사고날(`domain → application → adapter`), JPA. 구조 규칙은 `ArchitectureTest`가 강제한다.
- `ilsim-runner/`: 로컬 Runner. Kotlin, JVM 17 대상.
- `ilsim-web/`: React 웹. npm 프로젝트라 Gradle 빌드에는 포함하지 않는다.
- `code-risk-analyzer/`: Java/Spring/JPA 코드 위험과 테스트 적정성 정적 분석기. Java 21 기준.
- `build-convention/`: 선택적으로 적용하는 Gradle Convention Plugin. 복합 빌드(`includeBuild`)로 연결되어 있고 자체 Wrapper와 해당 디렉터리의 `AGENTS.md`를 우선한다.

## 작업 원칙

- `code-risk-analyzer` 작업은 먼저 [분석기 참고서](code-risk-analyzer/docs/reference/analysis.md)를 읽는다.
- 각 모듈의 기존 구조, 문서, 빌드와 배포 계약을 따른다. 공통 버전은 `gradle/libs.versions.toml`에서 관리한다.
- `code-risk-analyzer` 사용에 `build-convention` 적용을 필수 조건으로 추가하지 않는다.
- Gradle은 저장소 루트의 Wrapper로 실행한다(`./gradlew :ilsim-server:test`). `build-convention`만 자체 Wrapper를 쓴다.
- 요청 범위 밖 리팩터링이나 검증 규칙 완화를 하지 않는다.
- 기존 커밋 SHA와 이력을 보존한다. 이력 재작성이나 squash는 명시적인 요청 없이 하지 않는다.
- 세션을 시작하면 [세션 인수인계](docs/handoff.md)를 읽고, 작업을 마치면 갱신한다. PC 전용 설정에 기능을 두지 않는다.

## 응답 형식

토큰을 줄이기 위해 출력은 최소로 한다. 작업 결과 보고는 두 부분만 쓴다.

- 완료: 기능 단위로 한 줄씩(예: 상품 추가 기능, 주문 동시성 문제 해결). 검증 결과는 통과/실패만 덧붙인다.
- 결정 필요: 항목마다 안 2~3개와 각 장단점 한 줄, 추천안. 없으면 생략한다.

과정 설명, 파일별 변경 나열, 반복 요약은 쓰지 않는다. 사용자가 자세히 물을 때만 풀어 쓴다.

## 전체 검증

저장소 구조를 변경한 경우 [빌드와 검증](docs/repository.md#빌드와-검증)의 명령(루트 `./gradlew check`)으로 전체를 검증한다. 각 모듈 내부 변경에는 해당 모듈이 요구하는 추가 검증을 적용한다.
