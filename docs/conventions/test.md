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
  - ArchUnit 테스트 실행
- `checkstyleTest`
  - 테스트 코드 스타일 검증
- `pmdTest`
  - 테스트 코드 품질 검증
- `spotbugsTest`
  - 테스트 클래스 바이트코드 버그 패턴 검증

## 실행 명령

- 전체 검증: `./gradlew check`
- 샘플 검증: `./gradlew -p samples/consumer-test clean check`
- ArchUnit만: `./gradlew -p samples/consumer-test test --tests "*HexagonalArchitectureTest"`
