# 어댑터 컨벤션

## 공통 책임

- 입력 어댑터는 `adapter.in`, 출력 어댑터는 `adapter.out` 아래에 웹·메시징·영속성 등 기술별로 둔다.
- 입력 어댑터는 `UseCase`를 호출하고, 출력 어댑터는 `port.out`을 구현한다.
- 같은 BC의 도메인과 VO를 참조할 수 있다. 외부 타입과 내부 모델의 변환은 어댑터·Mapper에서 수행한다.
- 출력 어댑터는 기술 예외를 포트 계약의 예외로 번역한다. 기본·safe 구현은 동일한 포트 계약을 유지한다. 세부 규칙은 [예외 컨벤션](exception-conventions.md)을 따른다.
- 클래스·파라미터 선언, 의존성 주입과 코드 스타일은 [공통 코드 컨벤션](coding-conventions.md)을 따른다.

## 웹 입력 어댑터

- 컨트롤러는 요청 검증, Command·Query 변환 조율, UseCase 호출, 응답 변환 조율을 담당한다. 저장소를 직접 호출하거나 도메인 규칙을 구현하지 않는다.
- Request·Response는 `record`와 원시 값 중심으로 작성한다. 외부 계약이므로 원시 컬렉션을 허용하되 생성 시 방어적으로 복사하고 변경 가능한 컬렉션을 노출하지 않는다. Command·Query로 변환할 때 애플리케이션의 일급 컬렉션으로 감싸고, Result를 Response로 변환할 때 외부 계약의 목록으로 변환한다. 입력 형식은 어댑터에서, 도메인 불변식은 도메인에서 검증한다.
- Request에서 Command로의 단순 필드 변환은 인스턴스 메서드 `toCommand()`에, Result에서 Response로의 단순 필드 변환은 Response의 정적 팩토리 메서드 `from(final XxxResult result)`에 둔다. 컨트롤러는 `useCase.execute(request.toCommand())`, `Response.from(result)` 형태로 사용한다.
- 인증된 요청자 식별자·경로 변수 등 요청 본문 외의 값이 변환에 필요하면 컨트롤러에서 확보해 `toCommand(final String memberPublicId)`처럼 인자로 전달한다. Request·Response의 변환 메서드에서 서비스 호출이나 업무 판단을 수행하지 않으며, 복잡한 변환은 웹 어댑터 내부의 별도 Mapper로 분리한다. 대응하는 Command·Result가 없는 요청·응답에는 변환을 위해 빈 계약을 추가하지 않는다.
- 변환 의존 방향은 `adapter.in.web → application.port.in`을 유지한다. Command·Result에 웹 타입을 참조하는 `from(Request)`·`toResponse()`를 두지 않는다.
- 모든 Request 필드에는 입력 형식 검증용 `jakarta.validation` 어노테이션을 선언하고, 컨트롤러는 `@Valid @RequestBody`로 받는다. 문자열은 `@NotBlank`, 컬렉션은 `@NotEmpty`, 숫자는 `@Min`·`@Max` 등 범위 제약을 기본으로 하며 `message`에 한국어 사유를 명시한다. 도메인 불변식은 Request가 아닌 도메인에서 검증한다.
- 웹 예외 처리기는 업무 예외를 HTTP 상태와 오류 코드로 매핑한다. 응답 형식과 내부 오류 비노출 규칙은 [예외 컨벤션](exception-conventions.md)을 따른다.

## 메시징 어댑터

- 메시징 입력 어댑터는 공개 통합 이벤트를 로컬 Command로 변환해 UseCase를 호출한다. 메시지 전송과 Inbox·Outbox 영속 처리는 어댑터에 둔다.
- 전송 기술의 교체 경계와 Kafka 규칙은 [BC 이벤트](bounded-context-events.md), 전달·멱등성·재시도 규칙은 [Outbox/Inbox](outbox-inbox-conventions.md) 컨벤션을 따른다.

패키지 배치는 [패키지 구조](package-structure.md), 영속성 어댑터의 세부 규칙은 [영속성](persistence-conventions.md) 컨벤션을 따른다.

## 웹 변환 보완

- Request에서 Query로의 단순 변환은 public 인스턴스 `toQuery()`에 둔다. 분기·반복이 필요한 Request 변환은 웹 Mapper로 분리한다.
- Response 필드나 공개 반환 타입으로 애플리케이션 모델을 노출하지 않는다. `Response.from(Result)`의 내부 변환에서는 같은 BC의 Result DTO·일급 컬렉션을 참조할 수 있다.
