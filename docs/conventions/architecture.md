# 아키텍처 컨벤션

## 레이어 구조

- `domain`: 핵심 비즈니스 모델
- `application`: 유스케이스/포트
- `infrastructure`: 외부 기술 구현체(Adapter)
- `presentation`: API 입출력

## 의존성 방향

- `domain` -> `application`/`infrastructure`/`presentation` 금지
- `application` -> `infrastructure`/`presentation` 금지
- `presentation` -> `infrastructure` 직접 의존 금지

## 모델 분리

- 도메인 모델과 영속 엔티티를 분리한다.
- 조회 최적화 모델(Read Model)과 쓰기 모델을 분리한다.

## 아키텍처 자동 검증

- 플러그인 Validator
  - `validateHexagonalArchitecture`
  - `validateEntityNamingConvention`
  - `validateDomainStaticFactoryConvention`
- ArchUnit(샘플 프로젝트)
  - `HexagonalArchitectureTest`

## 품질 게이트 실행

- 전체: `./gradlew check`
- 샘플 전체: `./gradlew -p samples/consumer-test clean check`
- 도메인 전용: `./gradlew checkstyleDomain pmdDomain spotbugsDomain`
