# 도메인 컨벤션

## 핵심 원칙

- 도메인 클래스는 `private` 기본 생성자를 사용한다.
- 인자 생성자는 사용하지 않는다.
- 객체 생성은 정적 팩토리 메서드(`public static`)를 사용한다.
- 도메인 클래스는 `final`을 기본으로 한다.
- 도메인 패키지에는 `@Entity`를 두지 않는다.
- 도메인은 `application`, `infrastructure`, `presentation`에 의존하지 않는다.
- 도메인은 프레임워크(Spring/JPA/QueryDSL)에 직접 의존하지 않는다.

## 값/불변식 원칙

- `null` 방어는 `requireNonNull(value)`를 사용한다. (메시지 미사용)
- 공백/형식 같은 도메인 불변식은 도메인 내부에서 직접 검증한다.
- 식별자는 문자열 기반 공개 식별자(`publicId`)를 우선 사용한다.

## 예외 원칙

- 도메인 불변식 위반은 명확한 예외로 처리한다.
- 예외 메시지는 API 사용자에게 노출될 수 있으므로 한국어 기준으로 작성한다.

## 자동 검증

- `validateDomainStaticFactoryConvention`
  - `private` 기본 생성자 강제
  - 인자 생성자 금지
  - 정적 팩토리 메서드 강제
- `validateEntityNamingConvention`
  - 도메인 패키지 `@Entity` 금지
- `validateHexagonalArchitecture`
  - 도메인 -> 외부 레이어 의존 금지
- `checkstyleDomain`
  - 프레임워크 import 금지(`IllegalImport`)
  - 스타일 규칙(`AvoidStarImport`, `NeedBraces`, `UnusedImports`, `FinalClass`)
- `pmdDomain`
  - 복잡도 규칙(`CyclomaticComplexity`, `NPathComplexity`)
- `spotbugsDomain`
  - 도메인 클래스 바이트코드 버그 탐지

## 리뷰 검증

- 값 객체/엔티티의 책임이 도메인 안에 유지되는지
- 도메인 이벤트/상태 전이 규칙이 서비스 계층으로 새지 않는지
