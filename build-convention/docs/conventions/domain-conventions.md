# 도메인 컨벤션

아래 규칙은 필수 컨벤션이다.

- 의존 방향은 `adapter → application → domain`이다. `domain`은 `application`·`adapter`와 Spring·JPA에 의존하지 않는다. `application`은 Spring 사용을 허용하되 구체적인 어댑터·JPA API에 의존하지 않고, 어댑터와의 접점은 포트로만 정의한다. 다른 BC의 도메인과 저장소는 직접 참조하지 않는다. `domain`이 프로젝트 밖에서 사용할 수 있는 타입은 언어 표준 라이브러리(`java`·`kotlin`)와 UUID 생성 라이브러리뿐이며, 그 밖의 외부 타입은 기본으로 거부한다.
- 애그리거트는 함께 유지해야 하는 불변식을 기준으로 나눈다. 내부 객체의 변경은 루트를 통해 수행하고, 저장소는 루트 단위로 정의한다.
- 도메인 객체(루트·내부 Entity·VO)의 인스턴스 필드는 `private final`로 선언한다. 컬렉션은 방어적으로 복사하고 상태 변경은 새 객체로 반환하며, 애그리거트의 식별자는 유지한다.
- 도메인 컬렉션은 컬렉션 하나만 인스턴스 필드로 갖는 일급 컬렉션으로 감싸 검증과 연산을 캡슐화한다. 생성 시 방어적으로 복사하고, 접근자로도 변경 가능한 컬렉션을 노출하지 않는다. 적용 범위와 외부 계약의 예외는 [생활 체조](object-calisthenics.md) 규칙을 따른다.
- VO는 `record`가 아니라 `final class`로만 작성한다. 도메인에서 `record`는 `domain.event`의 이벤트와 `domain.outbox` 모델에만 허용하며, 그 밖의 위치(`domain`, `domain.model`, `domain.vo`)의 `record`는 검증에서 거부한다.
- VO의 생성자는 `private`, 값의 생성·조합 팩터리는 `of()`로 통일한다. ID 발급은 `generate()`, 문자열 파싱은 `parse()`, 빈 컬렉션 생성은 `empty()`를 허용한다.
- 의미·검증·연산이 필요한 값은 VO로 표현한다. 상태 enum은 `domain.vo`에 두며 `final class`·비공개 생성자·팩터리 규칙에서 제외한다. revision·sequence 같은 메타데이터는 원시 타입을 허용한다.
- 비공개 생성자에서 불변식을 검증한다. `create()`는 새 식별자와 초기 상태를 만들고, `restore()`는 저장된 식별자와 상태를 복원한다. `restore()`는 상태 불변식을 검증하며, 외부 업무 코드가 상태 전이를 우회하는 데 사용하지 않는다. 도메인 내부의 검증된 상태 변경(새 객체 반환)에는 재사용할 수 있다.
- `equals`·`hashCode`는 도메인 Entity는 식별자, VO는 구성 값을 기준으로 구현한다.
- 도메인 ID는 `{Domain}Id`로 표현하고 UUID v7을 발급·검증한다. 다른 BC의 ID는 로컬 `{Domain}ReferenceId`로 표현한다.
- 값의 검증·상태 전이·소유자에 따른 업무 규칙(예: 주문 소유자만 취소 가능)은 도메인 객체나 도메인 정책에 둔다. 소유권 규칙 위반은 `NotOrderOwnerException` 같은 도메인 예외로 표현하고, 인증된 요청자 확보와 유스케이스 접근 권한 확인은 애플리케이션에서 조율한다.
- 도메인 이벤트는 `domain.event`의 `record`로 작성한다. 루트는 이벤트를 보관하지 않고, 상태 변경 결과로 필요할 때 `placedEvent()`처럼 이벤트 생성 메서드를 제공한다. 도메인 객체·VO를 담을 수 있다. 공개 통합 이벤트로의 변환은 [BC 이벤트](bounded-context-events.md) 컨벤션을 따른다.
- Outbox 모델은 `global.domain.outbox`와 각 `domain.outbox`에 둔다. 메시지·전달 상태는 업무 흐름의 일부로 보아 도메인에 두며, 불변이고 `record`를 허용한다. 영속성과 전송은 어댑터가 담당한다.
- 업무 규칙 위반은 도메인 예외로 표현하며, 예외 계층은 [예외 컨벤션](exception-conventions.md)을 따른다.
- `global.domain`에는 둘 이상의 BC가 같은 의미로 쓰는 VO·예외·Outbox 모델만 둔다. 특정 BC의 업무 개념은 두지 않는다.
- 클래스·파라미터 선언과 코드 스타일은 [공통 코드 컨벤션](coding-conventions.md)을 따른다.

패키지 배치는 [패키지 구조](package-structure.md), 세부 규칙은 [애플리케이션](application-conventions.md), [어댑터](adapter-conventions.md), [영속성](persistence-conventions.md), [BC 이벤트](bounded-context-events.md) 컨벤션을 따른다.

## 생성과 이벤트 보완

- 도메인에서 Lombok을 사용하지 않는다. 생성·검증·상태 변경은 도메인 코드에 명시한다.
- 도메인 이벤트 이름은 `BookRegistered`처럼 과거형으로 작성하고 `Event` 접미사를 붙이지 않는다. record compact constructor에서 null 등 이벤트의 유효성을 검증한다.
- 업무 실패를 `IllegalArgumentException` 등 JDK 기본 예외로 표현하지 않는다. 해당 Context의 도메인 예외를 사용한다.
