# 애플리케이션 컨벤션

- 입력 계약은 UseCase·Command·Query·Result로 구분하고, 외부 기능은 출력 포트로 정의한다. 패키지 배치는 [패키지 구조](package-structure.md)를 따른다.
- 서비스는 `UseCase`를 구현한다. 구체적인 어댑터와 JPA API에 의존하지 않는다.
- Command·Query·Result는 `record`와 원시 값 중심으로 작성하고 HTTP·영속성 타입을 포함하지 않는다. 조회 인자가 있으면 Query에 조회 조건을 담아 전달하며, 조회 인자가 없는 경우 빈 Query를 만들지 않는다.
- Command·Query·Result와 출력 포트 계약의 컬렉션은 애플리케이션의 일급 컬렉션으로 감싼다. 단순 전달용 컬렉션도 포함하며, 컬렉션 하나만 인스턴스 필드로 갖는 불변 `final class` 또는 `record`로 작성한다. 입력을 방어적으로 복사하고 변경 가능한 컬렉션을 노출하지 않는다. 입력 계약의 일급 컬렉션은 해당 `port.in.command`·`query`·`result`, 출력 계약의 일급 컬렉션은 `port.out`에 둔다. 예: `GetCartResult`의 항목은 `CartResultItems`로 감싸고, 항목 자체는 원시 값 중심의 DTO로 유지한다. 공개 통합 이벤트의 원시 컬렉션 예외는 [BC 이벤트](bounded-context-events.md) 컨벤션을 따른다.
- 내부 검색 조건은 `port.out`의 `{Domain}SearchCondition` record로 작성하고 서비스에서 Query의 값을 VO·도메인 enum으로 변환한다. 페이지·크기만 필요한 조회에는 만들지 않는다.
- 서비스는 유스케이스 흐름(입력 변환 → 조회 → 도메인 호출 → 저장 → 이벤트 발행)만 조율한다. 도메인 객체를 Result로 바꾸는 매핑은 `application`의 `{Domain}ResultMapper`에 둔다.
- 서비스에서 입력을 VO로 변환하고 도메인 메서드를 호출한다. 업무 규칙의 배치는 [도메인 컨벤션](domain-conventions.md)을 따른다.
- 서비스는 인증된 요청자를 확인하고 접근 권한 확인을 조율한다. 트랜잭션은 유스케이스 단위로 선언하고 조회는 `@Transactional(readOnly = true)`를 사용한다.
- 저장소 포트는 애그리거트 단위로 정의하고, 도메인 식별자와 값에는 VO를 사용한다. 페이지·sequence 같은 메타데이터는 원시 타입을 허용한다.
- 조회 실패와 인증된 요청자의 역할·권한 부족 등 유스케이스 접근 권한 실패는 의미 있는 애플리케이션 예외로 표현한다. 도메인의 소유권 규칙 위반은 도메인 예외를 그대로 전달하며, 유스케이스가 별도의 실패 의미를 계약으로 제공해야 할 때만 해당 예외를 애플리케이션 예외로 변환한다. 세부 규칙은 [예외 컨벤션](exception-conventions.md)을 따른다.
- 다른 BC의 도메인과 저장소를 직접 호출하지 않는다. 공개 통합 이벤트의 계약과 발행 방식은 [BC 이벤트](bounded-context-events.md) 컨벤션을 따른다. Kafka 타입은 계약과 서비스에 포함하지 않는다.
- 클래스·파라미터 선언, 의존성 주입과 코드 스타일은 [공통 코드 컨벤션](coding-conventions.md)을 따른다.

관련 규칙은 [도메인](domain-conventions.md), [어댑터](adapter-conventions.md), [Querydsl](querydsl-conventions.md) 컨벤션을 따른다.
