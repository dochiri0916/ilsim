# 애플리케이션 컨벤션

## 핵심 원칙

- 애플리케이션 서비스는 유스케이스 인터페이스(`port/in`)를 구현한다.
- 애플리케이션은 도메인과 포트(`port/in`, `port/out`)에만 의존한다.
- 애플리케이션은 `infrastructure`, `presentation`에 직접 의존하지 않는다.
- 유스케이스 입출력은 `Command`, `Result` DTO로 분리한다.

## 포트 설계 원칙

- 아웃바운드 포트는 유스케이스가 필요한 계약만 노출한다.
- 조회는 `find...`(`Optional`)와 `load...`(필수 조회) 의미를 분리한다.
- `load...`의 예외 정책은 포트 default 메서드 또는 서비스에서 일관되게 처리한다.

## 트랜잭션 원칙

- 트랜잭션 경계는 애플리케이션 서비스에 둔다.
- 쓰기 유스케이스는 `@Transactional`, 조회 유스케이스는 `@Transactional(readOnly = true)`를 기본으로 한다.

## DTO 원칙

- 애플리케이션 외부 응답에 필요한 조회 전용 모델은 `port/out/dto`에 둔다.
- 프레젠테이션 DTO와 도메인 모델을 직접 섞지 않는다.

## 자동 검증

- `validateHexagonalArchitecture`
  - 애플리케이션 -> `infrastructure`/`presentation` 의존 금지
- `checkstyleMain`, `pmdMain`, `spotbugsMain`
  - 스타일/소스 품질/바이트코드 버그 기본 검증
- 샘플 `HexagonalArchitectureTest`(ArchUnit)
  - 애플리케이션 레이어 의존성 규칙 재검증

## 리뷰 검증

- 서비스 메서드가 도메인 로직을 과도하게 포함하지 않는지
- 포트가 인프라 세부 구현에 끌려가지 않는지
