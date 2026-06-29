# 테스트 컨벤션

## 테스트 계층

- 도메인 테스트: 생성/복원/불변식 검증을 우선한다.
- 아키텍처 테스트: 레이어 의존성 규칙을 ArchUnit으로 검증한다.
- 통합 테스트: 포트/어댑터/JPA/QueryDSL 연동 시나리오를 검증한다.

## 네이밍 원칙

- 테스트 메서드는 의도가 드러나는 문장형 이름을 사용한다.
- 테스트 데이터는 의미 있는 상수 이름으로 선언한다.

## 검증 우선순위

- 1순위: 도메인 불변식 위반 방지
- 2순위: 아키텍처 의존성 침범 방지
- 3순위: 인프라 조회/저장 회귀 방지
- 4순위: API 요청/응답 계약 검증

## 자동 검증

- `test` 태스크
  - JUnit 테스트 실행
- `jacocoTestReport`
  - JaCoCo XML/HTML 리포트 생성
- `jacocoTestCoverageVerification`
  - 전체 85%/80%, Domain 95%/90%, Application 90%/85%, Infrastructure 80%/70% 기준으로 Line/Branch 커버리지 검증
- `validateChangedCodeCoverage`
  - `-PchangedCoverageBaseRef=...`가 지정된 CI에서 변경된 Production 코드의 Line 90%, Branch 85% 커버리지 검증
- `validateArchUnitArchitecture`
  - ArchUnit 기반 레이어 의존성 검증
- `validateClaudeConventions`
  - CLAUDE.md 기반 소스 구조 규칙 검증
- `validatePitMutationGate`
  - PIT 플러그인이 적용된 프로젝트에서 Domain/Application Mutation Score 80%, Test Strength 85% 기준 검증
- `checkstyleTest`
  - 테스트 코드 스타일 검증
- `pmdTest`
  - 테스트 코드 품질 검증
- `spotbugsTest`
  - 테스트 클래스 바이트코드 버그 패턴 검증

## 실행 명령

- 전체 검증: `./gradlew check`
- ArchUnit만: `./gradlew validateArchUnitArchitecture`
- CLAUDE.md 구조 검증만: `./gradlew validateClaudeConventions`
- 변경 코드 커버리지: `./gradlew validateChangedCodeCoverage -PchangedCoverageBaseRef=origin/main`
- PIT 변이 테스트 게이트: `./gradlew validatePitMutationGate`

## 적용 정책

- 로컬 `check`는 테스트, 정적 분석, ArchUnit, JaCoCo 전체/계층별 커버리지를 포함한다.
- PR CI에서는 `-PchangedCoverageBaseRef=origin/main`처럼 기준 ref를 지정해 변경 코드 커버리지까지 실행한다.
- PIT는 프로젝트가 `info.solidsoft.pitest` 플러그인을 적용했을 때 설정되며, `enforcePitOnCheck = true`이면 `check`에 포함된다.
- 커버리지 기준은 프로젝트에서 더 높일 수 있지만 CLAUDE.md 기본값 아래로 낮출 수 없다.
