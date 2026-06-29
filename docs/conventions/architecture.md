# 아키텍처 컨벤션

## 레이어 구조

- `domain`: 핵심 비즈니스 모델
- `application`: 유스케이스/포트
- `adapter.in.web`: API 입출력 인바운드 어댑터
- `adapter.out`: 외부 기술 구현체 아웃바운드 어댑터

## 의존성 방향

- `domain` -> `application`/`adapter` 금지
- `application` -> `adapter` 금지
- `adapter.in.web` -> `adapter.out` 직접 의존 금지

## 모델 분리

- 도메인 모델과 영속 엔티티를 분리한다.
- 조회 최적화 모델(Read Model)과 쓰기 모델을 분리한다.

## 아키텍처 자동 검증

- 플러그인 Validator
  - `validateHexagonalArchitecture`
  - `validateArchUnitArchitecture`
  - `validateEntityNamingConvention`
  - `validateDomainStaticFactoryConvention`
  - `validateClaudeConventions`
  - `validateMigrationConventions`

## 품질 게이트 실행

- 전체: `./gradlew check`
- 도메인 전용: `./gradlew checkstyleDomain pmdDomain spotbugsDomain`
- 변경 코드 커버리지: `./gradlew validateChangedCodeCoverage -PchangedCoverageBaseRef=origin/main`
- PIT 변이 테스트: `./gradlew validatePitMutationGate`
