# 도메인 컨벤션

## 핵심 원칙

- Domain Entity, Value Object, Aggregate, Domain Event는 Java `record`를 기본값으로 사용한다.
- 복잡한 Aggregate Root와 Domain Service는 불변성을 지키는 `final class`를 허용한다.
- 객체 생성은 정적 팩토리 메서드(`create`, `from`, `pending`, `reconstitute`)를 사용한다.
- 도메인 패키지에는 `@Entity`를 두지 않는다.
- 도메인은 `application`, `adapter`에 의존하지 않는다.
- 도메인은 프레임워크(Spring/JPA/QueryDSL)에 직접 의존하지 않는다.

## 값/불변식 원칙

- `null`, 공백, 형식 같은 도메인 불변식은 도메인 내부에서 직접 검증한다.
- 불변식 위반은 JDK 기본 예외가 아니라 `domain.exception`의 도메인 예외로 표현한다.
- Domain Entity와 Aggregate Root의 식별자는 `UserId`, `OrderId` 같은 식별자 VO로 표현한다.
- raw `String`, primitive, wrapper type은 Value Object 내부에서만 허용한다.
- 컬렉션 필드는 `OrderLines`, `Tags` 같은 일급 컬렉션 record로 감싼다.

## 예외 원칙

- 도메인 불변식 위반은 명확한 예외로 처리한다.
- 예외 메시지는 API 사용자에게 노출될 수 있으므로 한국어 기준으로 작성한다.

## 자동 검증

- `validateDomainStaticFactoryConvention`
  - Domain 생성 경로의 정적 팩토리 메서드 강제
- `validateEntityNamingConvention`
  - 도메인 패키지 `@Entity` 금지
- `validateHexagonalArchitecture`
  - 도메인 -> 외부 레이어 의존 금지
- `validateClaudeConventions`
  - 도메인 모델의 record 기본값과 final class 예외 검증
  - `domain.model`, `domain.event`, `domain.exception` 패키지 위치 검증
  - 도메인 record compact constructor, String VO null/blank 검증, 도메인 예외 사용 검증
  - 도메인 서비스 final class 강제
  - DB 기술 키(`Long id`), raw scalar, raw collection 필드 금지
  - 일급 컬렉션 record의 방어적 복사 강제
  - 식별 가능 객체 참조 필드의 `{Target}Id` VO 사용 검증
- `checkstyleDomain`
  - 프레임워크 import 금지(`IllegalImport`)
  - Lombok import 금지(`IllegalImport`)
  - 스타일 규칙(`AvoidStarImport`, `NeedBraces`, `UnusedImports`, `FinalClass`)
  - 파일/선언 규칙(`FileTabCharacter`, `NewlineAtEndOfFile`, `OneTopLevelClass`, `OuterTypeFilename`)
  - statement/modifier 규칙(`OneStatementPerLine`, `MultipleVariableDeclarations`, `ModifierOrder`, `RedundantModifier`)
- `pmdDomain`
  - 복잡도 규칙(`CyclomaticComplexity`, `NPathComplexity`)
- `spotbugsDomain`
  - 도메인 클래스 바이트코드 버그 탐지

## 리뷰 검증

- 값 객체/엔티티의 책임이 도메인 안에 유지되는지
- 도메인 이벤트/상태 전이 규칙이 서비스 계층으로 새지 않는지
